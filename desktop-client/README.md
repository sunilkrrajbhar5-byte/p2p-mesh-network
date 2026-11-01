# SR Mesh Native Workspace

Build: `npm ci` then `npm run dist` in desktop-client/. The portable output is
`docs/downloads/SR-Mesh-Windows-Portable-v1.0.3.exe`.

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


## Windows icons and clean rebuild

`assets/icon.ico` contains 16, 32, 48, 64, 128 and 256 pixel square RGBA frames.
The source is the existing SR Mesh artwork in icon.png. Do not rename a PNG to ICO.
The window and electron-builder both reference assets/icon.ico, which is included
in app.asar. The existing appId is retained and set as Windows AppUserModelID.
The visible product and shortcut name is SR MESH Desktop. Its existing profile
folder is preserved so the branding update does not reset the user's identity.

`signAndEditExecutable: true` enables embedding the icon and version metadata.
`signExecutable: false` skips signing only; no signing certificate is configured.
NSIS installer, uninstaller and wizard header use the same ICO. NSIS creates
Desktop and Start Menu shortcuts. Prefer installing NSIS for stable taskbar pins;
a portable executable extracts its application to a temporary location.

From the repository root in PowerShell:

```powershell
Set-Location desktop-client
npm ci
npm run build
```

For a clean output location without deleting prior published downloads:

```powershell
npm run build -- --config.directories.output=../build/icon-rebuild-v1.0.3
```

This builds x64 NSIS and portable executables. Use a new empty output directory
when repeating a fully clean build. CI additionally compares the embedded PE icon
resources of the application, portable launcher and installer against the source ICO.

## If Windows still shows a cached old icon

1. Close SR Mesh. Unpin the old taskbar shortcut. Install the new NSIS build, open
   SR MESH Desktop from Start, and pin that new shortcut. Delete obsolete shortcuts.
2. If needed, rebuild the Windows icon cache using Microsoft's procedure below.
   Save open work first; restarting Explorer temporarily hides the taskbar/desktop.
3. Open Task Manager (Ctrl+Shift+Esc), select Windows Explorer, and End task.
4. In Task Manager choose Run new task, open cmd.exe as administrator and run:

```bat
cd /d "%LOCALAPPDATA%"
del /a IconCache.db
```

5. In Task Manager choose Run new task again and run explorer.exe. If icons remain
   stale, sign out and sign back in or restart Windows. Cache cleanup cannot fix
   a shortcut still pointing to an old executable: recreate that shortcut first.

References:
- https://www.electron.build/v26/docs/win/
- https://learn.microsoft.com/en-us/troubleshoot/windows-client/shell-experience/application-shortcuts-show-blank-icons
