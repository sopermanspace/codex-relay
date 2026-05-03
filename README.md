# Codex Relay

A native Android app plus a local app-server that lets you control Codex from your phone.

## Start

```bash
npm install
npm run setup
npm start
```

Keep this app-server running on your Mac. Setup creates a private persistent token, uses the current folder as the default Codex workspace, and prints the local URL your phone should use at home.

## Native Android App

The native app project lives in:

```text
android-native/
```

It is a real Android app named **Codex Relay** with its own launcher icon, generated character artwork, native connect screen, Relay Dashboard, and clean Codex result rendering. It does not use WebView.

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

Use **Home network** in the Android app when your phone and Mac are on the same trusted Wi-Fi.

Use **Away from home** only with an HTTPS tunnel or reverse proxy. Do not port-forward this server directly from your router. One practical setup:

```bash
cloudflared tunnel --url http://localhost:8787
```

Then set these in `.env`:

```bash
TRUST_PROXY=true
PUBLIC_URL=https://your-secure-tunnel.example
```

Restart with `npm start`, then use the HTTPS URL in **Away from home** mode. A reverse proxy with TLS and IP allowlisting is also supported.

## Security Notes

This controls a shell session on your Mac. Keep `REMOTE_TOKEN` long and private, use HTTPS for remote access, avoid direct internet exposure, and rotate the token by running `npm run setup` again if it is ever shared.
