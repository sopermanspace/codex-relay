# Codex Relay Android App

Native Android client for the Codex Relay app-server. It is a real Android app with nearby Wi-Fi discovery, encrypted paired-device token storage, project selection, command sending, and Codex result rendering.

## Build Locally

Install Android Studio or a working JDK and Gradle setup, then run:

```bash
cd android-native
./build-apk.sh
```

The debug APK is created at:

```text
android-native/app/build/outputs/apk/debug/app-debug.apk
```

## Build In GitHub Actions

Run the **Build Android APK** workflow manually from the Actions tab. It builds an unsigned release APK artifact:

```text
codex-relay-release-unsigned-apk
```

Unsigned release artifacts are for inspection and testing only. Configure Android signing separately before distributing public releases.

## Pair A Phone

1. Run `npm run setup` from the repository root.
2. Set `HOST=0.0.0.0` in the generated `.env` while pairing on trusted Wi-Fi.
3. Start the server with `npm start`.
4. Open the Android app and tap **Continue**.
5. Confirm the pairing request on the Mac web screen.
6. Enter the 8-digit code shown on the Mac.

The app accepts `http://` only for private local-network hosts such as `192.168.x.x`, `10.x.x.x`, `172.16-31.x.x`, localhost, and link-local addresses. Remote URLs must use `https://`.

## Updates

In-app APK installation is intentionally disabled. Builds can optionally set `BuildConfig.UPDATE_RELEASE_URL` to an HTTPS GitHub release API URL, and the app will open the release page when a newer version is available.
