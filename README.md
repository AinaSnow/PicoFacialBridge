# PicoFacialBridge OSC

[中文](README_zh.md) | Fork of [WolalaQAQ/PicoFacialBridge](https://github.com/WolalaQAQ/PicoFacialBridge)

Headset-only PICO 4 Pro eye/face conversion and direct OSC output to VRChat.
Virtual Desktop remains the streaming software. No PC bridge, daemon, VRCFaceTracking,
or UnifiedPicoModule installation is required.

```
PICO Binder / JNI shared memory → headset Unified Expressions mapping
                              → OSC UDP → VRChat native Face Tracking
Virtual Desktop               → streaming (unchanged)
```

## Use

1. Install the built `PicoFacialBridge-debug.apk` yourself using your usual trusted sideload method.
   This fork has a separate application ID `dev.pico.facialbridge.osc` and label
   **PicoFacialBridge OSC**. Stop the original bridge before running this version.
2. Enable PICO eye and face tracking. Open the app and enter the **IPv4 address of the PC
   running VRChat**, UDP port **9000** (or VRChat's configured OSC input port), and a
   rate of **30–120 Hz** (default **60**). Start and grant both PICO tracking permissions.
3. In a VRChat build supporting [OSC Face Tracking](https://vrc-beta-docs.netlify.app/avatars/face-tracking/osc/),
   enable OSC and use an avatar configured for native Face Tracking with **Use All Tracking Keys**.
   Legacy VRCFT avatar parameter setups alone are not sufficient for this protocol.
4. Use Virtual Desktop normally. The app window can close while the foreground service runs.
5. Stop via the app or notification when finished. Start/Restart applies saved IP/port/rate.
   Eye and face switches apply immediately; both off pauses acquisition.

The target IP means the VRChat PC, not an extra PC program. The app sends UDP directly:
**packets sent does not confirm VRChat received them**. Check VRChat OSC settings, PC IP,
network reachability and the avatar when the sender shows activity but the face does not move.
Use a trusted LAN. The protocol is unencrypted and unauthenticated. The app does not open a
UDP discovery listener or request multicast access. It does not alter PC network settings.

## Mapping and timing

- `/tracking/face/v1`, type tag `,b`, exactly 102 blob bytes in the official wire order.
  Each datagram is **132 bytes** including OSC strings, big-endian length and padding.
- Source slots are mapped by meaning, not by the PC module's enum order. Values are
  clamped to 0..1 and truncated after multiplying by 255. NaN/Infinity become zero.
- Gaze comes from the morphology direction differences; blink comes from morphology.
  EyeWide is multiplied by 2 by default. No other corrective filters are enabled.
- Enhanced EPI1 v2 left eye, right eye and mouth have independent clocks and cached valid
  values. An update to one group retains the others. A new explicitly invalid mouth observation becomes neutral immediately; a missing group
  is retained. Eyes keep their last-valid policy. Cached groups expire after **2 seconds**
  without a valid sample. Mode/session/restart/selection changes reset caches.
- Pupil dilation/constriction (indices 10..13) stay zero: source pupil millimeters have no
  reliable normalization here. Unsupported expressions stay zero. No pupil support is claimed.
- The worker reads the original bounded PICO rings once per send interval, coalesces all
  retained observations, and sends the latest full snapshot. No outgoing frame queue or
  catch-up bursts. Mapping and OSC buffers are reused; upstream ring-reader allocations remain.
- The rate is a maximum target, not a real-time guarantee. Android scheduling and synchronous
  Binder calls may reduce it. UDP errors back off to one send per second. Scheduling waits for the remainder of a monotonic deadline, skipping overdue deadlines
  instead of catching up. Summaries run every five seconds; health changes are throttled to one per second.
- Permissions, foreground service, wake lock, enhance detection and dead/stale-session
  reconnection remain. Advancing but invalid source observations do not trigger reconnects. A final neutral packet on stop/restart is best effort over UDP.

## Compatibility and validation

The acquisition layer is inherited from upstream's PICO OS 5.13.7 / Android 10, arm64-v8a
implementation, without an OpenXR session. **The osc.3 changes have not been installed or tested on a headset yet.** Earlier osc.2
user-provided on-device logs informed the invalid-frame fixes. Those observations do not
validate this version or prove VRChat reception. End-to-end latency, battery impact and
long-session reliability remain unmeasured.

Host tests cover all 52 source slots / 102 wire positions, gaze direction, independent
clocks, stale/missing/invalid samples, switches/reset, non-finite values, clamp,
OSC alignment/endianness and actual local UDP decoding. See [validation](docs/VALIDATION.md).

## Build

Requires **JDK 17**, Gradle 8.9 (wrapper), Android SDK platform/build-tools 35,
NDK 26.1.10909125 and CMake 3.22.1. Point `JAVA_HOME` and `ANDROID_HOME` to existing tools.
Do not use JDK 25 with this Gradle version.

```powershell
# Pure Java conversion + local UDP test, works with the available JDK 25 too
pwsh -File tests/run-direct-osc.ps1
pwsh -File tests/check-translations.ps1
# Android toolchain required
.\gradlew.bat --no-daemon :bridge:assembleDebug :bridge:lintDebug
python tests/test_apk_distribution.py
```

Linux/macOS/Git Bash: `bash tests/run-host-tests.sh` and
`bash ./gradlew --no-daemon :bridge:assembleDebug :bridge:lintDebug`.
The user APK is `bridge/build/outputs/apk/debug/bridge-debug.apk`; the androidTest APK is
only an instrumentation runner, not the app to install. Debug signatures may change
between CI builds; Android can require uninstall/reinstall and will clear preferences.

The fork CI builds and checks the APK, verifies its signature/alignment, and uploads a
short-lived `android-build` artifact. It has no Release publishing job. Local tests
need loopback UDP access but do not require a headset. Do not install or enable any
headset debugging/security option merely to run host tests.

Legacy BridgeSplit utilities remain solely for probe and host regression tests and are
excluded from the shipping bridge's Java sources. There is no legacy-mode switch.
The Binder/JNI code is unchanged. License and mapping credits are included in each APK:
[MIT](LICENSE), [third-party notices](THIRD_PARTY_NOTICES.md).

## Diagnostics

Version 0.6.0-osc.2 adds 5-second capture/validity/cache/conversion/UDP summaries and throttled state/error logs. View recent logs in the app or use logcat tag `PicoFacialBridge` (native details: `PicoBinderProbe`). Default logs contain no expression values. Version 0.6.0-osc.3 adds an explicit, default-off
Eye direction diagnostics switch (at most 2 samples/s, local logs only). No camera images,
automatic upload or persistent log files. See [field definitions and commands](docs/DIAGNOSTICS.md).
