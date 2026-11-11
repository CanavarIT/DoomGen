package com.generic.tech;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.text.InputFilter;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity implements Native.Callbacks, TouchView.Host {

    /** The engine can only be started once per process. */
    private static boolean engineStarted = false;

    private static final class Wad {
        String name;
        File file;
        boolean iwad;
        long size;
    }

    private Root root;
    private SurfaceView surface;
    private TouchView touch;
    private TextView status;
    private boolean fit = true;           // true: 4:3 like original Doom, false: stretch to screen
    private final Handler ui = new Handler(Looper.getMainLooper());

    // ------------------------------------------------------------ lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(lp);

        SharedPreferences sp = getPreferences(MODE_PRIVATE);
        fit = sp.getBoolean("fit", true);

        Music.init(this);
        Native.callbacks = this;

        surface = new SurfaceView(this);
        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { Native.setSurface(h.getSurface()); }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { Native.setSurface(h.getSurface()); }
            @Override public void surfaceDestroyed(SurfaceHolder h) { Native.setSurface(null); }
        });

        touch = new TouchView(this);
        touch.setHost(this);

        status = new TextView(this);
        status.setTextColor(Color.argb(160, 255, 255, 255));
        status.setTextSize(13);
        status.setGravity(android.view.Gravity.CENTER);
        status.setText("Loading\u2026");

        root = new Root(this);
        root.setBackgroundColor(Color.BLACK);
        root.addView(surface);
        root.addView(status);
        root.addView(touch);
        setContentView(root);

        immersive();

        if (!engineStarted) {
            engineStarted = true;
            new Thread(new Runnable() {
                @Override public void run() {
                    final List<Wad> wads = scanWads();
                    runOnUiThread(new Runnable() {
                        @Override public void run() { chooseAndStart(wads); }
                    });
                }
            }, "wad-scan").start();
        } else {
            status.setVisibility(View.GONE);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        immersive();
        Native.setPaused(false);
        Music.setAppPaused(false);
    }

    @Override protected void onPause() {
        Native.setPaused(true);
        Music.setAppPaused(true);
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    @Override protected void onDestroy() {
        if (isFinishing()) Process.killProcess(Process.myPid());
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // ------------------------------------------------------------ layout

    @Override public void toggleFit() {
        fit = !fit;
        getPreferences(MODE_PRIVATE).edit().putBoolean("fit", fit).apply();
        root.requestLayout();
    }

    // ------------------------------------------------------------ savegame name

    @Override public void showSaveDialog() {
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setHint("Save name");
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(23)});
        et.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        et.setPadding(48, 32, 48, 32);

        final boolean[] done = {false};
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Save game")
                .setView(et)
                .setPositiveButton("OK", null)
                .setNegativeButton("Cancel", null)
                .create();
        dlg.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override public void onShow(android.content.DialogInterface d) {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (done[0]) return;
                        done[0] = true;
                        sendSaveName(et.getText().toString());
                        dlg.dismiss();
                    }
                });
            }
        });
        et.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent e) {
                if (done[0]) return true;
                done[0] = true;
                sendSaveName(et.getText().toString());
                dlg.dismiss();
                return true;
            }
        });
        dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface d) {
                if (!done[0]) {           // cancelled: leave the name prompt
                    done[0] = true;
                    touch.escape();
                }
                immersive();
            }
        });
        dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dlg.show();
        et.requestFocus();
    }

    /** Feeds the typed name to the engine as key presses, then Enter. */
    private void sendSaveName(String name) {
        for (int i = 0; i < 24; i++) pulseKey(0x7f);          // clear existing text (backspace)
        for (int i = 0; i < name.length() && i < 23; i++) {
            char c = name.charAt(i);
            if (c < 32 || c > 126) continue;
            pulseKey(Character.toLowerCase(c));
        }
        pulseKey(TouchView.K_ENTER);
    }

    private static void pulseKey(int k) {
        Native.key(k, true);
        Native.key(k, false);
    }

    /** Lays the game surface out at 4:3 (or full screen) and the touch overlay over everything. */
    private final class Root extends FrameLayout {
        Root(Context c) { super(c); }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int w = r - l, h = b - t;
            int gw = w, gh = h;
            if (fit) {
                if (w * 3 > h * 4) { gw = h * 4 / 3; gh = h; }
                else { gw = w; gh = w * 3 / 4; }
            }
            int x = (w - gw) / 2, y = (h - gh) / 2;
            surface.layout(x, y, x + gw, y + gh);
            status.layout(0, 0, w, h);
            touch.layout(0, 0, w, h);
        }
    }

    // ------------------------------------------------------------ input (hardware)

    @Override public boolean onKeyDown(int keyCode, KeyEvent ev) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (ev.getRepeatCount() == 0) touch.escape();
            return true;
        }
        int k = mapKey(keyCode);
        if (k != 0) {
            if (ev.getRepeatCount() == 0) Native.key(k, true);
            return true;
        }
        return super.onKeyDown(keyCode, ev);
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent ev) {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true;
        int k = mapKey(keyCode);
        if (k != 0) {
            Native.key(k, false);
            return true;
        }
        return super.onKeyUp(keyCode, ev);
    }

    private static int mapKey(int kc) {
        if (kc >= KeyEvent.KEYCODE_A && kc <= KeyEvent.KEYCODE_Z) return 'a' + (kc - KeyEvent.KEYCODE_A);
        if (kc >= KeyEvent.KEYCODE_0 && kc <= KeyEvent.KEYCODE_9) return '0' + (kc - KeyEvent.KEYCODE_0);
        switch (kc) {
            case KeyEvent.KEYCODE_DPAD_UP: return TouchView.K_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return TouchView.K_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return TouchView.K_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return TouchView.K_RIGHT;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER: return TouchView.K_ENTER;
            case KeyEvent.KEYCODE_ESCAPE: return TouchView.K_ESC;
            case KeyEvent.KEYCODE_TAB: return TouchView.K_TAB;
            case KeyEvent.KEYCODE_SPACE: return TouchView.K_USE;
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT: return TouchView.K_FIRE;
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return TouchView.K_SHIFT;
            case KeyEvent.KEYCODE_COMMA: return ',';
            case KeyEvent.KEYCODE_PERIOD: return '.';
            case KeyEvent.KEYCODE_MINUS: return '-';
            case KeyEvent.KEYCODE_EQUALS: return '=';
            case KeyEvent.KEYCODE_PAGE_UP: return TouchView.K_PGUP;
            case KeyEvent.KEYCODE_PAGE_DOWN: return TouchView.K_PGDN;
            case KeyEvent.KEYCODE_DEL: return 0x7f;
        }
        return 0;
    }

    // ------------------------------------------------------------ engine callbacks

    @Override public void onQuit(final String message, final boolean error) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (error) {
                    String m = message == null ? "" : message.trim();
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Doom")
                            .setMessage(m.isEmpty() ? "Unknown error" : m)
                            .setCancelable(false)
                            .setPositiveButton("OK", new android.content.DialogInterface.OnClickListener() {
                                @Override public void onClick(android.content.DialogInterface d, int w) { quitProcess(); }
                            })
                            .show();
                } else {
                    quitProcess();
                }
            }
        });
    }

    private void quitProcess() {
        finishAndRemoveTask();
        ui.postDelayed(new Runnable() {
            @Override public void run() { Process.killProcess(Process.myPid()); }
        }, 250);
    }

    // ------------------------------------------------------------ WAD discovery

    private File externalDir() {
        File ext = getExternalFilesDir(null);
        if (ext == null) ext = getFilesDir();
        //noinspection ResultOfMethodCallIgnored
        ext.mkdirs();
        return ext;
    }

    private static boolean isWadName(String n) {
        return n.toLowerCase(Locale.ROOT).endsWith(".wad");
    }

    /** Reads the 4 byte header: "IWAD" / "PWAD". Returns 1 = IWAD, 2 = PWAD, 0 = not a WAD. */
    private static int wadKind(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] h = new byte[4];
            if (in.read(h) != 4) return 0;
            String s = new String(h, "US-ASCII");
            if (s.equals("IWAD")) return 1;
            if (s.equals("PWAD")) return 2;
        } catch (IOException ignored) {
        }
        return 0;
    }

    private List<Wad> scanWads() {
        Map<String, Wad> map = new LinkedHashMap<>();

        // 1) Android/data/<package>/files  (wins over assets)
        File ext = externalDir();
        File[] files = ext.listFiles();
        if (files != null) {
            java.util.Arrays.sort(files);
            for (File f : files) {
                if (f.isFile() && isWadName(f.getName())) addWad(map, f);
            }
        }

        // 2) assets (root and assets/wads), copied out because the engine uses plain files
        File cache = new File(getFilesDir(), "wads");
        //noinspection ResultOfMethodCallIgnored
        cache.mkdirs();
        for (String dir : new String[]{"", "wads"}) {
            String[] list;
            try {
                list = getAssets().list(dir);
            } catch (IOException e) {
                continue;
            }
            if (list == null) continue;
            java.util.Arrays.sort(list);
            for (String name : list) {
                if (!isWadName(name)) continue;
                if (map.containsKey(name.toLowerCase(Locale.ROOT))) continue;
                String path = dir.isEmpty() ? name : dir + "/" + name;
                File dst = new File(cache, name);
                try {
                    copyAsset(path, dst);
                    addWad(map, dst);
                } catch (IOException ignored) {
                }
            }
        }
        return new ArrayList<>(map.values());
    }

    private void addWad(Map<String, Wad> map, File f) {
        int kind = wadKind(f);
        if (kind == 0) return;
        Wad w = new Wad();
        w.name = f.getName();
        w.file = f;
        w.iwad = kind == 1;
        w.size = f.length();
        map.put(w.name.toLowerCase(Locale.ROOT), w);
    }

    private void copyAsset(String path, File dst) throws IOException {
        long assetLen = -1;
        try (AssetFileDescriptor afd = getAssets().openFd(path)) {
            assetLen = afd.getLength();
        } catch (IOException ignored) {
            // compressed asset: size unknown, only copy when missing
        }
        if (dst.exists() && (assetLen < 0 || dst.length() == assetLen)) return;

        File tmp = new File(dst.getParentFile(), dst.getName() + ".tmp");
        try (InputStream in = getAssets().open(path); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        //noinspection ResultOfMethodCallIgnored
        dst.delete();
        if (!tmp.renameTo(dst)) throw new IOException("rename failed");
    }

    private void chooseAndStart(final List<Wad> wads) {
        final List<Wad> iwads = new ArrayList<>();
        for (Wad w : wads) if (w.iwad) iwads.add(w);

        if (iwads.isEmpty()) {
            status.setText("No IWAD found.\n\nPut a *.wad file (doom.wad, doom2.wad, freedoom, Game.wad ...) into:\n"
                    + externalDir().getAbsolutePath()
                    + "\n\nor into app/src/main/assets/ before building,\nthen restart the app.");
            engineStarted = false;
            return;
        }
        if (iwads.size() == 1) {
            startEngine(iwads.get(0), wads);
            return;
        }

        String[] items = new String[iwads.size()];
        for (int i = 0; i < items.length; i++) {
            Wad w = iwads.get(i);
            items[i] = w.name + "  (" + (w.size / (1024 * 1024)) + " MB)";
        }
        new AlertDialog.Builder(this)
                .setTitle("Select game")
                .setCancelable(false)
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        startEngine(iwads.get(which), wads);
                    }
                })
                .show();
    }

    private void startEngine(Wad iwad, List<Wad> all) {
        status.setVisibility(View.GONE);

        List<String> args = new ArrayList<>();
        args.add("doomgeneric");
        args.add("-iwad");
        args.add(iwad.file.getAbsolutePath());
        args.add("-mb");
        args.add("32");
        List<String> pwads = new ArrayList<>();
        for (Wad w : all) if (!w.iwad) pwads.add(w.file.getAbsolutePath());
        if (!pwads.isEmpty()) {
            args.add("-file");
            args.addAll(pwads);
        }

        final String[] argv = args.toArray(new String[0]);
        final String workDir = externalDir().getAbsolutePath();

        Thread t = new Thread(null, new Runnable() {
            @Override public void run() { Native.run(argv, workDir); }
        }, "doom-engine", 16L * 1024 * 1024);
        t.start();
    }
}
