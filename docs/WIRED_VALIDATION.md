# Wired compatibility validation — 2026-10-06

Branch: `feature/wired-userspace-diagnostics`.

## Completed

- Shared module: 266 unit tests, zero failures/errors/skips.
- Common module: 75 unit tests, zero failures/errors/skips, including real diagnosis-page inflation, activity recreation and manual mode selection.
- Android lint: zero errors, 19 warnings; the combined build/test/lint command completed successfully.
- Native JNI and production Kotlin socket-adapter tests: IPv6 neighbor discovery; bidirectional UDP/checksum rejection; IPv6 fragment reassembly/output; TCP handshake, out-of-order receive, retransmission and peer EOF; receive timeout; close waking accept; detach/reattach.
- Debug APK builds with `libdiplay_userspace.so` for arm64-v8a, armeabi-v7a, x86 and x86_64 and includes the lwIP license.
- Public-tree credential scan and `git diff --check` pass.

## Not completed

- Android emulator screenshot acceptance: API 35 x86_64 AVD was created and started, but this environment has no KVM. Software-emulated startup did not complete after more than 20 minutes; the emulator was stopped. The six debug fixtures and acceptance script remain available for a booted emulator.
- Physical iPhone/head-unit acceptance, performance, long-running stability and plug/unplug cycles require the target devices and the existing provisioned package.

The source-only debug APK is a development artifact. Existing authentication provisioning and signing requirements remain unchanged. Automated desktop and page tests do not establish successful physical CarPlay operation.

See [WIRED_COMPATIBILITY.md](WIRED_COMPATIBILITY.md) for the architecture, commands, simulator fixtures and device acceptance procedure.
