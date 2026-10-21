# Direct OSC validation

Base bridge: bd35d56fe6b25a061f27da3a9e606596d732fe50.
Mapping reference: UnifiedPicoModule 486f176611e5f640b1317ea0b9730dc6ee72a5f7 (MIT).
Protocol checked against VRChat OSC Face Tracking documentation (2026-10-21) and
VRCFT e1056bb98843dd0fd1b152f24cce3b09d0d19db4 (read-only interoperability reference).

## Completed locally on Windows

- Official GitHub CLI identity: AinaSnow; fork parent verified as WolalaQAQ/PicoFacialBridge.
- JDK 25 compiles the new host-only classes with `--release 8`.
- DirectOscTest: 5,784 checks, including all 52 source slots against 102 wire indices,
  direction subtraction/signs, NaN/Inf/clamp, padding, big-endian length, 132-byte framing,
  v1/v2 independent groups, missing/invalid/stale frames, reset, mode changes, switches,
  destination validation, fixed packet reuse and actual local UDP decoding.
- Original host regression suites: 58 shared-memory, 29 legacy protocol,
  261 cadence/history, 50 forwarding/rate/loopback, 37 independent-expression checks.
  Legacy transport tests protect shared acquisition assumptions, not the shipping OSC transport.
- English/Chinese resource parity and format placeholders checked.

Loopback UDP timed out inside the execution sandbox; the same test passed outside it
with approved local execution. No firewall/network setting was changed.
No local Android SDK/NDK/JDK 17 was found; Android build uses the fork GitHub Actions runner.
CI checks build, Android lint, APK contents, apksigner and zipalign. See the associated
commit's Actions run for the exact outcome; the final delivery records the run URL/hash.

## Hardware acceptance still required (manual)

1. Install only the user APK; confirm PICO permissions and ordinary/enhanced mode label.
2. With the PC IP and OSC port configured, verify the VRChat native Face Tracking avatar
   responds to each eye separately, blink, brow, jaw, smile and tongue.
3. In enhanced mode verify a one-eye update does not reset mouth or the other eye.
4. Check channel switches, Stop neutralization, headset sleep/wake, lost Wi-Fi and reconnect.
5. Check VD coexistence, long sessions, actual latency/cadence, CPU load and battery usage.
6. Confirm pupil keys remain zero and unsupported expressions do not retain old values.

No hardware tests, APK installation, Unity project edits or performance measurements
were performed by this development task. UDP send success is not receiver confirmation.

## Diagnostics update (0.6.0-osc.2)

BridgeDiagnosticsTest adds 136 counter, elapsed-time rate, throttle, privacy and recovery checks.
No hardware acceptance or performance measurements were added.


## Recovery and eye diagnostics (0.6.0-osc.3)

RecoveryPacingTest adds invalid-but-advancing liveness, real stall/Binder recovery,
explicit invalid mouth neutralization, independent eye hold behavior, exact expiry,
60/90 Hz deadline scheduling (also 30/120), overrun skipping and opt-in eye diagnostic privacy/rate tests.
Deterministic simulated 2 ms work stays within 1 ms over ten seconds at every tested rate.
The optional Windows host `--measure` run reports actual local scheduling; it is not an Android benchmark.
This version still needs manual headset/VRChat acceptance. No installation or Unity changes are performed.

Local osc.3 results: 5,784 mapping/OSC, 136 summary diagnostics, 3,568 recovery/pacing/debug,
and 435 original regression checks passed. Local UDP decode passed outside the restricted sandbox.
Windows wait-only pacing sample: 60 configured / 59.989 Hz observed and 90 / 89.974 Hz,
each over approximately 3 seconds. No capture, network workload or headset was included.
An earlier stress sample using Thread.sleep(2) as work was slower (59.187 / 32.260 Hz);
that synthetic sleep also depends on Windows timer scheduling, so it is not an Android prediction.


## Stable parallel package (0.6.0-osc.4)

Core behavior is unchanged from osc.3. New package ID dev.pico.facialbridge.osc.direct and
application label PicoFacialBridge OSC Direct preserve the old package. CI disables both
debug and release signing, asserts its artifact is unsigned and verifies package/version/label.
The local signing script rejects missing keys and pins the public signer fingerprint before
and after signing. Private signing material never enters CI, Git or deliverable attachments.
