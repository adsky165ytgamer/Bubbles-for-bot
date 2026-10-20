# Bubble Manager

**Universal Floating App Bubbles for Android 10+ (Powered by Shizuku)**

Bubble Manager brings Android 17-style native app bubbles to Android 10 through 16+. Each bubble is an authentic third-party app running in a native AOSP freeform window (`WINDOWING_MODE_FREEFORM = 5`).

The APK is pre-built and available in the [`release/`](./release) folder:
- **Direct Download:** [`release/bubble-manager.apk`](./release/bubble-manager.apk)

---

## What It Does

Ordinary apps cannot launch other applications into floating windows due to Android's process sandboxing. Bubble Manager solves this by executing launch commands through a **Shizuku UserService running with shell privileges (UID 2000)**.

- **Real Freeform Windows**: Target apps run in true native floating windows rather than simulated mock views.
- **Bubble Cap (Max 3)**: Manages up to 3 active floating bubbles at once. Opening a 4th bubble automatically evicts and closes the oldest.
- **Collapsed Edge Stack**: When minimized, active bubbles dock neatly along the screen edge as an icon stack. Tapping expands the window and brings it to the front.
- **Drag-to-X to Close**: Dragging any bubble to the glowing red "X" drop-zone at the bottom dismisses the bubble and completely kills the app process (`am force-stop`).
- **Recents (+) Chip**: An expandable `+` chip on the edge opens a popup list of recently closed bubbles for instant 1-tap reopen.
- **Session Persistence**: Active and recent bubble lists are saved to storage (`SharedPreferences`) and restored on service start.
- **Material 3 Design**: Built with Google Material 3 Expressive guidelines featuring a teal tonal palette, dark/light theme tokens, and rounded card surfaces.
- **Live Diagnostics Console**: Integrated logcat viewer (tag: `ShizukuBackend`) with a 1-tap "Copy" button for troubleshooting on different OEM ROMs.

---

## Release APK

The compiled debug-signed APK is ready to install:

```
release/
└── bubble-manager.apk
```

To install directly via ADB:
```bash
adb install -r release/bubble-manager.apk
```

---

## Setup & Getting Started

### Prerequisites
1. An Android device running **Android 10 (API 29)** or newer.
2. The **[Shizuku](https://shizuku.rikka.app/)** app installed.

### Step-by-Step Instructions

1. **Install Bubble Manager**:
   Install `release/bubble-manager.apk` on your device.

2. **Start Shizuku**:
   - **Android 11+ (Wireless Debugging)**: Open Shizuku, pair via Developer Options -> Wireless Debugging, and start the service on-device.
   - **Android 10 or PC**: Connect your phone via USB and run:
     ```bash
     adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
     ```

3. **Authorize Bubble Manager in Shizuku**:
   Open Bubble Manager. When prompted, grant Shizuku permission (or toggle ON inside Shizuku's "Authorized Applications" list).

4. **Grant Overlay Permission**:
   Tap the "Draw over other apps" status row to grant `SYSTEM_ALERT_WINDOW` permission in Android Settings.

5. **Start Service & Add Bubbles**:
   - Tap **Start Service**. The floating collapsed bubble stack will appear docked to your screen edge.
   - Tap **Add App** to search and select any installed application.
   - Tap the bubble icon on the screen edge to expand the app in a freeform floating window!

---

## Technical Architecture

```
┌────────────────────────────────────────────────────────┐
│                   Bubble Manager UI                    │
│   (MainActivity, AppPickerSheet, BubbleOverlayService) │
└───────────────────────────┬────────────────────────────┘
                            │ IPC (IShellService.aidl)
                            ▼
┌────────────────────────────────────────────────────────┐
│             Shizuku UserService (ShellService)         │
│          Runs inside Shizuku process (UID 2000)        │
└───────────────────────────┬────────────────────────────┘
                            │ Privileged Shell Execution
                            ▼
┌────────────────────────────────────────────────────────┐
│              Android OS (Activity Manager)             │
│   • am start -n <component> --windowingMode 5          │
│   • am task resize <taskId> <bounds>                   │
│   • am force-stop <package>                            │
│   • settings put global enable_freeform_support 1      │
│   • settings put global force_resizable_activities 1   │
└────────────────────────────────────────────────────────┘
```

### Key Components

- **`IShellService.aidl` & `ShellService.kt`**: Bound via `Shizuku.bindUserService`. Runs in a standalone process under UID 2000, allowing shell commands to bypass app-level sandboxing.
- **`ShizukuWindowBackend.kt`**: Manages the connection to the shell service, polls `dumpsys activity recents` to resolve task IDs, and executes task moves and force-stops.
- **`BubbleManager.kt`**: Pure state machine managing active bubbles, cap limits, task states, recents history, and storage persistence.
- **`BubbleOverlayService.kt`**: Foreground overlay service rendering edge-docked bubbles, drag-and-drop gestures, the dismiss target, and the recents popup menu.

---

## Troubleshooting & OEM ROM Notes

- **Freeform Window Doesn't Open**:
  Some strict OEM skins (MIUI/HyperOS, ColorOS, FunTouch OS) have proprietary freeform window implementations. If a window launches in fullscreen instead of floating:
  1. Open Developer Options and ensure **"Enable freeform windows"** or **"Force activities to be resizable"** is toggled ON.
  2. Reboot the phone (some ROMs require a reboot after global settings change).
  3. Tap **Copy Logcat** in Bubble Manager's Diagnostics section to inspect the exact output from `am start`.
- **Task ID Resolution**:
  The task ID is parsed from `dumpsys activity recents`. If an OEM ROM formats the dumpsys output in an unusual way, `launch` will still open the app, but task collapse and off-screen hide may be skipped.

---

## Building from Source

```bash
# Build debug APK
gradle :app:assembleDebug

# Run unit and Robolectric tests
gradle :app:testDebugUnitTest
```

---

## License

Apache License 2.0. Uses the official [Shizuku API](https://github.com/RikkaApps/Shizuku).
