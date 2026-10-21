# Bubble Manager

**Universal Floating Multitasking Launcher for Android 10–16 (Powered by Shizuku)**

Bubble Manager (`com.multitask.bubble`) brings native-feeling floating app bubbles to Android 10 through 16+. Designed specifically for all tiers of Android devices—including budget hardware lacking native pop-up window or split-screen support (such as the Redmi A2)—it hosts real applications inside isolated virtual displays embedded into custom overlay frames.

The compiled debug-signed APK is available in the [`release/`](./release) folder:
- **Direct Download:** [`release/bubble-manager.apk`](./release/bubble-manager.apk)

---

## Technical Architecture & How It Works

Bubble Manager employs a dual-mode windowing architecture with automatic capability detection:

```
┌────────────────────────────────────────────────────────────────────────┐
│                        Bubble Manager Overlays                         │
│  • BubbleOverlayService (edge-docked bubbles, movable app drawer chip) │
│  • EmbeddedWindow (owned M3 frame, title bar, resize handle)           │
│  • SurfaceView buffer attached to VirtualDisplay                       │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ IPC (IShellService.aidl)
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                   Shizuku UserService (ShellService)                   │
│                Runs inside Shizuku daemon (UID 2000)                   │
│  • injectTouch(displayId, action, downTime, ids, xs, ys)               │
│  • injectKey(displayId, keyCode)                                       │
│  • am start -n <component> --display <id>                              │
│  • am force-stop <package>                                             │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ System Reflection / Binder
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                         Android OS Subsystems                          │
│  • DisplayManager (private VirtualDisplay creation & dynamic resize)   │
│  • IInputManager (hidden API event injection to secondary displays)    │
│  • ActivityTaskManager (freeform fallback via --windowingMode 5)       │
└────────────────────────────────────────────────────────────────────────┘
```

### 1. Primary Engine: Embedded Bubbles (Framework-Level Experience)
- **Isolated VirtualDisplay**: Each bubble instantiates a private `VirtualDisplay` via `DisplayManager` (`VIRTUAL_DISPLAY_FLAG_PUBLIC | VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY`).
- **SurfaceView Pipeline**: The display's pixel buffer renders directly into an owned `SurfaceView` within our floating overlay window (`TYPE_APPLICATION_OVERLAY`).
- **Full Window Ownership**: Because the window container lives in our overlay hierarchy, Bubble Manager owns the title bar, custom back button, minimize hook, close button, and corner resize handle—completely bypassing OEM windowing limitations.
- **Dynamic Corner Resizing**: Dragging the bottom-right corner resize handle (`@+id/resize_handle`) dynamically scales the window layout and invokes `VirtualDisplay.resize(newWidth, newHeight, densityDpi)` in real time.
- **Multi-Pointer Touch Injection**: High-fidelity multi-touch input forwarding via `IShellService.injectTouch` preserving all active pointer IDs, coordinates, and down-time timestamps. This enables:
  - **Pinch-to-zoom** (maps, browsers, photos).
  - **Double-tap to zoom**.
  - **Smooth inertial flings & continuous scrolling**.
  - **Hardware Back navigation** (`KEYCODE_BACK`).
- **Adaptive Buffer Scaling (Jank Prevention)**: To prevent GPU memory bandwidth saturation and surface tearing on budget devices (e.g., Redmi A2) during fast dragging and window resizing:
  - While a resize or drag gesture is active, `EmbeddedWindow` drops the internal `SurfaceView` buffer to **75% scale** (`holder.setFixedSize(width * 0.75, height * 0.75)`).
  - Heavy `VirtualDisplay.resize()` IPC calls are deferred until the gesture completes.
  - Upon finger lift or cancel (`ACTION_UP`/`ACTION_CANCEL`), `holder.setSizeFromLayout()` restores the native 1:1 buffer and the virtual display commits its final dimensions once.
- **Surface Redraw Validation**: Implements `SurfaceHolder.Callback2` with `surfaceRedrawNeeded()` to monitor when the hardware-buffer composition pipeline becomes active.
- **Black-Screen Diagnostics (`DiagnosticsDump`)**: Comprehensive state inspector combining local `SurfaceView` metrics with privileged Shizuku `dumpsys activity activities` and `dumpsys display` logs. Accessible via the in-app **Dump** button or adb command:
  ```bash
  adb shell am startservice -a com.multitask.bubble.DIAGNOSTICS com.multitask.bubble/.service.BubbleOverlayService
  ```
- **True Minimization**: Minimizing detaches the surface while keeping the app alive in memory. Tapping the bubble reattaches the surface immediately.
- **Clean Dismissal**: Dropping a bubble onto the bottom "X" drop target triggers `am force-stop` via Shizuku, terminating the package processes and releasing the virtual display.

### 2. Fallback Engine: System Freeform Window
- If a custom ROM or device lacks secondary virtual display support (`android.software.activities_on_secondary_displays`) or launching on the virtual display fails, Bubble Manager automatically falls back to system freeform windowing (`am start --windowingMode 5`).
- Best-effort task relocation and off-screen collapse via `am task resize`.

---

## Roadmap Features & Implementation Status

| Feature | Status | Implementation Details |
| :--- | :---: | :--- |
| **VirtualDisplay Embedding** | **Verified** | Private virtual displays created via `DisplayManager`, rendering into overlay `SurfaceView`. |
| **Adaptive Buffer Scaling** | **Verified** | 75% internal buffer during drag/resize; deferred `VirtualDisplay.resize()`; native restoration via `setSizeFromLayout()`. |
| **SurfaceRedraw Diagnostics** | **Verified** | `SurfaceHolder.Callback2` tracking hardware buffer readiness and surface validity. |
| **Movable App Drawer Chip** | **Verified** | Draggable grid chip (`__chip__`) movable freely anywhere on screen; coordinates persisted across restarts via `bubble_positions` SharedPreferences. |
| **Corner Window Resizing** | **Verified** | Touch-sensitive corner handle (`@+id/resize_handle`) tied to adaptive buffer scaling and dynamic layout updates. |
| **Multi-Pointer Touch Injection** | **Verified** | `IShellService.injectTouch` forwards full multi-touch coordinate arrays to target display ID via privileged `IInputManager` reflection. |
| **Arrange & Auto-Tiling Mode** | **Verified** | Long-press the grid chip to trigger haptic feedback and tile open windows into responsive slots (1, 2, or 3 windows). Drag title bar to reorder. |
| **App Drawer Overlay** | **Verified** | Built-in app picker dialog with live search filter, recently used apps pinned to top, and soft keyboard input support (`FLAG_NOT_TOUCH_MODAL`). |
| **Concurrency Cap (Max 3)** | **Verified** | `BubbleManager.kt` enforces a strict 3-bubble limit to conserve memory and CPU on budget devices (e.g., Redmi A2). |
| **Diagnostics Console & Dump** | **Verified** | Real-time diagnostics log (`DiagLog`) with in-app Dump button and `dumpsys` activity/display state extraction. |

---

## Engineering Cautions & Realities

1. **VirtualDisplay Compatibility**: Not all third-party apps or OEM ROMs permit execution on secondary displays. Apps with strict multi-display flags or complex multi-process surface composition may require the built-in freeform fallback mode.
2. **Freeform Commands**: `am start --windowingMode 5` and `am task resize` are best-effort fallbacks. Some OEM skins (HyperOS/MIUI, ColorOS) have proprietary window managers that may ignore system freeform resize commands.
3. **Process Termination**: `am force-stop` forcefully terminates the target package's running processes to immediately reclaim memory on budget devices. It does not erase user data or app storage.
4. **Hardware Performance**: Multiple simultaneous embedded applications consume significant RAM and GPU fill rate. On devices with 2GB–3GB RAM (like the Redmi A2), running 2–3 simultaneous active windows should be paired with single-open mode or closing unused bubbles.

---

## Setup & Getting Started

### Prerequisites
1. Android 10 (API 29) to Android 16.
2. **[Shizuku](https://shizuku.rikka.app/)** installed and running.

### Installation Instructions
1. Install `release/bubble-manager.apk` on your device:
   ```bash
   adb install -r release/bubble-manager.apk
   ```
2. Start Shizuku:
   - **Android 11+**: Pair via Developer Options -> Wireless Debugging.
   - **Android 10 or PC**: Connect via USB and run:
     ```bash
     adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
     ```
3. Open Bubble Manager and grant **Shizuku** permission and **Draw over other apps** (`SYSTEM_ALERT_WINDOW`) permission.
4. Tap **Start Service**.

---

## Verification & Testing Guide

When testing on a physical device:
1. **Service Start**: Tap **Start Service**. The edge-docked bubble stack and floating app drawer chip will appear.
2. **Movable Chip**: Drag the grid chip anywhere on screen. Kill the service and restart—verify the chip restores at the exact saved coordinates.
3. **App Drawer**: Tap the grid chip to open the app drawer. Search for an app (e.g. Maps, Keep, or Calculator) and launch it.
4. **Corner Resizing**: Touch and drag the bottom-right corner resize handle (`@+id/resize_handle`). Verify the window dimensions scale smoothly and `VirtualDisplay.resize()` updates the embedded content.
5. **Multi-Touch Gestures**: In a map or photo viewer app, test two-finger pinch-to-zoom, double-tap zoom, scrolling, and inertial flings.
6. **Title Bar Controls**:
   - Tap `<` (Back) to send a hardware Back key event into the embedded display.
   - Tap `-` (Minimize) to collapse the window while keeping the app alive.
   - Tap the docked bubble to restore the window.
7. **Arrange Mode**: Open 2 or 3 apps, then long-press the grid chip until a haptic buzz occurs. Windows animate into a neat tiled grid. Drag a window's title bar to swap slots. Long-press the chip again to exit arrange mode.
8. **Dismissal**: Drag a docked bubble down to the red **X zone** at the bottom to trigger `am force-stop` and dismiss the bubble.
9. **Diagnostics**: Open the main activity to inspect the Diagnostics card (verifying `Mode: embedded bubbles`).

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
