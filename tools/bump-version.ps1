# MakayRemote surum artirici
# Tek kaynak: VERSION.txt  (ornek 1.0.0 -> 1.0.1)
# app/build.gradle.kts VERSION.txt okur; burada gradle duzenlenmez.
# Cikti (stdout): OLD|NEW

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$versionFile = Join-Path $root "VERSION.txt"

if (-not (Test-Path $versionFile)) {
    Set-Content -Path $versionFile -Value "1.0.0" -Encoding ASCII -NoNewline
}

$old = (Get-Content -Path $versionFile -Raw).Trim()
if ([string]::IsNullOrWhiteSpace($old)) { $old = "1.0.0" }

if ($old -notmatch '^(\d+)\.(\d+)\.(\d+)$') {
    Write-Error "Gecersiz surum formati: '$old' (beklenen: 1.0.0)"
    exit 1
}

$major = [int]$Matches[1]
$minor = [int]$Matches[2]
$patch = [int]$Matches[3] + 1

if ($patch -gt 99) {
    $patch = 0
    $minor++
}
if ($minor -gt 99) {
    $minor = 0
    $major++
}

$new = "$major.$minor.$patch"
Set-Content -Path $versionFile -Value $new -Encoding ASCII -NoNewline

Write-Output "$old|$new"
exit 0
