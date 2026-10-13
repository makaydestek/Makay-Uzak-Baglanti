# MakayRemote - VERSION.txt ile build.gradle senkron kontrolu
# Cikti: VERSION|CODE   ornek: 1.0.1|1000001
# Not: code formul app/build.gradle.kts ile AYNI olmak zorunda:
#      major * 1_000_000 + minor * 1_000 + patch

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$versionFile = Join-Path $root "VERSION.txt"

if (-not (Test-Path $versionFile)) {
    Write-Error "VERSION.txt yok"
    exit 1
}

$v = (Get-Content -Path $versionFile -Raw).Trim()
if ($v -notmatch '^(\d+)\.(\d+)\.(\d+)$') {
    Write-Error "Gecersiz surum: '$v'"
    exit 1
}

$code = ([int]$Matches[1] * 1000000) + ([int]$Matches[2] * 1000) + [int]$Matches[3]
Write-Output "$v|$code"
exit 0
