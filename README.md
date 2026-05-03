# Codex Android Remote

A native Android app plus a local app-server that lets you control Codex from your phone.

## Start

```bash
npm install
cp .env.example .env
npm start
```

Keep this app-server running on your Mac. The native Android app connects to it directly over HTTP and WebSocket.

## Native Android App

The native app project lives in:

```text
android-native/
```

It is a real Android app with its own launcher icon, native connect screen, native terminal output, and direct WebSocket control of the Codex app-server. It does not use WebView.

Build after installing Android Studio or a working JDK/Gradle setup:

```bash
cd android-native
./build-apk.sh
```

This machine currently reports no Java runtime, so APK compilation is blocked here until Android tooling is installed.

No-install workaround: push this repo to GitHub and run **Build Android APK** from the Actions tab. The workflow builds the native APK in GitHub Actions and uploads `codex-remote-debug-apk` as a downloadable artifact.

The launcher logo is available as:

- `public/brand/codex-remote-logo.svg`
- `public/brand/codex-remote-logo.png`

Regenerate Android PNG icons after logo edits:

```bash
npm run build:icons
```

## Use From Anywhere

Expose this app-server only behind HTTPS and a private access layer. Two practical options:

```bash
cloudflared tunnel --url http://localhost:8787
```

or place it behind a reverse proxy with TLS and IP allowlisting. Set `PUBLIC_URL` in `.env` to the HTTPS URL so the server prints a scannable QR.

## Security Notes

This controls a shell session on your Mac. Keep `REMOTE_TOKEN` long and private, use HTTPS, and avoid exposing the port directly to the internet.
