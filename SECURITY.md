# Security

Report sensitive vulnerabilities using this repository's private security reporting when available. Otherwise open a minimal issue requesting a private contact without posting secrets or exploit data.

USB video/control bind to Android loopback and are reached through an ADB forward. Only authorize trusted PCs. The local USB control endpoint is intended for trusted local software; it is not a multi-user authentication boundary.

Wi-Fi is opt-in. The app creates a device certificate with its private key in Android Keystore and a fresh random 128-bit pairing key on each enable/session. The PC pins the certificate's SHA-256 fingerprint and authenticates video/control using that pairing key over TLS. Keep the complete connection link private. Disable Wi-Fi to invalidate the active key; reinstalling clears the app's identity.

Control requests have bounded bodies and worker queues. Browser-origin requests, unexpected Host headers, and non-JSON commands are rejected to reduce cross-site/rebinding attacks. Video packets, frame dimensions, and shared-memory capacities are bounded. Slow consumers do not create an unbounded frame queue. None of these measures make a rooted phone or compromised PC trustworthy.

Do not attach private signing keys, pairing links, phone identifiers, or personal camera footage to an issue. Source and development binaries do not contain a production signing key. There is no telemetry/account service in the app.
