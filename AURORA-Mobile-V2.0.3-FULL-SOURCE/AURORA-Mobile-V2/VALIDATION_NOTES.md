# Validation notes — AURORA Mobile V2.0.3

Checks performed in this environment:
- ZIP archive integrity: checked after packaging.
- AndroidManifest.xml and XML resources: parsed as XML.
- Kotlin files: run through `kotlinc` as a syntax-screening attempt; this environment has no Android SDK / AndroidX / ZXing classpath, so type resolution and Android compilation cannot be confirmed. No Kotlin parser errors (`expecting`) were emitted in the prior source screening; V2.0.3 changes were also screened for balanced structure and expected API/session references. This is not a substitute for a full Android compile.
- Source inspection: confirmed manual APK selection is routed through Android's package installer, and unknown-app installation permission is requested only when needed.

Not verified here:
- `gradle assembleDebug` (Gradle/Android SDK are not installed in this environment).
- Running on a physical Android device.
- Pairing and endpoint compatibility with the desktop JARVIS build.
- Automatic online update checks (not implemented because no trusted release feed was provided).

Additional V2.0.3 source checks:
- Confirmed main and overlay event cursors are persisted in SharedPreferences.
- Confirmed new pairing invalidates the prior poll generation and clears the stored session token.
