# Wired compatibility mode and connection diagnostics

The wired connection setting has two explicit choices:

- **Standard mode (VPN)** is the existing default.
- **Compatibility mode (no VPN)** runs IPv6/TCP/UDP inside the application using lwIP 2.2.1. It does not create a TUN, configure system routes, require root, or initialize Bluetooth.

There is no automatic fallback. Changing the mode during a session requires a separate disconnect confirmation. Saving does not reconnect; the next manually started wired connection uses the saved mode. Wireless connection behavior and the existing authentication/signing inputs are retained.

## Architecture

```
iPhone USBMUX / Lockdown / iAP2 -> existing control and authentication

iPhone USB NCM -> EthernetFramePipe -> lwIP IPv6 / TCP / UDP
                                      -> session-owned AirPlayNetwork
                                      -> control, video, audio, events, timing, microphone
```

`CarPlayUserSpaceService` is an ordinary bound Android Service. It owns the NCM frame pumps, native stack, listeners and accepted sockets. `DiPlaySessionService` continues to own the foreground session notification. The system socket factory is retained for standard wired and wireless transports. All dynamically negotiated AirPlay ports and microphone output use the session's selected factory.

The native port supports IPv6 neighbor discovery, fragmentation/reassembly, TCP retransmission, out-of-order buffering, and UDP checksums. Its frame queues are bounded; output overflow fails the attachment instead of silently dropping data indefinitely. One native USB interface may be attached at a time. Detach closes tracked sockets, unblocks pending operations, closes USB, joins the frame pumps and removes the interface. lwIP's process-wide timer thread is retained for later attachments.

## Diagnosis

Open **Connection diagnostics** from the home page or Settings. Start detection uses a real wired connection with the manually selected mode. The page can also observe a connection started elsewhere without restarting it. Stop disconnects the session. The report export includes structured stages and the existing redacted log.

Stages distinguish:

- declared USB Host capability from actual device access;
- authentication input loading from iPhone authentication acceptance;
- backend/listener initialization from USB IPv6 input and bidirectional reachability;
- AirPlay activation from a decoded/rendered video frame;
- delivered audio and sent touch/microphone packets from human-observed output.

States are not started, detecting, passed, failed, unverified and not applicable. A failure retains earlier evidence, interrupts concurrent checks and leaves later checks unexecuted. Stop rejects late callbacks. High-frequency media/frame evidence keeps the first successful timestamp. If AirPlay has not activated within 45 seconds after an iAP2 CarPlay start request, diagnosis records a timeout and closes that attempt; retry is manual.

Bluetooth is not applicable to wired mode. VPN is not applicable to compatibility mode. Diagnostic text and exports exclude secret-bearing errors and peer addresses. The most recent attempt is retained in memory across page navigation/activity recreation; process termination clears it, so export before exiting when a report is needed.

English and Simplified Chinese UI strings are provided; other languages use the English fallback for the new controls.

## Automated validation

Use the repository's pinned JDK/SDK/NDK and existing authentication inputs. Source-only builds still omit runtime identity assets.

```sh
bash gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug --max-workers=4
python3 scripts/test_userspace_network.py
python3 scripts/test_userspace_network.py --adapters
```

The desktop network tests require JDK 25, gcc and Python 3. Export `JAVA_HOME` and `GRADLE_USER_HOME`. `--adapters` uses the compiled `:shared:bundleLibCompileToJarDebug` output and Gradle's Kotlin runtime to exercise the actual Kotlin socket adapters over a virtual Ethernet cable. Both tests build the same lwIP/JNI sources used in the APK. They exercise NDP, valid and invalid UDP checksums, fragmented IPv6 traffic, TCP handshake, out-of-order receive, retransmission, peer EOF, timeout, close waking accept, and detach/reattach. No VPN, root, physical USB device or authentication input is used.

Robolectric page tests inflate the real diagnosis page, verify failure evidence after recreation, and select/persist a mode without starting a connection. `common` unit tests include Android resources for these checks.

## Android emulator UI acceptance

Install the debug APK on a booted emulator. The simulation activity exists only in debug code and requires the shell's DUMP permission. It refuses to replace an existing background session. Its fixture always displays a **SIMULATION** banner, including in exported reports, and does not access USB or authentication secrets.

```sh
adb install -r mobile/build/outputs/apk/debug/mobile-debug.apk
adb shell am start -n com.shihab.diplay.hudtest/com.shilapi.xcertplay.diagnostics.WiredDiagnosticSimulationActivity --es scenario ncm_missing
python3 scripts/test_wired_diagnostics_ui.py --serial emulator-5554 --output /tmp/diplay-ui-results
```

Supported fixtures: `success`, `usb_denied`, `ncm_missing`, `network_failed`, `airplay_timeout`, `vpn_unavailable`. Success still leaves audio audibility, touch response and microphone reception unverified. The acceptance script captures UI hierarchy and screenshots for every fixture and checks the displayed outcome and simulation banner. Robolectric separately verifies that later stages are marked blocked after an NCM failure.

## Physical acceptance remains required

Emulated UI and a virtual Ethernet peer do not reproduce iPhone USB re-enumeration, vendor USB restrictions, actual accessory acceptance or vehicle media hardware. Use the existing fully provisioned package on each target head unit and iPhone to verify both manually selected modes, video, audible audio, touch, Siri, calls and navigation. Compare CPU/memory/latency with standard mode where it is supported; run at least 60 minutes and 20 plug/unplug cycles. Keep simulation, desktop network, and physical results separate.
