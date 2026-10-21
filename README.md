# Bubble Manager

**Android 17-Style Floating App Bubbles for Android 10+ (Powered by Shizuku)**

Bubble Manager brings real application bubbles to Android 10 through 16+. Each bubble runs an authentic third-party app in a private virtual display inside a custom overlay window, with full touch and back navigation forwarded via Shizuku shell input injection.

The APK is pre-built and available in the [`release/`](./release) folder:
- **Direct Download:** [`release/bubble-manager.apk`](./release/bubble-manager.apk)

---

## How It Works

Bubble Manager implements two windowing modes with automatic capability detection:

### 1. Primary Engine: Embedded Bubbles (Framework-Level Experience)
- **Private VirtualDisplay**: Each bubble creates an isolated `VirtualDisplay` via Android's public `DisplayManager` API.
- **In-Window Rendering**: The target application's pixels render directly into a `SurfaceView` inside our own floating overlay window (`TYPE_APPLICATION_OVERLAY`).
- **Complete Frame Control**: Bubble Manager owns the title bar, dragging behavior, back button, minimize button, and close button—independent of OEM skins.
- **Privileged Input Injection**: Touch events (`ACTION_DOWN`, `ACTION_MOVE`, `ACTION_UP`, `ACTION_CANCEL`) and hardware keys (`KEYCODE_BACK`) are forwarded to the virtual display via `IShellService` running with shell privileges (UID 2000) using Android's internal `IInputManager.injectInputEvent`.
- **True Minimization**: Minimizing detaches the surface while keeping the app alive in memory. Tapping the bubble reattaches the surface immediately.
- **Clean Dismissal**: Dropping the bubble on the "X" zone stops the app process (`am force-stop`) and releases the virtual display.

### 2. Fallback Engine: System Freeform Window
- If the device or ROM lacks secondary display support (`android.software.activities_on_secondary_displays`) or launching on the virtual display fails, Bubble Manager seamlessly falls back to AOSP system freeform mode (`am start --windowingMode 5`).
- Automatic window resizing and off-screen task collapse via `am task resize`.

---

## Features

- **App Bubble Cap (Max 3)**: Manages up to 3 active floating bubbles simultaneously. Adding a 4th bubble evicts the oldest and pushes it to Recents.
- **Edge-Docked Bubble Stack**: Minimized bubbles dock smoothly along the screen edge with active/open state highlights and smooth drag physics.
- **Drag-to-X to Close**: Dragging any bubble to the glowing red "X" drop-zone at the bottom dismisses the bubble and completely force-stops the app.
- **Recent Bubbles (`+` Chip)**: An expandable `+` chip on the edge opens a Material 3 popover menu displaying recent bubbles with 1-tap restore.
- **Session Persistence**: Active and recent bubble lists are saved to storage (`SharedPreferences`) and restored on service start.
- **Pure Material 3 Design**: Teal tonal palette (light and dark modes), M3 filled and tonal buttons, elevated cards, and rounded shapes.
- **Real-Time Diagnostics Console**: Live diagnostics logger showing active mode (`Mode: embedded bubbles` or `Mode: freeform fallback`), binder connection status, display IDs, and input events, complete with a 1-tap "Copy" button.

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
1. Android 10 (API 29) or higher.
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
   Tap the "Draw over other apps" status row in the dashboard to grant `SYSTEM_ALERT_WINDOW` permission in Android Settings.

5. **Start Service & Add Bubbles**:
   - Tap **Start Service**. The floating collapsed bubble stack will appear docked to your screen edge.
   - Tap **Add App** to search and select any installed application from the bottom sheet app picker.
   - Tap the bubble icon on the screen edge to expand the embedded floating window!

---

## Verification & Testing Guide

When testing on a physical device:
1. Ensure Shizuku is running and Bubble Manager is authorized.
2. Ensure "Draw over other apps" permission is granted.
3. Tap **Start Service**.
4. Tap **Add App** and choose any app (e.g., Calculator or Settings).
5. **Expected result**: A floating window appears with a Material title bar, icon, title, back, minimize, and close buttons, running the app inside a `SurfaceView`.
6. Tap inside the app to interact (scrolling, tapping).
7. Tap the **Back button** (`<`) in the title bar to trigger key back navigation.
8. Tap the **Minimize button** (`-`) or tap the docked bubble to collapse the window.
9. Drag the bubble down to the red **X zone** to dismiss and terminate the application.
10. Check the **Diagnostics** card in the main dashboard:
    - First line confirms: `Mode: embedded bubbles` (or `Mode: freeform fallback`).

---

## Project Structure

```
app/
├── src/main/
│   ├── aidl/com/multitask/bubble/core/
│   │   └── IShellService.aidl            # Privileged IPC interface (exec, injectMotion, injectKey)
│   ├── java/com/multitask/bubble/
│   │   ├── MainActivity.kt               # Setup dashboard, permission toggles & diagnostics log
│   │   ├── core/
│   │   │   ├── BubbleManager.kt          # Pure state machine for bubble cap, recents & persistence
│   │   │   ├── DiagLog.kt                # Shared in-memory and logcat diagnostic stream
│   │   │   ├── ShellService.kt           # Privileged shell service running under Shizuku UID 2000
│   │   │   ├── ShizukuWindowBackend.kt   # Shell connection & command/input routing
│   │   │   └── WindowBackend.kt          # Window manager abstraction interface
│   │   ├── service/
│   │   │   └── BubbleOverlayService.kt   # Foreground overlay service, edge dock & touch gestures
│   │   └── ui/
│   │       ├── AppPickerSheet.kt         # Searchable installed application picker
│   │       ├── BubbleWindow.kt           # Window contract (show, hide, release)
│   │       ├── EmbeddedWindow.kt         # VirtualDisplay + SurfaceView floating window
│   │       └── FreeformWindow.kt         # AOSP freeform window fallback
│   └── res/
│       ├── layout/                       # Material 3 view layouts (activities, windows, cards)
│       └── values/                       # Material 3 teal tonal color palette and themes
└── src/test/java/com/multitask/bubble/
    └── BubbleManagerTest.kt              # Robolectric tests for bubble cap, eviction & recents
```

---

## Building from Source

```bash
# Build debug APK
gradle :app:assembleDebug

# Run Robolectric unit tests
gradle :app:testDebugUnitTest
```

---

## License

Apache License 2.0. Uses the official [Shizuku API](https://github.com/RikkaApps/Shizuku).
