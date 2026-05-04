# Codex Relay

A native Android app plus a local app-server that lets you control Codex from your phone.

## Start

```bash
npm install
npm run setup
npm start
```

Keep this app-server running on your Mac. Setup prepares the local server and uses the current folder as the default Codex workspace. On first Android setup, keep your phone near the Mac on the same Wi-Fi and tap **Continue**. The Mac web screen shows a nearby-device pairing request with a fresh 8-digit one-time code; confirm it on the Mac, then enter the code on your phone.

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

Use **Home network** in the Android app when your phone and Mac are on the same trusted Wi-Fi. The first setup discovers the Mac locally, then stores a private device key on the phone so future launches reconnect automatically.

Use **Away from home** only with an HTTPS tunnel or reverse proxy. Do not port-forward this server directly from your router. One practical setup:

```bash
cloudflared tunnel --url http://localhost:8787
```

Then set these in `.env`:

```bash
TRUST_PROXY=true
PUBLIC_URL=https://your-secure-tunnel.example
```

Restart with `npm start`. After the phone has paired once, it can reconnect automatically whenever the saved server address is reachable. For away-from-home use, that saved address must be an HTTPS tunnel or reverse proxy; a code by itself cannot make a private Mac reachable across the internet.

## Security Notes

This controls a shell session on your Mac. Pairing codes are one-time, short-lived, rate-limited, and exchanged for a random device key stored on the phone. Use HTTPS for remote access, avoid direct internet exposure, and prefer a trusted tunnel or reverse proxy. Nearby discovery removes manual URL entry for first setup, but it does not replace TLS for hostile networks or the need for a reachable HTTPS tunnel when you are away.
