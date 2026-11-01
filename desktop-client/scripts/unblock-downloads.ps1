[CmdletBinding(SupportsShouldProcess)]
param([string]$Folder = (Join-Path $PSScriptRoot '../../docs/downloads'))
$ErrorActionPreference='Stop'
$directory = Get-Item -LiteralPath $Folder
if (-not $directory.PSIsContainer) { throw 'Specify a downloads directory.' }
Write-Warning 'Removes Zone.Identifier only. This does not disable or bypass Smart App Control. Review the files and checksums first.'
Get-ChildItem -LiteralPath $directory.FullName -Filter '*.exe' -File | ForEach-Object {
 if ($PSCmdlet.ShouldProcess($_.FullName,'Remove download Zone.Identifier')) { Unblock-File -LiteralPath $_.FullName }
}
