# AURORA Mobile V2 — native Android source

A native Kotlin Android companion app for the AURORA/JARVIS desktop assistant. This is not a website or PWA. Current source version: **2.0.3 (versionCode 5)**.

## Current features
- Dark, cyan-accented chat UI with Turkish push-to-talk.
- QR pairing through a `jarvis://pair` payload, explicit PC approval, and private IPv4 LAN host validation.
- Desktop chat messages sent to `/api/message`; incoming messages polled from `/api/events`.
- User-started draggable floating assistant bubble and mini-chat overlay. Tapping the bubble opens the mini panel; dragging moves it. Android's **Display over other apps** permission is required; returning from the permission screen starts the bubble if permission was granted. The main screen can request stopping it too.
- Mini-chat polls desktop events while open so replies can be displayed in the overlay.
- V2.0.3: starting a fresh QR pairing invalidates the previous poll loop and clears the stored session token; main and overlay event cursors persist across restarts to reduce repeated messages.
- Persistent foreground notification with a stop action while the overlay is running.
- Private local diagnostic log at `files/aurora-diagnostics.log`; it records event names/errors, not chat contents, API keys, or session tokens.
- GitHub Actions workflow (`.github/workflows/android-build.yml`) to build a debug APK and upload it as a workflow artifact.
- Manual update mode: choose a local APK and hand it to Android’s package installer after the user grants “install unknown apps” permission. This is not an automatic online update feed.

## Build APK

### Android Studio
1. Install Android Studio and JDK 17 or newer.
2. Open this project directory and allow Gradle sync (internet is needed for dependencies).
3. Select **Build > Build APK(s)**.

### GitHub Actions
Push the full project to a GitHub repository. The included workflow builds `app-debug.apk` on push, pull request, or manual dispatch and attaches it as an Actions artifact. Check the workflow run status; adding a workflow file alone does not prove the app builds.

## Desktop integration contract
The paired desktop JARVIS must implement compatible endpoints:
- `POST /api/pair-request`
- `GET /api/pair-status?id=...`
- `POST /api/message`
- `GET /api/events?session=...&since=...`

The exact request/response JSON schema must match the desktop implementation. The mobile app does not patch or update the desktop app. Overlay messages are only marked as sent when the desktop endpoint returns `accepted`; the phone cannot verify that the desktop completed a requested action unless the desktop emits a result event.

## Update system status
Manual APK handoff to Android’s package installer is implemented. **Online update checking is not enabled** because no canonical release feed/repository was supplied. Before adding an online feed, use HTTPS, validate the manifest and APK SHA-256/signature, compare versions, and keep Android’s user-approved installer flow. Do not embed provider API keys in the app.

## Planned, not implemented
- Camera capture and image-analysis pipeline.
- Notification listener / phone notification center.
- Public release feed and update installer.
- Application-layer encryption.

## Security notes
- Current pairing accepts literal private IPv4 LAN addresses only (not DNS names) and requires PC-side approval. QR ports are validated before requests are sent.
- Keep Groq/Fish/provider keys on the desktop.
- Current local protocol uses HTTP; use only on a trusted private LAN until application-layer encryption is added.
- Android requires explicit user permission for overlays; Android may also restrict foreground services or notifications by OS version/vendor.
