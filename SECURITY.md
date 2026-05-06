# Security Policy

Codex Relay controls a local Codex process and can indirectly run commands on the host machine. Treat it like a local administration tool.

## Supported Versions

The `main` branch receives security fixes before public releases are tagged.

## Safe Deployment

- Keep `HOST=127.0.0.1` unless you are actively pairing a phone on trusted Wi-Fi.
- Use `HOST=0.0.0.0` only on trusted private networks.
- Do not port-forward the server directly from a router.
- Use HTTPS for remote access through a trusted tunnel or reverse proxy.
- Keep `.env`, signing keys, tokens, and generated APK signing material out of git.

## Reporting A Vulnerability

Open a private security advisory on GitHub if the repository has advisories enabled. If not, contact the repository owner directly before publishing details.

Please include:

- Affected commit or release.
- Clear reproduction steps.
- Impact and expected behavior.
- Any suggested fix, if known.
