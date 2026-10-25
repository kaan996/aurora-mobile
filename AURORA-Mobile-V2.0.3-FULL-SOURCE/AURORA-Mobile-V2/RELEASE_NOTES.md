# AURORA Mobile V2 — Release notes

## 2.0.1 (source update; not yet device-tested)
- Added a main-screen stop control for the floating assistant.
- Returning from Android overlay permission settings starts the bubble only if permission was granted.
- Tightened pairing host validation to literal private IPv4 addresses and validated QR port range.
- Logged floating-panel attach failures for diagnostics.
- Increased Android versionCode to 3 / versionName to 2.0.1.

## Verification status
- ZIP integrity and AndroidManifest XML parsing can be checked in this workspace.
- Android compilation and device behavior are not verified here because Android SDK/Gradle are unavailable.
- Online update feed/installer, camera image-analysis, and notification-listener features remain unimplemented.


## V2.0.3 — connection reliability
- Starting a new QR pairing invalidates the previous polling loop and clears the old session token.
- Main chat event cursor is saved, reducing duplicate messages after app restart.
- Floating mini-chat keeps its own event cursor across overlay restarts.
- Updated mobile branding and several user-facing prompts to AURORA/Turkish.
- Static source checks only; Android build and device testing still require an Android SDK/device.
