SR Mesh Desktop 1.0.2 (Windows x64)

npm ci
npm run build:win
Builds docs/downloads/SR-Mesh-Windows-Setup-v1.0.2.exe and portable equivalent.
Electron and electron-builder are pinned development dependencies; runtime has no npm dependency downloads.
The launcher is packaged locally; join Android host Wi-Fi, scan or enter a local gateway origin.
Detection probes at most 16 private endpoints with four workers and bounded timeout/response size.
It checks the existing Offline Mesh /login and /ws protocol, then opens the host UI in an isolated
sandboxed window. Fingerprinting is compatibility detection, not authentication. Enter the host key
in that window. No Node integration/preload is given to the gateway. Normal TLS validation remains on.
Public hosts, credentials in URLs and path/query injection are rejected by native IPC validation.
Chat/file HTTP and WebSocket transfers use the existing gateway client; the desktop is not a relay
or a Wi-Fi Direct group owner. Gateway service must be started separately on the Android host.
Voice requires a trusted HTTPS certificate and explicit microphone approval. No certificate bypass.

Installers are unsigned because no Windows code-signing certificate was provided. Windows may
show publisher/reputation warnings. Do not disable platform security protections.
The PWA caches only its launcher and icons; first installation/download requires internet.
Local gateway connectivity requires its Wi-Fi network, not public internet. Browser installation
still requires user approval, and its availability is controlled by Chrome/Edge.

Physical Android-to-Windows transfer and microphone interoperability require a running gateway.
Node tests validate discovery bounds, URL handling, timeout and protocol identification.
