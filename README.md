# Bubble Manager

**Android 17-Style Floating App Bubbles for Android 10+ (Powered by Shizuku) — Polish Version**

Bubble Manager brings real application bubbles to Android 10 through 16+. Each bubble runs an authentic third-party app in a private virtual display inside a custom overlay window, with full multi-touch gesture forwarding (pinch-to-zoom, double-tap, flings) via Shizuku shell input injection, an arrange/tiling mode, and a persistent draggable app drawer chip.

The APK is pre-built and available in the [`release/`](./release) folder:
- **Direct Download:** [`release/bubble-manager.apk`](./release/bubble-manager.apk)

---

## How It Works

Bubble Manager implements two windowing modes with automatic capability detection:

### 1. Primary Engine: Embedded Bubbles (Framework-Level Experience)
- **Private VirtualDisplay**: Each bubble creates an isolated `VirtualDisplay` via Android's public `DisplayManager` API (`VIRTUAL_DISPLAY_FLAG_PUBLIC | VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY`).
- **In-Window Rendering**: The target application's pixels render directly into a `SurfaceView` inside our own floating overlay window (`TYPE_APPLICATION_OVERLAY`).
- **Complete Frame Control**: Bubble Manager owns the title bar, dragging behavior, back button, minimize button, and close button—independent of OEM skins.
- **Multi-Pointer Input Injection**: Touch events are forwarded to the virtual display via `IShellService.injectTouch` running under Shizuku shell UID 2000. All pointer IDs, coordinates, and pressure values are preserved, enabling:
  - **Pinch-to-zoom** (in maps, browsers, photos).
  - **Double-tap to zoom**.
  - **Flinging & continuous scrolling**.
  - **Back navigation** (`KEYCODE_BACK`).
- **True Minimization**: Minimizing detaches the surface while keeping the app alive in memory. Tapping the bubble reattaches the surface immediately.
- **Clean Dismissal**: Dropping the bubble on the "X" zone stops the app process (`am force-stop`) and releases the virtual display.

### 2. Fallback Engine: System Freeform Window
- If the device or ROM lacks secondary display support (`android.software.activities_on_secondary_displays`) or launching on the virtual display fails, Bubble Manager seamlessly falls back to AOSP system freeform mode (`am start --windowingMode 5`).
- Automatic window resizing and off-screen task collapse via `am task resize`.

---

## What's New in the Polish Version

- **Multi-Pointer Touch & Gestures**:
  - Full support for multi-touch gestures directly handled by the embedded application: pinch-to-zoom, multi-finger taps, and smooth inertial flings.
- **Arrange Mode & Auto-Tiling**:
  - **Long-press the grid chip**: haptic feedback triggers, the chip turns solid teal (`bg_chip_active.xml`), and all open windows smoothly animate into a neat, responsive grid based on bubble order:
    - **1 window**: Fills the main workspace.
    - **2 windows**: Stacks vertically (in portrait) or sits side-by-side (in landscape).
    - **3 windows**: Two windows on top and one full-width window below.
  - **Interactive Slot Snapping**: Dragging a window by its title bar in arrange mode automatically snaps it to the nearest slot and reorders the other windows.
  - **Long-press again** to toggle off arrange mode.
- **Draggable App Drawer Chip**:
  - The grid icon chip (`ic_apps.xml`) can be dragged anywhere on the screen edge and saves its coordinates across app restarts (`bubble_positions` SharedPreferences).
  - Tapping the chip opens an elevated overlay app drawer with search and recently used apps pinned at the top.
  - Supports soft keyboard input within the search box.
- **Smooth Window Animations**:
  - Decelerate and overshoot entrance/exit animations and fluid repositioning transitions via `ValueAnimator`.
  - Window focus raising on title-bar tap.

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

5. **Start Service & Use Bubbles**:
   - Tap **Start Service**. The floating collapsed bubble stack and the grid chip appear docked on the screen edge.
   - Tap the **Grid Chip** to open the app drawer (recents first, with instant search) or tap **Add App** from the dashboard.
   - Pick an app: a floating Material 3 window appears with the app running inside!

---

## Verification & Device Testing Flow

1. **Start**: Verify Shizuku running and overlay permission granted. Tap **Start Service**.
2. **App Drawer**: Tap the floating grid chip. Search for an app (e.g., Maps, Calculator, Browser) and select it.
3. **Gestures**: Test single tap, smooth scrolling, double-tap zoom, pinch-to-zoom (in Google Maps / Photos), and the Back button (`<`) in the title bar.
4. **Arrange Mode**:
   - Open 2 or 3 apps.
   - **Long-press the grid chip**: Notice the haptic buzz and the chip turning solid teal. Windows animate into a neat tiled grid.
   - Drag any window by its title bar and release: it snaps to the nearest slot and the others reorder.
   - Long-press the chip again to leave arrange mode.
5. **Minimize & Close**:
   - Tap the minimize button (`-`) or tap the docked bubble: window hides while keeping the app alive.
   - Tap the bubble again: window returns immediately.
   - Drag a bubble down to the red **X zone**: app is force-stopped and the bubble closes.
6. **Diagnostics**:
   - Check the Diagnostics card in the main dashboard:
     - Confirms `Mode: embedded bubbles` (or `Mode: freeform fallback`).

---

## Project Structure

```
app/
├── src/main/
│   ├── aidl/com/multitask/bubble/core/
│   │   └── IShellService.aidl            # Privileged IPC (exec, injectTouch [multi-pointer], injectKey)
│   ├── java/com/multitask/bubble/
│   │   ├── MainActivity.kt               # Setup dashboard, permission toggles & diagnostics log
│   │   ├── core/
│   │   │   ├── BubbleManager.kt          # State machine for bubble cap, recents, order & tiling
│   │   │   ├── DiagLog.kt                # Shared in-memory and logcat diagnostic stream
│   │   │   ├── ShellService.kt           # Privileged shell service running under Shizuku UID 2000
│   │   │   ├── ShizukuWindowBackend.kt   # Shell connection & multi-touch / key injection
│   │   │   └── WindowBackend.kt          # Window manager abstraction interface
│   │   ├── service/
│   │   │   └── BubbleOverlayService.kt   # Foreground overlay, gesture handling, arrange mode & drawer
│   │   └── ui/
│   │       ├── AppList.kt                # App entries loader, recents sorting & search adapter
│   │       ├── AppPickerSheet.kt         # Searchable app picker bottom sheet
│   │       ├── BubbleWindow.kt           # Window contract (show, hide, release, setBounds, raise)
│   │       ├── EmbeddedWindow.kt         # VirtualDisplay + SurfaceView with multi-touch forwarding
│   │       └── FreeformWindow.kt         # AOSP freeform window fallback
│   └── res/
│       ├── layout/                       # Material 3 view layouts (activities, drawer, windows)
│       └── values/                       # Material 3 teal tonal color palette and themes
└── src/test/java/com/multitask/bubble/
    └── BubbleManagerTest.kt              # Robolectric tests for cap, single/multi-open, and moveTo
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
