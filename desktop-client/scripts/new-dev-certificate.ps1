[CmdletBinding()]
param([switch]$TrustCurrentUser, [Security.SecureString]$Password)
$ErrorActionPreference = 'Stop'
$certDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../certs'))
New-Item -ItemType Directory -Path $certDirectory -Force | Out-Null
$pfxPath = Join-Path $certDirectory 'dev-cert.pfx'
$cerPath = Join-Path $certDirectory 'dev-cert.cer'
if (Test-Path -LiteralPath $pfxPath) { throw 'dev-cert.pfx already exists. Reuse it; this script will not replace a signing identity.' }
if (-not $Password) { $Password = Read-Host 'Choose a strong development PFX password' -AsSecureString }
if ($Password.Length -lt 16) { throw 'Use a password of at least 16 characters.' }
$certificate = New-SelfSignedCertificate -Type CodeSigningCert -Subject 'CN=SR MESH Development Only' -FriendlyName 'SR MESH Development Only' -CertStoreLocation 'Cert:\CurrentUser\My' -KeyAlgorithm RSA -KeyLength 3072 -HashAlgorithm SHA256 -KeyUsage DigitalSignature -KeyExportPolicy Exportable -NotAfter (Get-Date).AddYears(1)
Export-PfxCertificate -Cert $certificate -FilePath $pfxPath -Password $Password -ChainOption EndEntityCertOnly | Out-Null
Export-Certificate -Cert $certificate -FilePath $cerPath | Out-Null
if ($TrustCurrentUser) {
 Import-Certificate -FilePath $cerPath -CertStoreLocation 'Cert:\CurrentUser\Root' | Out-Null
 Import-Certificate -FilePath $cerPath -CertStoreLocation 'Cert:\CurrentUser\TrustedPublisher' | Out-Null
}
Write-Host "Development certificate created: $pfxPath"
Write-Host "Thumbprint (save for later removal): $($certificate.Thumbprint)"
Write-Warning 'Local Authenticode testing only. This certificate does NOT satisfy Smart App Control trusted-provider requirements. Do not distribute the private PFX or install this trust on customers computers.'
