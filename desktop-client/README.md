# SR Mesh Native Workspace

Build: `npm ci` then `npm run dist` in desktop-client/. The portable output is
`docs/downloads/SR-Mesh-Windows-Portable-v1.0.2.exe`.

The app bundles the existing docs/gateway.html. It runs no browser marketing page.
Select a discovered Android gateway, enter the gateway access key, then use chat
or file transfer. The phone gateway must be running and reachable on the same LAN.

Native Node HTTP/WebSocket transport uses an isolated preload bridge. Remote HTML
never receives Node or filesystem access. Gateway session cookies remain in the
main process. TLS verification is enabled. HTTP works locally but is unencrypted.
Downloads stream to a temporary file beside the location chosen in the native
Save dialog, then rename on successful completion. Cancelling uploads aborts the
native HTTP request. Portable binaries are unsigned (no Windows certificate supplied).

The workflow builds on Windows, verifies the packaged dashboard/preload, publishes
an immutable Release asset, and Pages copies it into downloads/. This avoids the
100 MiB Git file limit. The mobile docs/index.html is not edited.

Tests use real loopback HTTP/WebSocket servers. Physical Android interoperability
and Windows publisher reputation are not established by these automated tests.
