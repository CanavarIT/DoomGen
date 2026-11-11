package com.generic.tech;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;

/** MIDI music (converted from MUS by the engine) played through MediaPlayer. */
final class Music {
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static Context ctx;
    private static MediaPlayer mp;
    private static float volume = 1f;
    private static boolean gamePaused, appPaused;

    private Music() {}

    static void init(Context c) {
        ctx = c.getApplicationContext();
    }

    static void play(final byte[] midi, final boolean loop) {
        H.post(new Runnable() {
            @Override public void run() {
                try {
                    release();
                    File f = new File(ctx.getCacheDir(), "music.mid");
                    FileOutputStream fo = new FileOutputStream(f);
                    fo.write(midi);
                    fo.close();
                    MediaPlayer p = new MediaPlayer();
                    p.setDataSource(f.getAbsolutePath());
                    p.setLooping(loop);
                    p.setVolume(volume, volume);
                    p.prepare();
                    mp = p;
                    updateState();
                } catch (Throwable t) {
                    release();
                }
            }
        });
    }

    static void stop() {
        H.post(new Runnable() { @Override public void run() { release(); } });
    }

    static void setVolume(final float v) {
        volume = v;
        H.post(new Runnable() {
            @Override public void run() {
                if (mp != null) {
                    try { mp.setVolume(volume, volume); } catch (Throwable ignored) {}
                }
            }
        });
    }

    static void setGamePaused(final boolean p) {
        H.post(new Runnable() { @Override public void run() { gamePaused = p; updateState(); } });
    }

    static void setAppPaused(final boolean p) {
        H.post(new Runnable() { @Override public void run() { appPaused = p; updateState(); } });
    }

    private static void updateState() {
        if (mp == null) return;
        try {
            boolean shouldPlay = !gamePaused && !appPaused;
            if (shouldPlay && !mp.isPlaying()) mp.start();
            else if (!shouldPlay && mp.isPlaying()) mp.pause();
        } catch (Throwable ignored) {}
    }

    private static void release() {
        if (mp != null) {
            try { mp.stop(); } catch (Throwable ignored) {}
            try { mp.release(); } catch (Throwable ignored) {}
            mp = null;
        }
    }
}
