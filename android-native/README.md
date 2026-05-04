# Codex Relay Android App

Native Android client for the Codex app-server. This is not a browser install flow and not a WebView shell: it builds as **Codex Relay**, an Android app with its own launcher icon, generated character artwork, native connect screen, Relay Dashboard, and clean Codex result rendering.

## What It Does

- Creates Codex sessions through the app-server HTTP API.
- Uses a native nearby-pairing screen with local Wi-Fi discovery and a one-time pairing code.
- Verifies access through the app-server auth API.
- Sends tasks through the app-server command API.
- Renders Codex results in native Android views.
- Allows local-network HTTP during development, plus HTTPS for remote tunnel use.

## Build

This machine currently does not have a working Java runtime or Gradle, so the APK cannot be compiled here yet. After installing Android Studio or a JDK plus Gradle, run:

```bash
cd android-native
./build-apk.sh
```

The debug APK will be created at:

```text
android-native/app/build/outputs/apk/debug/app-debug.apk
```

## Build Without Installing Android Tools

Push the repo to GitHub and run the **Build Android APK** workflow from the Actions tab. It builds in GitHub Actions and uploads a downloadable artifact named:

```text
codex-remote-debug-apk
```

## Pair The Phone

1. Start the Codex Relay server on your Mac with `npm start`.
2. Open the Android app while the phone and Mac are on the same Wi-Fi.
3. Tap **Continue**. The phone discovers Codex Relay locally and asks the Mac to show a fresh 8-digit code.
4. Confirm the nearby-device request on the Codex Relay web screen on your Mac. This prompt appears in Relay, not inside the closed-source Codex desktop app.
5. Enter the code shown on the Mac. The app stores a private paired-device key and reconnects automatically after that.

The old default server URL is kept only as a development fallback in:

```text
app/src/main/res/values/strings.xml
```

For "from anywhere," pair once nearby first, then make the saved server reachable through an HTTPS tunnel such as Cloudflare Tunnel. Do not expose the local HTTP server directly to the internet.
