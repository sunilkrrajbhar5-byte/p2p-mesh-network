[CmdletBinding()]
param([Security.SecureString]$Password)
$ErrorActionPreference = 'Stop'
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$pfxPath = Join-Path $project 'certs/dev-cert.pfx'
if (-not (Test-Path -LiteralPath $pfxPath)) { throw 'Run scripts/new-dev-certificate.ps1 first.' }
if (-not $Password) { $Password = Read-Host 'Development PFX password' -AsSecureString }
$names = @('CSC_LINK','CSC_KEY_PASSWORD','WIN_CSC_LINK','WIN_CSC_KEY_PASSWORD','AZURE_SIGN_ENDPOINT','AZURE_SIGN_ACCOUNT','AZURE_SIGN_PROFILE','AZURE_SIGN_PUBLISHER')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name,'Process'); [Environment]::SetEnvironmentVariable($name,$null,'Process') }
$pointer = [IntPtr]::Zero
Push-Location $project
try {
 $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Password)
 $env:CSC_LINK = $pfxPath
 $env:CSC_KEY_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
 # Dev signatures never overwrite public download artifacts.
 npm.cmd run build -- --config.directories.output=../build/desktop-dev-signed
 if ($LASTEXITCODE -ne 0) { throw 'Development signed build failed.' }
} finally {
 foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
 if ($pointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
 Pop-Location
}
