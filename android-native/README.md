# Codex Relay Android App

Native Android client for the Codex app-server. This is not a browser install flow and not a WebView shell: it builds as **Codex Relay**, an Android app with its own launcher icon, generated character artwork, native connect screen, Relay Dashboard, and clean Codex result rendering.

## What It Does

- Creates Codex sessions through the app-server HTTP API.
- Uses a native connection screen for the server URL and remote token.
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

## Configure

Default server URL is set in:

```text
app/src/main/res/values/strings.xml
```

For phone testing on the same Wi-Fi network, use your Mac LAN URL from the app-server output, for example:

```text
http://192.168.18.182:8787
```

For “from anywhere,” use an HTTPS tunnel URL such as Cloudflare Tunnel.
