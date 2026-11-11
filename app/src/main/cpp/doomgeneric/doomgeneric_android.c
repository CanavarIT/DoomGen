// doomgeneric platform layer for Android (ANativeWindow + JNI)
#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>

#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "doomgeneric.h"
#include "doomkeys.h"
#include "doomstat.h"
#include "d_event.h"

#define LOG_TAG "DoomGeneric"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------- state

static JavaVM *g_vm = NULL;
static JNIEnv *g_engine_env = NULL;      // env of the engine thread
static jclass g_native_class = NULL;     // global ref to com.generic.tech.Native
static jmethodID g_mid_quit = NULL;      // static void onQuit(String, boolean)
static jmethodID g_mid_mplay = NULL, g_mid_mstop = NULL, g_mid_mpause = NULL, g_mid_mvol = NULL;

static ANativeWindow *g_win = NULL;
static pthread_mutex_t g_win_mx = PTHREAD_MUTEX_INITIALIZER;

extern void DG_AudioPause(int paused);

static volatile int g_paused = 0;
static uint64_t g_time_offset_ms = 0;
static uint64_t g_time_start_ms = 0;

static atomic_int g_turn_accum = 0;

// ---------------------------------------------------------------- key queue

#define KEYQ_SIZE 256
static unsigned short g_keyq[KEYQ_SIZE];
static unsigned int g_keyq_w = 0, g_keyq_r = 0;
static pthread_mutex_t g_keyq_mx = PTHREAD_MUTEX_INITIALIZER;

static void push_key(int pressed, unsigned char key)
{
    pthread_mutex_lock(&g_keyq_mx);
    unsigned int next = (g_keyq_w + 1) % KEYQ_SIZE;
    if (next != g_keyq_r)
    {
        g_keyq[g_keyq_w] = (unsigned short)((pressed << 8) | key);
        g_keyq_w = next;
    }
    pthread_mutex_unlock(&g_keyq_mx);
}

int DG_GetKey(int *pressed, unsigned char *key)
{
    int ret = 0;
    pthread_mutex_lock(&g_keyq_mx);
    if (g_keyq_r != g_keyq_w)
    {
        unsigned short d = g_keyq[g_keyq_r];
        g_keyq_r = (g_keyq_r + 1) % KEYQ_SIZE;
        *pressed = d >> 8;
        *key = d & 0xFF;
        ret = 1;
    }
    pthread_mutex_unlock(&g_keyq_mx);
    return ret;
}

// Called from I_GetEvent (engine thread): turns accumulated swipe into a mouse event.
void DG_PollInput(void)
{
    int dx = atomic_exchange(&g_turn_accum, 0);
    if (dx == 0)
        return;
    if (menuactive || gamestate != GS_LEVEL)
        return;
    if (dx > 900) dx = 900;
    if (dx < -900) dx = -900;

    event_t ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = ev_mouse;
    ev.data1 = 0;
    ev.data2 = dx;
    ev.data3 = 0;
    D_PostEvent(&ev);
}

// ---------------------------------------------------------------- time

static uint64_t now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000ULL + (uint64_t)ts.tv_nsec / 1000000ULL;
}

uint32_t DG_GetTicksMs(void)
{
    return (uint32_t)(now_ms() - g_time_start_ms - g_time_offset_ms);
}

void DG_SleepMs(uint32_t ms)
{
    usleep(ms * 1000);
}

void DG_SetWindowTitle(const char *title)
{
    (void)title;
}

void DG_Init(void)
{
    g_time_start_ms = now_ms();
}

// ---------------------------------------------------------------- video

void DG_DrawFrame(void)
{
    if (g_paused)
    {
        uint64_t t0 = now_ms();
        while (g_paused)
            usleep(30000);
        g_time_offset_ms += now_ms() - t0;
    }

    pthread_mutex_lock(&g_win_mx);
    if (g_win != NULL)
    {
        ANativeWindow_Buffer buf;
        if (ANativeWindow_lock(g_win, &buf, NULL) == 0)
        {
            if (buf.bits != NULL && buf.format == WINDOW_FORMAT_RGBA_8888)
            {
                int w = buf.width < DOOMGENERIC_RESX ? buf.width : DOOMGENERIC_RESX;
                int h = buf.height < DOOMGENERIC_RESY ? buf.height : DOOMGENERIC_RESY;
                for (int y = 0; y < h; y++)
                {
                    const uint32_t *src = (const uint32_t *)DG_ScreenBuffer + y * DOOMGENERIC_RESX;
                    uint32_t *dst = (uint32_t *)buf.bits + y * buf.stride;
                    for (int x = 0; x < w; x++)
                    {
                        uint32_t p = src[x];  // 0x00RRGGBB
                        dst[x] = 0xFF000000u | ((p & 0xFF) << 16) | (p & 0xFF00) | ((p >> 16) & 0xFF);
                    }
                }
            }
            ANativeWindow_unlockAndPost(g_win);
        }
    }
    pthread_mutex_unlock(&g_win_mx);
}

// ---------------------------------------------------------------- quit / fatal

static void call_java_quit(const char *msg, int is_error)
{
    JNIEnv *env = g_engine_env;
    if (env != NULL && g_native_class != NULL && g_mid_quit != NULL)
    {
        jstring js = (*env)->NewStringUTF(env, msg ? msg : "");
        (*env)->CallStaticVoidMethod(env, g_native_class, g_mid_quit, js, (jboolean)is_error);
        (*env)->DeleteLocalRef(env, js);
    }
    // The engine cannot be restarted inside the same process: park here.
    for (;;)
        sleep(1);
}

void DG_Quit(void)
{
    call_java_quit("", 0);
}

void DG_Fatal(const char *msg)
{
    LOGI("FATAL: %s", msg);
    call_java_quit(msg, 1);
}

// ---------------------------------------------------------------- music bridge (engine thread only)

void DG_MusicPlay(const void *midi, int len, int loop)
{
    JNIEnv *env = g_engine_env;
    if (!env || !g_mid_mplay) return;
    jbyteArray arr = (*env)->NewByteArray(env, len);
    if (!arr) return;
    (*env)->SetByteArrayRegion(env, arr, 0, len, (const jbyte *)midi);
    (*env)->CallStaticVoidMethod(env, g_native_class, g_mid_mplay, arr, (jboolean)(loop ? 1 : 0));
    (*env)->DeleteLocalRef(env, arr);
}

void DG_MusicStop(void)
{
    JNIEnv *env = g_engine_env;
    if (!env || !g_mid_mstop) return;
    (*env)->CallStaticVoidMethod(env, g_native_class, g_mid_mstop);
}

void DG_MusicPause(int paused)
{
    JNIEnv *env = g_engine_env;
    if (!env || !g_mid_mpause) return;
    (*env)->CallStaticVoidMethod(env, g_native_class, g_mid_mpause, (jboolean)(paused ? 1 : 0));
}

void DG_MusicVolume(float v)
{
    JNIEnv *env = g_engine_env;
    if (!env || !g_mid_mvol) return;
    (*env)->CallStaticVoidMethod(env, g_native_class, g_mid_mvol, (jfloat)v);
}

// ---------------------------------------------------------------- JNI

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void)reserved;
    g_vm = vm;
    return JNI_VERSION_1_6;
}

// Native.run(String[] args, String workDir) -- blocks forever (engine thread)
JNIEXPORT void JNICALL
Java_com_generic_tech_Native_run(JNIEnv *env, jclass clazz, jobjectArray jargs, jstring jworkdir)
{
    g_engine_env = env;
    g_native_class = (jclass)(*env)->NewGlobalRef(env, clazz);
    g_mid_quit = (*env)->GetStaticMethodID(env, clazz, "onQuit", "(Ljava/lang/String;Z)V");
    g_mid_mplay = (*env)->GetStaticMethodID(env, clazz, "musicPlay", "([BZ)V");
    g_mid_mstop = (*env)->GetStaticMethodID(env, clazz, "musicStop", "()V");
    g_mid_mpause = (*env)->GetStaticMethodID(env, clazz, "musicPause", "(Z)V");
    g_mid_mvol = (*env)->GetStaticMethodID(env, clazz, "musicVolume", "(F)V");

    const char *wd = (*env)->GetStringUTFChars(env, jworkdir, NULL);
    if (wd != NULL)
    {
        if (chdir(wd) != 0)
            LOGI("chdir(%s) failed", wd);
        (*env)->ReleaseStringUTFChars(env, jworkdir, wd);
    }

    int argc = (*env)->GetArrayLength(env, jargs);
    char **argv = (char **)calloc((size_t)argc + 1, sizeof(char *));
    for (int i = 0; i < argc; i++)
    {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, jargs, i);
        const char *c = (*env)->GetStringUTFChars(env, s, NULL);
        argv[i] = strdup(c ? c : "");
        (*env)->ReleaseStringUTFChars(env, s, c);
        (*env)->DeleteLocalRef(env, s);
    }
    argv[argc] = NULL;

    doomgeneric_Create(argc, argv);
    for (;;)
        doomgeneric_Tick();
}

JNIEXPORT void JNICALL
Java_com_generic_tech_Native_setSurface(JNIEnv *env, jclass clazz, jobject surface)
{
    (void)clazz;
    pthread_mutex_lock(&g_win_mx);
    if (g_win != NULL)
    {
        ANativeWindow_release(g_win);
        g_win = NULL;
    }
    if (surface != NULL)
    {
        g_win = ANativeWindow_fromSurface(env, surface);
        if (g_win != NULL)
            ANativeWindow_setBuffersGeometry(g_win, DOOMGENERIC_RESX, DOOMGENERIC_RESY,
                                             WINDOW_FORMAT_RGBA_8888);
    }
    pthread_mutex_unlock(&g_win_mx);
}

JNIEXPORT void JNICALL
Java_com_generic_tech_Native_key(JNIEnv *env, jclass clazz, jint key, jboolean down)
{
    (void)env; (void)clazz;
    push_key(down ? 1 : 0, (unsigned char)key);
}

JNIEXPORT void JNICALL
Java_com_generic_tech_Native_turn(JNIEnv *env, jclass clazz, jint dx)
{
    (void)env; (void)clazz;
    atomic_fetch_add(&g_turn_accum, dx);
}

extern int saveStringEnter;

// 0 = in game, 1 = menu open (UI behaves like a menu pad: arrows + Enter),
// 2 = typing a savegame name (Java shows the system keyboard),
// 3 = intermission / finale / title (FIRE/USE must be real game buttons to skip/continue)
JNIEXPORT jint JNICALL
Java_com_generic_tech_Native_mode(JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    if (saveStringEnter)
        return 2;
    if (menuactive)
        return 1;
    return (gamestate != GS_LEVEL) ? 3 : 0;
}

JNIEXPORT void JNICALL
Java_com_generic_tech_Native_setPaused(JNIEnv *env, jclass clazz, jboolean paused)
{
    (void)env; (void)clazz;
    g_paused = paused ? 1 : 0;
    DG_AudioPause(g_paused);
}
