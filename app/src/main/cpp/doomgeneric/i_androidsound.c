// Android sound for doomgeneric:
//  - SFX: small software mixer fed to an AAudio output stream
//  - Music: MUS -> MIDI (mus2mid) played by android.media.MediaPlayer via JNI

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <pthread.h>

#include <aaudio/AAudio.h>

#include "config.h"
#include "doomfeatures.h"
#include "doomtype.h"
#include "memio.h"
#include "mus2mid.h"
#include "deh_str.h"
#include "i_sound.h"
#include "i_system.h"
#include "m_misc.h"
#include "w_wad.h"
#include "z_zone.h"

// referenced by I_BindSoundVariables (config compatibility only)
int use_libsamplerate = 0;
float libsamplerate_scale = 0.65f;

// bridge functions implemented in doomgeneric_android.c
extern void DG_MusicPlay(const void *midi, int len, int loop);
extern void DG_MusicStop(void);
extern void DG_MusicPause(int paused);
extern void DG_MusicVolume(float v);

#define OUT_RATE 44100
#define MAX_CH 16

typedef struct
{
    int16_t *data;   // mono, OUT_RATE
    int len;         // samples
} sample_t;

typedef struct
{
    const int16_t *data;
    int len;
    int pos;
    int lgain;       // 0..254
    int rgain;       // 0..254
    int active;
} channel_t_mix;

static channel_t_mix chans[MAX_CH];
static pthread_mutex_t mix_mx = PTHREAD_MUTEX_INITIALIZER;
static AAudioStream *stream = NULL;
static volatile int stream_error = 0;
static int sound_initialized = 0;
static int use_prefix = 0;

// ------------------------------------------------------------ audio callback

static aaudio_data_callback_result_t audio_cb(AAudioStream *s, void *user, void *audioData, int32_t numFrames)
{
    (void)s; (void)user;
    int16_t *out = (int16_t *)audioData;
    int32_t i;

    pthread_mutex_lock(&mix_mx);
    for (i = 0; i < numFrames; i++)
    {
        int l = 0, r = 0;
        for (int c = 0; c < MAX_CH; c++)
        {
            channel_t_mix *ch = &chans[c];
            if (!ch->active)
                continue;
            int smp = ch->data[ch->pos++];
            l += (smp * ch->lgain) >> 8;
            r += (smp * ch->rgain) >> 8;
            if (ch->pos >= ch->len)
                ch->active = 0;
        }
        // soft headroom
        l = (l * 3) >> 2;
        r = (r * 3) >> 2;
        if (l > 32767) l = 32767; else if (l < -32768) l = -32768;
        if (r > 32767) r = 32767; else if (r < -32768) r = -32768;
        out[i * 2] = (int16_t)l;
        out[i * 2 + 1] = (int16_t)r;
    }
    pthread_mutex_unlock(&mix_mx);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static void error_cb(AAudioStream *s, void *user, aaudio_result_t error)
{
    (void)s; (void)user; (void)error;
    stream_error = 1;
}

static int open_stream(void)
{
    AAudioStreamBuilder *b = NULL;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK)
        return 0;
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setSampleRate(b, OUT_RATE);
    AAudioStreamBuilder_setChannelCount(b, 2);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setDataCallback(b, audio_cb, NULL);
    AAudioStreamBuilder_setErrorCallback(b, error_cb, NULL);
    aaudio_result_t r = AAudioStreamBuilder_openStream(b, &stream);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK)
    {
        stream = NULL;
        return 0;
    }
    if (AAudioStream_requestStart(stream) != AAUDIO_OK)
    {
        AAudioStream_close(stream);
        stream = NULL;
        return 0;
    }
    stream_error = 0;
    return 1;
}

static void close_stream(void)
{
    if (stream != NULL)
    {
        AAudioStream_requestStop(stream);
        AAudioStream_close(stream);
        stream = NULL;
    }
}

// Java side calls this on pause/resume
void DG_AudioPause(int paused)
{
    if (stream == NULL)
        return;
    if (paused)
        AAudioStream_requestPause(stream);
    else
        AAudioStream_requestStart(stream);
}

// ------------------------------------------------------------ sfx

static void GetSfxLumpName(sfxinfo_t *sfx, char *buf, size_t buf_len)
{
    if (sfx->link != NULL)
        sfx = sfx->link;

    if (use_prefix)
        M_snprintf(buf, buf_len, "ds%s", DEH_String(sfx->name));
    else
        M_StringCopy(buf, DEH_String(sfx->name), buf_len);
}

static int A_GetSfxLumpNum(sfxinfo_t *sfx)
{
    char namebuf[9];
    GetSfxLumpName(sfx, namebuf, sizeof(namebuf));
    return W_GetNumForName(namebuf);
}

static sample_t *LoadSample(sfxinfo_t *sfx)
{
    sample_t *res = (sample_t *)sfx->driver_data;
    if (res != NULL)
        return res;

    int lumpnum = sfx->lumpnum;
    if (lumpnum < 0)
        lumpnum = sfx->lumpnum = A_GetSfxLumpNum(sfx);

    int lumplen = W_LumpLength(lumpnum);
    byte *data = W_CacheLumpNum(lumpnum, PU_STATIC);

    if (lumplen < 8 || data[0] != 0x03 || data[1] != 0x00)
    {
        W_ReleaseLumpNum(lumpnum);
        return NULL;
    }

    int rate = data[2] | (data[3] << 8);
    uint32_t length = data[4] | (data[5] << 8) | (data[6] << 16) | ((uint32_t)data[7] << 24);

    if (length > (uint32_t)lumplen - 8 || length <= 48 || rate <= 0)
    {
        W_ReleaseLumpNum(lumpnum);
        return NULL;
    }

    // DMX skips the first and last 16 bytes
    const byte *pcm = data + 8 + 16;
    int n = (int)length - 32;

    int outlen = (int)(((int64_t)n * OUT_RATE) / rate);
    if (outlen < 1)
    {
        W_ReleaseLumpNum(lumpnum);
        return NULL;
    }

    res = (sample_t *)malloc(sizeof(sample_t));
    res->data = (int16_t *)malloc((size_t)outlen * sizeof(int16_t));
    res->len = outlen;

    for (int i = 0; i < outlen; i++)
    {
        int64_t p = (int64_t)i * rate * 256 / OUT_RATE;  // 8.8 fixed
        int idx = (int)(p >> 8);
        int frac = (int)(p & 255);
        int a = pcm[idx < n ? idx : n - 1];
        int b = pcm[idx + 1 < n ? idx + 1 : n - 1];
        int v = a * (256 - frac) + b * frac;            // 0..255*256
        res->data[i] = (int16_t)((v - 128 * 256) );     // (-128..127)*256
    }

    W_ReleaseLumpNum(lumpnum);
    sfx->driver_data = res;
    return res;
}

static void CalcGains(int vol, int sep, int *l, int *r)
{
    *l = ((254 - sep) * vol) / 127;
    *r = (sep * vol) / 127;
    if (*l > 254) *l = 254;
    if (*r > 254) *r = 254;
    if (*l < 0) *l = 0;
    if (*r < 0) *r = 0;
}

static boolean A_InitSound(boolean use_sfx_prefix)
{
    use_prefix = use_sfx_prefix;
    memset(chans, 0, sizeof(chans));
    if (!open_stream())
        return false;
    sound_initialized = 1;
    return true;
}

static void A_ShutdownSound(void)
{
    if (!sound_initialized)
        return;
    close_stream();
    sound_initialized = 0;
}

static void A_UpdateSound(void)
{
    // recover from route changes (headphones etc.)
    if (sound_initialized && stream_error)
    {
        close_stream();
        open_stream();
    }
}

static void A_UpdateSoundParams(int channel, int vol, int sep)
{
    if (!sound_initialized || channel < 0 || channel >= MAX_CH)
        return;
    int l, r;
    CalcGains(vol, sep, &l, &r);
    pthread_mutex_lock(&mix_mx);
    chans[channel].lgain = l;
    chans[channel].rgain = r;
    pthread_mutex_unlock(&mix_mx);
}

static int A_StartSound(sfxinfo_t *sfxinfo, int channel, int vol, int sep)
{
    if (!sound_initialized || channel < 0 || channel >= MAX_CH)
        return -1;

    sample_t *smp = LoadSample(sfxinfo);
    if (smp == NULL)
        return -1;

    int l, r;
    CalcGains(vol, sep, &l, &r);

    pthread_mutex_lock(&mix_mx);
    chans[channel].data = smp->data;
    chans[channel].len = smp->len;
    chans[channel].pos = 0;
    chans[channel].lgain = l;
    chans[channel].rgain = r;
    chans[channel].active = 1;
    pthread_mutex_unlock(&mix_mx);
    return channel;
}

static void A_StopSound(int channel)
{
    if (!sound_initialized || channel < 0 || channel >= MAX_CH)
        return;
    pthread_mutex_lock(&mix_mx);
    chans[channel].active = 0;
    pthread_mutex_unlock(&mix_mx);
}

static boolean A_SoundIsPlaying(int channel)
{
    if (!sound_initialized || channel < 0 || channel >= MAX_CH)
        return false;
    return chans[channel].active ? true : false;
}

static void A_PrecacheSounds(sfxinfo_t *sounds, int num_sounds)
{
    (void)sounds; (void)num_sounds;  // loaded lazily
}

static snddevice_t sound_devices[] =
{
    SNDDEVICE_SB,
    SNDDEVICE_PAS,
    SNDDEVICE_GUS,
    SNDDEVICE_WAVEBLASTER,
    SNDDEVICE_SOUNDCANVAS,
    SNDDEVICE_AWE32,
};

sound_module_t DG_sound_module =
{
    sound_devices,
    sizeof(sound_devices) / sizeof(sound_devices[0]),
    A_InitSound,
    A_ShutdownSound,
    A_GetSfxLumpNum,
    A_UpdateSound,
    A_UpdateSoundParams,
    A_StartSound,
    A_StopSound,
    A_SoundIsPlaying,
    A_PrecacheSounds,
};

// ------------------------------------------------------------ music

typedef struct
{
    void *midi;
    int len;
} song_t;

static int music_playing = 0;

static boolean M_Init(void)
{
    return true;
}

static void M_Shutdown(void)
{
    DG_MusicStop();
    music_playing = 0;
}

static void M_SetVolume(int volume)
{
    DG_MusicVolume((float)volume / 127.0f);
}

static void M_Pause(void)
{
    DG_MusicPause(1);
}

static void M_Resume(void)
{
    DG_MusicPause(0);
}

static void *M_Register(void *data, int len)
{
    song_t *song = NULL;

    if (len > 4 && !memcmp(data, "MThd", 4))
    {
        song = (song_t *)malloc(sizeof(song_t));
        song->midi = malloc((size_t)len);
        memcpy(song->midi, data, (size_t)len);
        song->len = len;
        return song;
    }

    MEMFILE *in = mem_fopen_read(data, (size_t)len);
    MEMFILE *out = mem_fopen_write();

    if (mus2mid(in, out) == 0)
    {
        void *buf;
        size_t buflen;
        mem_get_buf(out, &buf, &buflen);
        song = (song_t *)malloc(sizeof(song_t));
        song->midi = malloc(buflen);
        memcpy(song->midi, buf, buflen);
        song->len = (int)buflen;
    }

    mem_fclose(in);
    mem_fclose(out);
    return song;
}

static void M_UnRegister(void *handle)
{
    song_t *song = (song_t *)handle;
    if (song != NULL)
    {
        free(song->midi);
        free(song);
    }
}

static void M_Play(void *handle, boolean looping)
{
    song_t *song = (song_t *)handle;
    if (song == NULL)
        return;
    DG_MusicPlay(song->midi, song->len, looping ? 1 : 0);
    music_playing = 1;
}

static void M_Stop(void)
{
    DG_MusicStop();
    music_playing = 0;
}

static boolean M_IsPlaying(void)
{
    return music_playing ? true : false;
}

static void M_Poll(void)
{
}

static snddevice_t music_devices[] =
{
    SNDDEVICE_PAS,
    SNDDEVICE_GUS,
    SNDDEVICE_WAVEBLASTER,
    SNDDEVICE_SOUNDCANVAS,
    SNDDEVICE_GENMIDI,
    SNDDEVICE_AWE32,
};

music_module_t DG_music_module =
{
    music_devices,
    sizeof(music_devices) / sizeof(music_devices[0]),
    M_Init,
    M_Shutdown,
    M_SetVolume,
    M_Pause,
    M_Resume,
    M_Register,
    M_UnRegister,
    M_Play,
    M_Stop,
    M_IsPlaying,
    M_Poll,
};
