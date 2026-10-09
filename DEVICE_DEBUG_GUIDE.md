# Pixel 10a & Android 17 Canary On-Device Development & Debugging Guide

This guide documents the full technical profile, diagnostics, debugging steps, and commands for developing, testing, and debugging **Termux+ (Termux AI)** directly on the physical device.

---

## 1. Target Hardware & Environment Profile

| Attribute | Specification | Notes |
| :--- | :--- | :--- |
| **Device Model** | Google Pixel 10a | Google Tensor platform |
| **Operating System** | Android 17 Canary (API 36+) | Preview / Developer Canary channel |
| **Root Status** | Rooted | Available via `su` (KernelSU / Magisk / APatch) |
| **CPU Architecture** | ARM64 (`arm64-v8a`) | Pure 64-bit userland (no 32-bit `armeabi-v7a` support) |
| **Memory Page Size** | **16 KB (16,384 bytes)** | Critical: requires ELF 16 KB alignment |
| **Application ID** | `com.termux.ai` | Main Activity: `com.termux.app.TabbedTerminalActivity` |
| **Internal Data Dir**| `/data/data/com.termux.ai` | User storage: `/data/user/0/com.termux.ai` |

---

## 2. On-Device Diagnostic Commands (Root Shell)

If you are working from a terminal on the phone (or via SSH / adb), use these commands with root permissions (`su`):

### A. Check the App Crash Dump File
Build 10+ includes an unhandled exception interceptor that writes crash dumps directly to disk before the process terminates:
```bash
su -c "cat /data/data/com.termux.ai/files/last_crash.txt"
```
*If a crash occurs, this file will contain the failing thread name, exception class, message, and full Java/Kotlin stack trace.*

### B. Live & Historical Logcat for Termux AI
Filter specifically for app startup logs, crash logs, and terminal engine outputs:
```bash
# Capture recent fatal crashes and Termux tags
su -c "logcat -d -v time -s TERMUX_CRASH:* AndroidRuntime:E TermuxPTY:* TermuxPlusApplication:* EnhancedTerminalView:*"

# Follow live output while launching the app
su -c "logcat -v time -s TERMUX_CRASH:* AndroidRuntime:E TermuxPTY:* TermuxPlusApplication:*"
```

### C. Launch / Restart the App from Terminal
```bash
# Force stop the app
su -c "am force-stop com.termux.ai"

# Start the main Tabbed Terminal Activity
su -c "am start -n com.termux.ai/com.termux.app.TabbedTerminalActivity"
```

### D. Clear App Cache & Settings (Reset App State)
```bash
su -c "pm clear com.termux.ai"
```

### E. Install Downloaded APK Directly via Root
If you download a new APK to your phone's `Download/` directory:
```bash
su -c "pm install -r -d /sdcard/Download/app-debug.apk"
```

### F. Verify 16 KB Page Alignment of Native Libraries
To confirm `libtermux.so` is properly aligned to 16 KB on device:
```bash
# Locate installed shared library
LIB_PATH=$(su -c "find /data/app -name libtermux.so | grep com.termux.ai | head -n 1")

# Inspect ELF Program Headers
su -c "readelf -W -l $LIB_PATH | grep -E 'LOAD|Align'"
```
*Expected output: All `LOAD` segments must specify `Align 0x4000` (16,384 bytes).*

---

## 3. History of Fixes & Architectural Decisions

### Issue 1: 16 KB Page Alignment Crash (`dlopen` abort)
- **Symptom:** On Android 15+ kernels with 16 KB page size, 4 KB-aligned `.so` libraries cannot be mapped into memory.
- **Fix:** 
  1. Configured CMake linker options in `terminal-emulator/CMakeLists.txt`:
     ```cmake
     target_link_options(termux PRIVATE "-Wl,-z,max-page-size=16384")
     ```
  2. Set `useLegacyPackaging = false` in `app/build.gradle` so libraries remain uncompressed and 16 KB-aligned in the APK archive.
  3. Removed `-Wl` from Gradle compiler flags (`cppFlags`/`cFlags`), which clang treats as an unused compiler argument.

### Issue 2: Terminal Layout NullPointerException (`mRenderer` null)
- **Symptom:** App crashed immediately upon opening with a blank screen.
- **Root Cause:** In `TerminalView.java` (line 990), `updateSize()` calculates column counts via `viewWidth / mRenderer.mFontWidth`. Upstream Termux relied on the legacy `TermuxActivity` to call `setTextSize()` before layout. In the tabbed rewrite, `setTextSize()` was omitted, leaving `mRenderer` null during Android's initial layout pass (`onSizeChanged`).
- **Fix:**
  - Initialized `mRenderer` in `TerminalView` constructor with default monospace font size.
  - Added null fallback in `TerminalView.updateSize()`.
  - Added explicit `setTextSize()` in `EnhancedTerminalView.initialize()` and `TerminalFragment.setupTerminalView()`.

### Issue 3: Sensor Registration Exception on Rooted/Canary Devices
- **Symptom:** `ShakeDetector` crashed `onCreate()` on devices where accelerometer sensors are missing, restricted, or return null.
- **Fix:** Added null checks and try-catch guards around `SensorManager.registerListener()` and `unregisterListener()`.

### Issue 4: Android 17 Canary Keystore Provider Changes
- **Symptom:** `EncryptedSharedPreferences` throws `SecurityException` or fails on developer preview builds when Keystore schemas change.
- **Fix:** Added a test read in `EncryptedPreferencesManager.java` (`prefs.getAll()`). If any Keystore failure occurs, it automatically falls back to standard `SharedPreferences` without crashing the application.

### Issue 5: Pseudo-Terminal Master File Descriptor GC Closure
- **Symptom:** Reader and writer threads lost the native PTY file descriptor abruptly.
- **Root Cause:** Calling `ParcelFileDescriptor.adoptFd(fd)` inside a static method meant the wrapper object was garbage collected immediately, closing the underlying file descriptor.
- **Fix:** Saved `ParcelFileDescriptor` into an instance field `mTerminalParcelFileDescriptor` on `TerminalSession`, kept alive until `cleanupResources()` is invoked.

---

## 4. Continuing Development Directly from the Phone

If you clone the repository onto the phone (e.g. inside a local Termux environment or chroot/proot):

### A. Clone and Sync
```bash
git clone https://github.com/fvkd/termux-ai-app.git
cd termux-ai-app
git pull origin main
```

### B. Trigger CI Builds via GitHub CLI (`gh`)
If building on GitHub Actions from the phone:
```bash
# Push your code change
git commit -am "your commit message"
git push origin main

# Monitor the build
gh run list -L 3
gh run watch <RUN_ID>

# Download the produced APK release
gh release download v0.1.0-build-<N> -p "app-debug.apk" -O app.apk
su -c "pm install -r -d app.apk"
```

### C. Build Locally on Phone (If JDK & Android SDK Installed)
```bash
./gradlew assembleDebug --no-daemon
su -c "pm install -r -d app/build/outputs/apk/debug/app-debug.apk"
```

---

## 5. Key File Locations

- **Main Activity:** [TabbedTerminalActivity.java](file:///home/vivivi/projects/termux-ai-app/app/src/main/java/com/termux/app/TabbedTerminalActivity.java)
- **Application Class & Crash Interceptor:** [TermuxPlusApplication.java](file:///home/vivivi/projects/termux-ai-app/app/src/main/java/com/termux/plus/TermuxPlusApplication.java)
- **Terminal Rendering Engine:** [TerminalView.java](file:///home/vivivi/projects/termux-ai-app/app/src/main/java/com/termux/view/TerminalView.java)
- **Enhanced Terminal View:** [EnhancedTerminalView.java](file:///home/vivivi/projects/termux-ai-app/app/src/main/java/com/termux/terminal/EnhancedTerminalView.java)
- **PTY Session & Native IO:** [TerminalSession.java](file:///home/vivivi/projects/termux-ai-app/terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java)
- **16 KB CMake Config:** [CMakeLists.txt](file:///home/vivivi/projects/termux-ai-app/terminal-emulator/CMakeLists.txt)
- **Build Memory & History:** [BUILD_MEMORY.md](file:///home/vivivi/projects/termux-ai-app/BUILD_MEMORY.md)
