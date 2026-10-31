# Run from PowerShell after authorizing GitHub CLI and configuring signing secrets.
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath 'C:\Users\sunil\OneDrive\Documents\ChatGPT\P2P'
$gh = Join-Path $PWD '.tools\gh\bin\gh.exe'
if (-not (Test-Path -LiteralPath $gh)) { $gh = (Get-Command gh -ErrorAction Stop).Source }
function Assert-Success([string]$Action) {
    if ($LASTEXITCODE -ne 0) { throw "$Action failed; stopping without force-pushing or changing tags." }
}
& $gh auth status
Assert-Success 'GitHub authentication (run gh auth login --web --git-protocol https --scopes workflow first)'
$login = & $gh api user --jq .login
Assert-Success 'Read authenticated account'
if ($login -ne 'sunilkrrajbhar5-byte') { throw 'Sign in as sunilkrrajbhar5-byte before publishing.' }
& $gh auth setup-git
Assert-Success 'Configure Git credential helper'

git init
Assert-Success 'Initialize repository'
git config --local user.name 'sunilkrrajbhar5-byte'
git config --local user.email 'sunil.kr.rajbhar5@gmail.com'
$remotes = @(git remote)
if ($remotes -contains 'origin') {
    git remote set-url origin 'https://github.com/sunilkrrajbhar5-byte/p2p-mesh-network.git'
} else {
    git remote add origin 'https://github.com/sunilkrrajbhar5-byte/p2p-mesh-network.git'
}
Assert-Success 'Configure origin'
git branch -M main
Assert-Success 'Select main'
git add .
Assert-Success 'Stage source'
if (git status --porcelain) {
    git commit -m 'Prepare GitHub-native mesh distribution and admin PIN authentication'
    Assert-Success 'Commit source'
}

# The repository must exist and be public. Create it once with:
# gh repo create sunilkrrajbhar5-byte/p2p-mesh-network --public
$visibility = & $gh repo view sunilkrrajbhar5-byte/p2p-mesh-network --json visibility --jq .visibility
Assert-Success 'Read repository (create the public repository first if absent)'
if ($visibility -ne 'PUBLIC') { throw 'Public repository required for this GitHub Free Pages setup.' }
git push -u origin main
Assert-Success 'Push main (inspect/merge remote changes if rejected; never force-push)'

$pagesMethod = 'POST'
& $gh api repos/sunilkrrajbhar5-byte/p2p-mesh-network/pages --silent 2>$null
if ($LASTEXITCODE -eq 0) { $pagesMethod = 'PUT' }
& $gh api --method $pagesMethod repos/sunilkrrajbhar5-byte/p2p-mesh-network/pages `
    -f 'source[branch]=main' -f 'source[path]=/docs'
Assert-Success 'Enable Pages from main /docs'

$configured = @(& $gh secret list --repo sunilkrrajbhar5-byte/p2p-mesh-network --json name --jq '.[].name')
Assert-Success 'Read signing-secret names'
$required = @('MESH_KEYSTORE_BASE64','MESH_KEYSTORE_PASSWORD','MESH_KEY_ALIAS','MESH_KEY_PASSWORD')
$missing = @($required | Where-Object { $_ -notin $configured })
if ($missing.Count -gt 0) {
    throw ('Pages/main are published, but release signing secrets are missing: ' + ($missing -join ', ') + '. See GITHUB_SETUP.txt, section 3. Rerun after configuring them.')
}
git fetch origin --tags
Assert-Success 'Fetch existing release tags'
git show-ref --verify --quiet refs/tags/v1.0.0
if ($LASTEXITCODE -eq 0) { throw 'v1.0.0 already exists. Inspect its workflow or rerun via workflow_dispatch; do not move the tag.' }
git tag -a v1.0.0 -m 'Initial universal P2P mesh release'
Assert-Success 'Create release tag'
git push origin v1.0.0
Assert-Success 'Push release tag'
& $gh run list --repo sunilkrrajbhar5-byte/p2p-mesh-network --workflow build-release.yml --limit 5
Write-Host 'After the workflow succeeds, verify the release asset and Pages deployment.'
Write-Host 'Web: https://sunilkrrajbhar5-byte.github.io/p2p-mesh-network/'
Write-Host 'APK: https://github.com/sunilkrrajbhar5-byte/p2p-mesh-network/releases/latest/download/app-debug.apk'
Write-Host 'Config: https://raw.githubusercontent.com/sunilkrrajbhar5-byte/p2p-mesh-network/main/config.json'
