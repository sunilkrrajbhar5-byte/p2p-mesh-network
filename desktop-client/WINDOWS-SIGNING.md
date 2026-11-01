# Windows signing and Smart App Control

Smart App Control (SAC) requires a certificate from a provider trusted by Microsoft.
A self-signed certificate imported into CurrentUser Root is useful for local
Authenticode testing, but is not a SAC trust fix. NSIS registration, a stable
AppUserModelID, Zone.Identifier removal and `npm start` do not guarantee SAC approval.
The previously published v1.0.3 executables remain unsigned; editing build scripts
cannot change files already downloaded by users. A new signed release is needed.

## Local development certificate (PowerShell, from desktop-client/)

```powershell
.\scripts\new-dev-certificate.ps1 -TrustCurrentUser
npm run build:dev
```

The first script requests a strong password, creates an RSA-3072/SHA-256 code-signing
certificate in CurrentUser\My, exports certs/dev-cert.pfx and its public .cer,
and imports ONLY the public certificate into CurrentUser\Root and TrustedPublisher.
It does not change LocalMachine trust and needs no machine-wide admin permission.
Omit -TrustCurrentUser if you only want to generate/export it. PFX/private keys and
certs/ are gitignored. Use only on your development account, not customer machines.
Keep the displayed thumbprint to remove this exact certificate later using certmgr.msc
from Personal, Trusted Root Certification Authorities and Trusted Publishers.
The script intentionally refuses to overwrite an existing development key.

build:dev requests the PFX password without echoing it, supplies CSC_LINK and
CSC_KEY_PASSWORD only for the build, restores the prior process environment afterward,
and writes to ../build/desktop-dev-signed (not the public downloads folder).
Dev outputs have a development signature. This DOES NOT promise SAC acceptance.

Equivalent environment setup for an already provisioned PFX:

```powershell
$env:CSC_LINK = (Resolve-Path .\certs\dev-cert.pfx).Path
# Supply CSC_KEY_PASSWORD through a secret manager/process environment.
# Do not put the password in package.json or a committed script.
npm run build
```

`certificatePassword: "env:CSC_KEY_PASSWORD"` is a literal password, not supported
variable interpolation. The pinned electron-builder 26 uses CSC_LINK/CSC_KEY_PASSWORD
(or WIN_CSC_LINK/WIN_CSC_KEY_PASSWORD). Its optional certificateFile/password settings
belong under win.signtoolOptions, not directly under win.

## Real public release signing

Obtain a provider-issued code-signing identity or configure Microsoft Artifact Signing
(formerly Trusted Signing). This requires the provider's identity verification and
credentials; the repository cannot mint a publicly trusted certificate for free.
Use a hardware/cloud signer when required by your certificate provider.

The signing.config.cjs file supports:
- PFX: CSC_LINK (path or base64), CSC_KEY_PASSWORD. Never use the development PFX for a public release.
- Artifact Signing: AZURE_SIGN_ENDPOINT, AZURE_SIGN_ACCOUNT, AZURE_SIGN_PROFILE,
  AZURE_SIGN_PUBLISHER; use AZURE_TENANT_ID, AZURE_CLIENT_ID and AZURE_CLIENT_SECRET
  for the supported service-principal authentication flow. Grant the identity only
  the signing permission on the intended profile using Azure's setup documentation.

For GitHub Actions, configure either WINDOWS_CSC_LINK and WINDOWS_CSC_KEY_PASSWORD
repository secrets, or the AZURE_SIGN_* repository variables plus AZURE_* identity
secrets above. Do not configure both providers simultaneously.

New builds fail if credentials are absent. CI checks Authenticode on the main app,
portable launcher and NSIS installer, and rejects self-signed release certificates.
A valid local signature is not proof that SAC accepts the publisher. Test the final
signed release on a SAC-enabled Windows 11 machine before asserting compatibility.

```powershell
npm ci
npm run build
Get-AuthenticodeSignature '..\docs\downloads\SR-Mesh-Windows-Setup-v1.0.3.exe' |
  Format-List Status,StatusMessage,SignerCertificate
```

The existing NSIS config provides per-user install, registry/uninstall metadata,
Desktop/Start shortcuts, and the stable AppUserModelID. NSIS is a normal Windows
installer; there is no relevant Electron renderer sandbox to turn off for it.
The app keeps nodeIntegration:false, contextIsolation:true, sandbox:true.

## Download-zone cleanup for reviewed local test files

From repository root (after verifying source and SHA-256):

```powershell
Unblock-File -Path .\docs\downloads\*.exe
```

Or use the scoped helper with a preview first:

```powershell
.\desktop-client\scripts\unblock-downloads.ps1 -WhatIf
.\desktop-client\scripts\unblock-downloads.ps1
```

For a different downloads folder: `Unblock-File -Path .\downloads\*.exe`.
These commands remove the Zone.Identifier stream only. They do not disable SAC,
add an SAC exclusion, establish publisher reputation, or make an unsafe file safe.

## Run from source during development

```powershell
Set-Location desktop-client
npm ci
npm start
```

This launches the dashboard with the installed Electron runtime and avoids packaging
an app EXE. SAC still evaluates executables and may block the runtime or dependencies;
this is a development workflow, not a security bypass. Do not add --no-sandbox.

## References
- https://learn.microsoft.com/en-us/windows/apps/develop/smart-app-control/code-signing-for-smart-app-control
- https://support.microsoft.com/en-us/windows/security/threat-malware-protection/smart-app-control-frequently-asked-questions
- https://www.electron.build/v26/docs/features/code-signing/
- https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.utility/unblock-file
