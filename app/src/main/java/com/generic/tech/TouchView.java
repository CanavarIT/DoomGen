package com.generic.tech;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.SparseIntArray;
import android.view.MotionEvent;
import android.view.View;

/**
 * Minimal touch controls:
 *  - left half: floating stick (moves the character only: forward/back/strafe)
 *  - right half: swipe left/right anywhere to turn
 *  - FIRE / USE / weapon prev-next buttons, tiny ESC / MAP / FIT chips at the top right
 * In menus (and title / intermission) the stick sends arrow keys and FIRE/USE act as Enter.
 */
public class TouchView extends View {

    public interface Host {
        void toggleFit();
        void showSaveDialog();
    }

    // Doom key codes (doomkeys.h)
    static final int K_ESC = 27, K_ENTER = 13, K_TAB = 9;
    static final int K_RIGHT = 0xae, K_LEFT = 0xac, K_UP = 0xad, K_DOWN = 0xaf;
    static final int K_STRAFE_L = 0xa0, K_STRAFE_R = 0xa1, K_USE = 0xa2, K_FIRE = 0xa3;
    static final int K_SHIFT = 0x80 + 0x36, K_PGUP = 0x80 + 0x49, K_PGDN = 0x80 + 0x51;

    // Turning speed: 1.0 = swiping the full screen width turns 360 degrees
    private static final float TURN_SENSITIVITY = 1.0f;

    private static final int B_FIRE = 0, B_USE = 1, B_PREV = 2, B_NEXT = 3;
    private static final int C_ESC = 10, C_MAP = 11, C_FIT = 12;

    private final float d;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();

    private Host host;

    // layout
    private final float[] bx = new float[4], by = new float[4], br = new float[4];
    private final RectF[] chip = new RectF[3];
    private float stickR, idleX, idleY;

    // touch state
    private int stickId = -1, turnId = -1;
    private float stickCx, stickCy, stickX, stickY;
    private float turnLastX, turnRem;
    private final SparseIntArray owner = new SparseIntArray(); // pointerId -> button/chip id
    private final boolean[] held = new boolean[4];

    // key state for the stick (game mode)
    private boolean kUp, kDown, kSl, kSr, kRun;
    // menu mode pulses
    private int menuDir = 0;
    private long lastPulse;

    private int menuMode = 0;     // 1 = menu pad (arrows + Enter)
    private boolean okMode = false; // intermission / finale / title: OK = real FIRE/USE
    private int curMode = 0;
    private boolean saveShown = false;
    private float fireLastX;
    private float fireRem;

    private final Runnable modeTick = new Runnable() {
        @Override public void run() {
            int m = Native.mode();
            if (m != curMode) {
                releaseAll();
                curMode = m;
                menuMode = (m == 1 || m == 2) ? 1 : 0;
                okMode = (m == 3);
                invalidate();
            }
            if (m == 2) {
                if (!saveShown) {
                    saveShown = true;
                    releaseAll();
                    if (host != null) host.showSaveDialog();
                }
            } else {
                saveShown = false;
            }
            postDelayed(this, 120);
        }
    };

    private final Runnable stickTick = new Runnable() {
        @Override public void run() {
            if (stickId < 0) return;
            if (menuMode == 1) menuStick();
            postDelayed(this, 30);
        }
    };

    public TouchView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.6f * d);
        fill.setStyle(Paint.Style.FILL);
        text.setColor(0xFFFFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        for (int i = 0; i < chip.length; i++) chip[i] = new RectF();
        setFocusable(false);
    }

    public void setHost(Host h) {
        host = h;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(modeTick);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(modeTick);
        removeCallbacks(stickTick);
        super.onDetachedFromWindow();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        stickR = 56 * d;
        float fx = w - 66 * d, fy = h - 68 * d;
        bx[B_FIRE] = fx;               by[B_FIRE] = fy;               br[B_FIRE] = 36 * d;
        bx[B_USE] = fx - 82 * d;       by[B_USE] = fy + 22 * d;       br[B_USE] = 25 * d;
        bx[B_NEXT] = fx + 8 * d;       by[B_NEXT] = fy - 72 * d;      br[B_NEXT] = 17 * d;
        bx[B_PREV] = fx - 40 * d;      by[B_PREV] = fy - 66 * d;      br[B_PREV] = 17 * d;
        float cw = 42 * d, ch = 26 * d, gap = 6 * d, top = 8 * d;
        float right = w - 10 * d;
        for (int i = 2; i >= 0; i--) {
            chip[i].set(right - cw, top, right, top + ch);
            right -= cw + gap;
        }
        idleX = stickR + 34 * d;
        idleY = h - stickR - 26 * d;
    }

    // ------------------------------------------------------------ drawing

    @Override protected void onDraw(Canvas c) {
        boolean menu = menuMode == 1 || okMode;

        // stick
        if (stickId >= 0) {
            stroke.setColor(0x80FFFFFF);
            c.drawCircle(stickCx, stickCy, stickR, stroke);
            float dx = stickX - stickCx, dy = stickY - stickCy;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len > stickR) { dx = dx * stickR / len; dy = dy * stickR / len; }
            fill.setColor(0x66FFFFFF);
            c.drawCircle(stickCx + dx, stickCy + dy, 22 * d, fill);
        } else {
            stroke.setColor(0x2EFFFFFF);
            c.drawCircle(idleX, idleY, stickR, stroke);
            fill.setColor(0x1AFFFFFF);
            c.drawCircle(idleX, idleY, 20 * d, fill);
        }

        // buttons
        drawBtn(c, B_FIRE, menu ? "OK" : "FIRE", 12);
        drawBtn(c, B_USE, menu ? "OK" : "USE", 10);
        if (!menu) {
            drawBtn(c, B_PREV, "\u2039", 20);
            drawBtn(c, B_NEXT, "\u203A", 20);
        }

        // chips
        drawChip(c, C_ESC, "ESC");
        if (!menu) drawChip(c, C_MAP, "MAP");
        drawChip(c, C_FIT, "FIT");
    }

    private void drawBtn(Canvas c, int i, String label, float sp) {
        fill.setColor(held[i] ? 0x66FFFFFF : 0x22FFFFFF);
        stroke.setColor(0x80FFFFFF);
        c.drawCircle(bx[i], by[i], br[i], fill);
        c.drawCircle(bx[i], by[i], br[i], stroke);
        text.setTextSize(sp * d);
        text.setColor(0xB0FFFFFF);
        c.drawText(label, bx[i], by[i] + sp * d * 0.36f, text);
    }

    private void drawChip(Canvas c, int id, String label) {
        RectF r = chip[id - 10];
        fill.setColor(0x22FFFFFF);
        stroke.setColor(0x66FFFFFF);
        c.drawRoundRect(r, 8 * d, 8 * d, fill);
        c.drawRoundRect(r, 8 * d, 8 * d, stroke);
        text.setTextSize(10 * d);
        text.setColor(0xB0FFFFFF);
        c.drawText(label, r.centerX(), r.centerY() + 3.6f * d, text);
    }

    // ------------------------------------------------------------ touch

    @Override public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int idx = e.getActionIndex();
                pointerDown(e.getPointerId(idx), e.getX(idx), e.getY(idx));
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++)
                    pointerMove(e.getPointerId(i), e.getX(i), e.getY(i));
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int idx = e.getActionIndex();
                pointerUp(e.getPointerId(idx));
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                break;
        }
        invalidate();
        return true;
    }

    private int hit(float x, float y) {
        for (int i = 0; i < 3; i++) {
            RectF r = chip[i];
            rf.set(r.left - 6 * d, r.top - 6 * d, r.right + 6 * d, r.bottom + 6 * d);
            if (rf.contains(x, y)) return 10 + i;
        }
        for (int i = 0; i < 4; i++) {
            if ((menuMode == 1 || okMode) && (i == B_PREV || i == B_NEXT)) continue;
            float dx = x - bx[i], dy = y - by[i], rr = br[i] + 8 * d;
            if (dx * dx + dy * dy <= rr * rr) return i;
        }
        return -1;
    }

    private void pointerDown(int id, float x, float y) {
        int h = hit(x, y);
        if (h >= 10) {
            if (h == C_MAP && (menuMode == 1 || okMode)) return;
            owner.put(id, h);
            if (h == C_ESC) pulse(K_ESC);
            else if (h == C_MAP) pulse(K_TAB);
            else if (host != null) host.toggleFit();
            return;
        }
        if (h >= 0) {
            owner.put(id, h);
            held[h] = true;
            buttonPress(h, true);
            if (h == B_FIRE) {
                fireLastX = x;
                fireRem = 0;
            }
            return;
        }
        if (x < getWidth() * 0.5f) {
            if (stickId < 0) {
                stickId = id;
                stickCx = stickX = x;
                stickCy = stickY = y;
                menuDir = 0;
                removeCallbacks(stickTick);
                post(stickTick);
            }
        } else if (turnId < 0) {
            turnId = id;
            turnLastX = x;
            turnRem = 0;
        }
    }

    private void pointerMove(int id, float x, float y) {
        if (id == stickId) {
            stickX = x;
            stickY = y;
            // the stick base stays where the finger first touched
            if (menuMode == 0) gameStick();
        } else if (id == turnId) {
            float dx = x - turnLastX;
            turnLastX = x;
            if (menuMode == 0 && getWidth() > 0) {
                turnRem += dx / getWidth() * 8192f * TURN_SENSITIVITY;
                int u = (int) turnRem;
                if (u != 0) {
                    turnRem -= u;
                    Native.turn(u);
                }
            }
        } else if (owner.get(id, -1) == B_FIRE) {
            // finger holding FIRE can slide left/right to aim
            float dx = x - fireLastX;
            fireLastX = x;
            if (menuMode == 0 && getWidth() > 0) {
                fireRem += dx / getWidth() * 8192f * TURN_SENSITIVITY;
                int u = (int) fireRem;
                if (u != 0) {
                    fireRem -= u;
                    Native.turn(u);
                }
            }
        }
    }

    private void pointerUp(int id) {
        if (id == stickId) {
            stickId = -1;
            removeCallbacks(stickTick);
            setStickKeys(false, false, false, false, false);
            menuDir = 0;
        } else if (id == turnId) {
            turnId = -1;
        }
        int o = owner.get(id, -1);
        if (o >= 0) {
            owner.delete(id);
            if (o < 4) {
                held[o] = false;
                buttonPress(o, false);
            }
        }
    }

    private void releaseAll() {
        setStickKeys(false, false, false, false, false);
        for (int i = 0; i < 4; i++) {
            if (held[i]) {
                held[i] = false;
                buttonPress(i, false);
            }
        }
        owner.clear();
        stickId = -1;
        turnId = -1;
        menuDir = 0;
        removeCallbacks(stickTick);
    }

    // ------------------------------------------------------------ actions

    private void buttonPress(int b, boolean down) {
        if (menuMode == 1) {
            if (down && (b == B_FIRE || b == B_USE)) pulse(K_ENTER);
            return;
        }
        switch (b) {
            case B_FIRE: Native.key(K_FIRE, down); break;
            case B_USE:  Native.key(K_USE, down); break;
            case B_PREV: if (down) pulse(K_PGUP); break;
            case B_NEXT: if (down) pulse(K_PGDN); break;
        }
    }

    private void pulse(int key) {
        Native.key(key, true);
        Native.key(key, false);
    }

    private void gameStick() {
        float nx = (stickX - stickCx) / stickR;
        float ny = (stickY - stickCy) / stickR;
        float mag = (float) Math.sqrt(nx * nx + ny * ny);
        boolean up = ny < -0.30f, down = ny > 0.30f;
        boolean sl = nx < -0.45f, sr = nx > 0.45f;
        boolean run = mag > 0.60f && (up || down || sl || sr);
        setStickKeys(up, down, sl, sr, run);
    }

    private void setStickKeys(boolean up, boolean down, boolean sl, boolean sr, boolean run) {
        if (up != kUp) { kUp = up; Native.key(K_UP, up); }
        if (down != kDown) { kDown = down; Native.key(K_DOWN, down); }
        if (sl != kSl) { kSl = sl; Native.key(K_STRAFE_L, sl); }
        if (sr != kSr) { kSr = sr; Native.key(K_STRAFE_R, sr); }
        if (run != kRun) { kRun = run; Native.key(K_SHIFT, run); }
    }

    private void menuStick() {
        float nx = (stickX - stickCx) / stickR;
        float ny = (stickY - stickCy) / stickR;
        int dir = 0;
        if (Math.abs(nx) > 0.5f || Math.abs(ny) > 0.5f) {
            if (Math.abs(ny) >= Math.abs(nx)) dir = ny < 0 ? K_UP : K_DOWN;
            else dir = nx < 0 ? K_LEFT : K_RIGHT;
        }
        long now = SystemClock.uptimeMillis();
        if (dir != 0 && (dir != menuDir || now - lastPulse > 240)) {
            pulse(dir);
            lastPulse = now;
        }
        menuDir = dir;
    }

    /** Called by the activity for hardware BACK / ESC. */
    public void escape() {
        pulse(K_ESC);
    }
}
