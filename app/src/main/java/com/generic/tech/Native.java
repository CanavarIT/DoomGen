package com.generic.tech;

import android.view.Surface;

/** JNI bridge to libdoomgeneric.so */
public final class Native {
    static {
        System.loadLibrary("doomgeneric");
    }

    public interface Callbacks {
        void onQuit(String message, boolean error);
    }

    static volatile Callbacks callbacks;

    private Native() {}

    // ---- implemented in C ----
    public static native void run(String[] args, String workDir);   // never returns
    public static native void setSurface(Surface surface);           // null to detach
    public static native void key(int doomKey, boolean down);
    public static native void turn(int mouseUnits);
    public static native int mode();                                  // 0 = game, 1 = menu-like
    public static native void setPaused(boolean paused);

    // ---- called from C (engine thread) ----
    public static void onQuit(String message, boolean error) {
        Callbacks cb = callbacks;
        if (cb != null) cb.onQuit(message, error);
    }

    public static void musicPlay(byte[] midi, boolean loop) { Music.play(midi, loop); }
    public static void musicStop() { Music.stop(); }
    public static void musicPause(boolean paused) { Music.setGamePaused(paused); }
    public static void musicVolume(float v) { Music.setVolume(v); }
}
