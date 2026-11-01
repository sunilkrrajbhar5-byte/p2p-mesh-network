[CmdletBinding()]
param(
    [string]$SdkRoot,
    [string]$JavaHome,
    [switch]$AcceptLicenses,
    [switch]$RunTests
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not $SdkRoot) {
    $candidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android/Sdk'), (Join-Path $projectRoot '.tools/android-sdk'))
    $SdkRoot = $candidates | Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Container) } | Select-Object -First 1
}
if (-not $SdkRoot) { throw 'No Android SDK found. Install official Android command-line tools first.' }
$SdkRoot = (Resolve-Path -LiteralPath $SdkRoot).Path
if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
if (-not $JavaHome) { $JavaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User') }
if (-not $JavaHome) { throw 'Pass -JavaHome with a verified JDK 17+ directory.' }
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/javac.exe'))) { throw 'JavaHome must contain a complete JDK.' }
$env:JAVA_HOME = (Resolve-Path -LiteralPath $JavaHome).Path
$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot
# Restrict cache/environment overrides to this PowerShell process.
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.tools/gradle-home'
$env:SDKMANAGER_OPTS = '-Djava.net.preferIPv4Stack=true -Djava.net.preferIPv6Addresses=false'
$sdkManager = Join-Path $SdkRoot 'cmdline-tools/latest/bin/sdkmanager.bat'
if (-not (Test-Path -LiteralPath $sdkManager)) { throw "Missing command-line tools: $sdkManager" }
$logs = Join-Path $projectRoot 'build/local-environment'
New-Item -ItemType Directory -Force $logs | Out-Null
if ($AcceptLicenses) {
    # Caller explicitly authorizes accepting Google's displayed SDK licenses.
    (1..100 | ForEach-Object { 'y' }) | & $sdkManager "--sdk_root=$SdkRoot" --licenses *> (Join-Path $logs 'licenses.log')
    if ($LASTEXITCODE -ne 0) { throw "License acceptance failed; inspect $logs/licenses.log" }
    # New Android CLI shims treat --licenses as a no-op. AGP 8.x still checks
    # the SDK license records for these published Android SDK license revisions.
    $licenseDir = Join-Path $SdkRoot 'licenses'
    New-Item -ItemType Directory -Force $licenseDir | Out-Null
    $licenseFile = Join-Path $licenseDir 'android-sdk-license'
    $accepted = if (Test-Path -LiteralPath $licenseFile) { @(Get-Content -LiteralPath $licenseFile) } else { @() }
    $accepted += @('24333f8a63b6825ea9c5514f83c2829b004d1fee', 'd56f5187479451eabf01fb78af6dfcb131a6481e', '8933bad161af4178b1185d1a37fbf41ea5269c55')
    [IO.File]::WriteAllLines($licenseFile, @($accepted | Select-Object -Unique), [Text.UTF8Encoding]::new($false))
}
$missingPackages = @()
if (-not (Test-Path -LiteralPath (Join-Path $SdkRoot 'platforms/android-35/android.jar'))) { $missingPackages += 'platforms;android-35' }
if (-not (Test-Path -LiteralPath (Join-Path $SdkRoot 'build-tools/35.0.0/aapt2.exe'))) { $missingPackages += 'build-tools;35.0.0' }
if ($missingPackages.Count -gt 0) {
    & $sdkManager "--sdk_root=$SdkRoot" @missingPackages *> (Join-Path $logs 'install.log')
    if ($LASTEXITCODE -ne 0) { throw "SDK installation failed; inspect $logs/install.log" }
}
foreach ($required in @('platforms/android-35/android.jar','build-tools/35.0.0/aapt2.exe','licenses/android-sdk-license')) {
    if (-not (Test-Path -LiteralPath (Join-Path $SdkRoot $required))) { throw "SDK verification failed: $required" }
}
$properties = Join-Path $projectRoot 'local.properties'
$lines = if (Test-Path -LiteralPath $properties) { @(Get-Content -LiteralPath $properties | Where-Object { $_ -notmatch '^\s*sdk\.dir\s*=' }) } else { @() }
$escaped = $SdkRoot.Replace('\','\\').Replace(':','\:')
[IO.File]::WriteAllLines($properties, @($lines) + "sdk.dir=$escaped", [Text.UTF8Encoding]::new($false))
Write-Output "SDK verified: $SdkRoot"
if ($RunTests) {
    Push-Location $projectRoot
    try {
        & .\gradlew.bat testDebugUnitTest --continue --console=plain *> (Join-Path $logs 'gradle-tests.log')
        $testExit = $LASTEXITCODE
        Get-Content (Join-Path $logs 'gradle-tests.log') -Tail 45
        if ($testExit -ne 0) { throw "Local Gradle tests failed (exit $testExit)" }
    } finally { Pop-Location }
}
