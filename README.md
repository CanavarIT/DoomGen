<img width="2400" height="1080" alt="1000894302" src="https://github.com/user-attachments/assets/c918e03c-97ff-4437-b5d2-fbb10ddb3cc7" />

# DoomGeneric for Android

An Android port of [doomgeneric](https://github.com/ozkl/doomgeneric). The Doom engine stays in C and is built with the NDK. Java is only used for the window, touch controls, WAD discovery and music.

| | |
|---|---|
| Package | `com.generic.tech` |
| NDK | 29.0.14033849 |
| CMake | 3.31.6 |
| Shell language | Java (Java 8) |
| Build | Gradle (Groovy), AGP 8.5.2, Gradle 8.7 |
| minSdk / targetSdk | 29 / 34 |
| ABI | `arm64-v8a` |

No game data (WAD files) is included in the repository. You need to provide your own.

## Features

- Loads any file ending in `.wad`, whatever its name (`Game.wad`, `doom2.wad`, `freedoom1.wad`, ...).
- Looks for WADs in the app's `assets` and in `Android/data/com.generic.tech/files/`.
- Minimal touch controls: floating stick, swipe to turn, and turning with the finger that holds FIRE.
- Sound: effects through a custom AAudio mixer, music as MIDI through `MediaPlayer`.
- Savegame names are typed with the system keyboard.
- Works with an external keyboard too.

## Where to put the WAD

1. `Android/data/com.generic.tech/files/`
2. `app/src/main/assets/` or `app/src/main/assets/wads/`. These files are copied to the app's internal storage on first launch, because the engine works with plain files.

If a file with the same name exists in both places, the one in `Android/data/...` wins.

The file type is detected from its first four bytes, not from its name:

- `IWAD` is a main game. If there are several, a picker is shown at startup.
- `PWAD` is an add-on. All PWADs found are loaded automatically with `-file`. To disable one, rename its extension.
- Files without a WAD header are skipped.

If no IWAD is found, the screen shows a hint with the folder path.

The config (`default.cfg`) and savegames (`.savegame/`) are stored in `Android/data/com.generic.tech/files/`.

## Controls

| Action | How |
|---|---|
| Move | Stick in the left half of the screen. It appears where you first touch and stays there. It only moves the character: forward, back, strafe. Pushing far from the center makes you run. |
| Turn | Swipe left or right in the right half of the screen. |
| Aim | The finger holding FIRE can slide left and right to turn the view. |
| FIRE / USE | Two round buttons at the bottom right. |
| Weapons | The `‹` and `›` buttons above FIRE. |
| ESC, MAP, FIT | Three small chips at the top right. ESC opens the menu, MAP opens the automap, FIT switches between 4:3 and stretching to the full screen. |
| Back | The Back button works as ESC. |

Button labels depend on the screen:

- **In menus** the stick works as arrow keys, and FIRE and USE are labeled OK and act as Enter. This also confirms Y/N prompts.
- **On the level results screen, in finales and on the title screen** the OK buttons are real FIRE and USE. These are what skip the results and continue the game.
- **When saving**, picking a slot opens the system keyboard. Enter or OK saves the game, Cancel goes back to the menu. An empty name becomes `SAVE N`.

Turning speed is set by the `TURN_SENSITIVITY` constant in `TouchView.java`. A value of 1.0 means swiping across the full screen width turns 360°.

External keyboard: arrows, Enter, Esc, Tab, Space (USE), Ctrl (FIRE), Shift (run), letters and digits.

## How it works

```
DoomGeneric/
├── settings.gradle, build.gradle, gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── LICENSE_doomgeneric                      doomgeneric license (GPL)
└── app/
    ├── build.gradle
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/                          put *.wad here
        ├── res/                             theme, icon
        ├── java/com/generic/tech/
        │   ├── MainActivity.java            window, WAD discovery, engine start
        │   ├── Native.java                  JNI bridge
        │   ├── TouchView.java               touch controls
        │   └── Music.java                   MIDI music
        └── cpp/
            ├── CMakeLists.txt
            └── doomgeneric/                 engine sources + Android layer
                ├── doomgeneric_android.c    video, input, time, JNI
                └── i_androidsound.c         sound and music
```

### Startup

1. `MainActivity` scans for WADs on a background thread, copies them out of assets if needed and detects their type from the header.
2. One IWAD is chosen and the remaining PWADs are added to the launch arguments.
3. The engine runs on its own Java thread with a 16 MB stack. The thread calls `Native.run(args, workDir)`, which never returns. Arguments: `-iwad <path> -mb 32 [-file <pwad...>]`.
4. The JNI layer does a `chdir` into `Android/data/.../files` (the config and savegames use the current directory), then calls `doomgeneric_Create` followed by `doomgeneric_Tick` in an endless loop.

The engine cannot be restarted inside the same process because of its global state, so the whole process is killed when the game quits or fails with an error.

### Video

The engine renders into a 640×400 buffer (320×200 scaled 2x). A `SurfaceView` hands its `Surface` to native code. In `DG_DrawFrame` the window is locked with `ANativeWindow_lock`, pixels are converted from `0x00RRGGBB` to RGBA and the frame is posted. The FIT button controls the size of the view.

### Input

- Button and stick presses go through `Native.key(...)` into a ring queue, which the engine drains with `DG_GetKey`.
- Swipe turning is an accumulated delta. In `I_GetEvent` it is turned into a mouse event. In `g_game.c` the mouse delta is summed instead of overwritten, otherwise several events within one tic would be lost.
- `Native.mode()` reports the UI state: 0 is in game, 1 is menu, 2 is typing a savegame name, 3 is results, finale and title screen. `TouchView` uses it to change how the buttons behave.

### Sound

- **Effects.** Sound lumps are converted to 16-bit 44100 Hz with linear interpolation. A 16-channel mixer with panning runs in the AAudio stream callback. The stream is reopened after a route change (for example, headphones).
- **Music.** MUS is converted to MIDI with `mus2mid`, the bytes are passed to Java (`Music`), and `MediaPlayer` plays them from a temporary file. Pausing the game or minimizing the app pauses the music.

### Pause and quit

While the app is in the background, the engine blocks inside `DG_DrawFrame` and the game clock stops, so there is no fast-forward catch-up after you return. Quitting and fatal errors (`I_Quit`, `I_Error`) call the Java callback `onQuit`, which shows the message and ends the process.

## Changes to the doomgeneric sources

| File | Change |
|---|---|
| `i_input.c` | calls `DG_PollInput()` from `I_GetEvent` |
| `g_game.c` | mouse movement is accumulated (`+=`) |
| `m_menu.c` | Enter confirms Y/N prompts; an empty savegame name becomes `SAVE N` |
| `m_controls.c` | weapon switching defaults to PgUp/PgDn |
| `i_system.c` | `I_Quit` and `I_Error` hand control to Java (`DG_Quit`, `DG_Fatal`) |
| `i_sound.c` | dependency on SDL_mixer removed |

SDL is not used. All platform variants (SDL, X11, Windows and others) except Android were removed from the sources.

## Building

You need Android SDK 34, NDK 29.0.14033849 and CMake 3.31.6.

```
./gradlew assembleDebug
```

The APK ends up in `app/build/outputs/apk/debug/`. The project can be opened in Android Studio or AndroidIDE.

The C compiler flags in `CMakeLists.txt` matter:

- `-fsigned-char` is required: Doom assumes `char` is signed, while on ARM it is unsigned by default.
- `-std=gnu11` and the set of `-Wno-...` flags let the old code build with modern clang.
- `-Wl,-z,max-page-size=16384` is needed for devices with 16 KB memory pages.

## Limitations

- `arm64-v8a` only.
- Music plays on Android's built-in MIDI synthesizer, so the sound depends on the device.
- Network play is not supported.
- A PWAD that doesn't match the IWAD is rejected by the engine with an error message.

## License

The doomgeneric and Doom sources are released under the GNU GPL v2. The license text is in `LICENSE_doomgeneric`. Game data files (`doom.wad`, `doom2.wad` and others) are not part of the repository and belong to their owners. A free replacement is [Freedoom](https://freedoom.github.io/).
