<#
    MAKAY Remote - uretim keystore'u olusturur (ilk kurulum)

    NEDEN BU BETIK VAR?
    - local.properties bu dosyaya isaret ediyor.
    - "keystore'u EZME" kuralini kod olarak zorlar. Uretim anahtari
      bilerek/sayesiz yeniden uretilirse TUM MEVCUT KURUMLAR guncellenemez
      hale gelir ve uygulama guvenligi kalici olarak bozulur.

    KULLANIM:
        powershell -ExecutionPolicy Bypass -File tools\keystore-olustur.ps1
        powershell -ExecutionPolicy Bypass -File tools\keystore-olustur.ps1 -Goster

    ONLEMLER:
    - Anahtar zaten varsa HICBIR SEY YAPMAZ (uretmez, uzerine yazmaz).
    - Sifre uretildiginde local.properties'e yazilir; ekrana YAZDIRILMAZ.
      Sifreyi gormek icin -Goster kullanin ve HEMEN guvenli bir yere yedekleyin.

    >> SIFREYI KAYBEDERSENIZ gelecek surumleri IMZALEYEMEZSINIZ. <<
    >> Bu dosya (.jks) ve local.properties birlikte yedeklenmelidir.     <<
#>
param(
    # Uretilen/okunan sifreyi ekrana yazdirir (yalnizca siz goreceksiniz)
    [switch]$Goster,
    [string]$Alias = "makay-remote",
    [int]$ValidityDays = 10000,   # ~27 yil (Play/App-signing icin yeterince uzun)
    [string]$DName = "CN=Makay Remote, OU=Remote, O=Makay, C=TR"
)

$ErrorActionPreference = 'Stop'

$root      = Split-Path -Parent $PSScriptRoot
$ksRelPath = "keystore/makay-remote-release.jks"
$ksPath    = Join-Path $root ($ksRelPath -replace '/', '\')
$lpPath    = Join-Path $root 'local.properties'
$ksDir     = Split-Path -Parent $ksPath

function Read-Props([string]$p) {
    $h = @{}
    if (Test-Path $p) {
        Get-Content $p | ForEach-Object {
            if ($_ -match '^\s*([^#=\s][^=]*)=(.*)$') {
                $h[$matches[1].Trim()] = $matches[2]
            }
        }
    }
    return $h
}

function Set-ReleaseProps([string]$p, [hashtable]$updates) {
    # local.properties'ı BAŞTAN YAZMAZ: mevcut satirlari yerinde gunceller ki
    # yorumlar (orada keystore betigi ipucu var) ve diger anahtarlar korunsun.
    $lines = @()
    if (Test-Path $p) { $lines = @(Get-Content $p) }
    foreach ($k in $updates.Keys) {
        $found = $false
        for ($i = 0; $i -lt $lines.Count; $i++) {
            if ($lines[$i] -match "^\s*$([regex]::Escape($k))\s*=") {
                $lines[$i] = "$k=$($updates[$k])"
                $found = $true
                break
            }
        }
        if (-not $found) { $lines += "$k=$($updates[$k])" }
    }
    [IO.File]::WriteAllLines($p, $lines, (New-Object Text.UTF8Encoding($false)))
}

<#
    keytool, JKS formati icin bir UYARI yazip stderr'e cikar. Betikte
    $ErrorActionPreference='Stop' oldugundan bu uyari PowerShell'i sonlandirir
    ve dogrulama adimi HIC CALISMAZ. Bu sarmalayici EAP'yi gecici olarak
    gevsetir, ciktisiz calistirir ve gercek donus kodunu dondurur.
#>
function Invoke-Keytool([string[]]$KeytoolArgs) {
    $eap = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $script:keytool @KeytoolArgs 2>&1 | ForEach-Object {
            if ($_ -is [System.Management.Automation.ErrorRecord]) {
                $msg = $_.Exception.Message
                # Uyariyi goster, hata olusturma
                if ($msg -match 'Warning:') { Write-Host "     (keytool) $msg" -ForegroundColor DarkGray }
            }
        }
        return $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $eap
    }
}

# ---- keytool ----
$keytool = $null
$candidates = @()
if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME 'bin\keytool.exe') }
$candidates += 'C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot\bin\keytool.exe'
$candidates += 'C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot\bin\keytool.exe'
$candidates += 'C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot\bin\keytool.exe'
$candidates += 'C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe'
foreach ($c in $candidates) { if (-not $keytool -and (Test-Path $c)) { $keytool = $c } }
if (-not $keytool) {
    Write-Host "[HATA] keytool bulunamadi. JAVA_HOME tanimlayin." -ForegroundColor Red
    exit 1
}

$props = Read-Props $lpPath
$existingStorePass = [string]$props['RELEASE_STORE_PASSWORD']
$existingKeyPass   = [string]$props['RELEASE_KEY_PASSWORD']
$existingAlias     = [string]$props['RELEASE_KEY_ALIAS']

# ============================================================
# DURUM 1: Anahtar zaten var -> EZME, sadece dogrula
# ============================================================
if (Test-Path $ksPath) {
    Write-Host "[OK] Anahtar zaten mevcut: $ksRelPath" -ForegroundColor Green
    Write-Host "     URETIM TEKRAR YAPILMADI (mevcut anahtar korundu)."

    if (-not $existingStorePass -or -not $existingKeyPass -or -not $existingAlias) {
        Write-Host ""
        Write-Host "[HATA] Anahtar dosyasi VAR ama local.properties'te sifre/alias yok." -ForegroundColor Red
        Write-Host "       Bu, sifreyi kaybettiginiz anlamina gelir; mevcut anahtari okuyamayiz."
        Write-Host "       COZUM: local.properties'i bir YEDEKTEN geri yukleyin."
        Write-Host "       Yedek yoksa anahtari elle silip yeniden uretebilirsiniz, ANCAK"
        Write-Host "       bu, onceden kurulan tum uygulamalari guncellenemez hale getirir."
        exit 1
    }

    # Anahtari sifreyle okuyabildigimizi dogrula (sifre yanlissa burada duser)
    $rc = Invoke-Keytool @('-list', '-keystore', $ksPath,
                           '-storepass', $existingStorePass,
                           '-alias', $existingAlias)
    if ($rc -ne 0) {
        Write-Host "[HATA] Anahtar okunamadi - sifre local.properties ile uyusmuyor." -ForegroundColor Red
        exit 1
    }
    Write-Host "     Sifre ile dogrulandi (alias: $existingAlias)." -ForegroundColor Green
    if ($Goster) {
        Write-Host ""
        Write-Host "  RELEASE_STORE_PASSWORD = $existingStorePass" -ForegroundColor Yellow
        Write-Host "  RELEASE_KEY_PASSWORD   = $existingKeyPass"   -ForegroundColor Yellow
        Write-Host "  RELEASE_KEY_ALIAS      = $existingAlias"      -ForegroundColor Yellow
    } else {
        Write-Host "     Sifre icin: local.properties  (veya bu betigi -Goster ile calistirin)"
    }
    exit 0
}

# ============================================================
# DURUM 2: Anahtar yok -> uret
# ============================================================
Write-Host "[*] Uretim anahtari olusturuluyor..." -ForegroundColor Cyan

# Guclu rastgele sifre (32 karakter, bulaniklasmasi zor, kacak karakter yok)
$alpha = 'abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789'
$bytes = New-Object byte[] 64
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($bytes)
$sb = New-Object Text.StringBuilder
for ($i = 0; $i -lt 32; $i++) { [void]$sb.Append($alpha[$bytes[$i] % $alpha.Length]) }
$pw = $sb.ToString()

if (-not (Test-Path $ksDir)) { New-Item -ItemType Directory -Path $ksDir -Force | Out-Null }

$rc = Invoke-Keytool @(
    '-genkeypair', '-v',
    '-keystore', $ksPath, '-storetype', 'JKS', '-alias', $Alias,
    '-keyalg', 'RSA', '-keysize', '2048', '-validity', "$ValidityDays",
    '-storepass', $pw, '-keypass', $pw,
    '-dname', $DName
)
if ($rc -ne 0 -or -not (Test-Path $ksPath)) {
    Write-Host "[HATA] keytool basarisiz (kod: $rc)." -ForegroundColor Red
    exit 1
}

# Olusan dosyanin git'e girmedigini dogrula
$gi = Join-Path $ksDir '.gitignore'
if (-not (Test-Path $gi)) {
    [IO.File]::WriteAllLines($gi, @('*', '!.gitignore'), (New-Object Text.UTF8Encoding($false)))
}

Set-ReleaseProps $lpPath @{
    'RELEASE_STORE_FILE'     = $ksRelPath
    'RELEASE_STORE_PASSWORD' = $pw
    'RELEASE_KEY_ALIAS'      = $Alias
    'RELEASE_KEY_PASSWORD'   = $pw
}

Write-Host ""
Write-Host "  [OK] $ksRelPath  (RSA 2048, gecerlilik $ValidityDays gun, alias: $Alias)" -ForegroundColor Green
Write-Host "  [OK] local.properties guncellendi" -ForegroundColor Green
Write-Host ""
Write-Host "  ============================================================" -ForegroundColor Yellow
Write-Host "   ONEMLI - SIMDI YEDEKLEYIN (sifre ekrana yazdirilmadi):"      -ForegroundColor Yellow
Write-Host "     1) local.properties  -> RELEASE_* satirlari"                -ForegroundColor Yellow
Write-Host "     2) keystore\makay-remote-release.jks"                      -ForegroundColor Yellow
Write-Host "   Bu iki dosya BIRLIKTE guvenli bir yerde (sifre yoneticisi)"   -ForegroundColor Yellow
Write-Host "   saklanmalidir. Sifre DEGISTIRILMEMELIDIR: her surumde ayni"   -ForegroundColor Yellow
Write-Host "   anahtarla imzalanir, aksi halde kullanici guncelleme alamaz." -ForegroundColor Yellow
Write-Host "  ============================================================" -ForegroundColor Yellow

if ($Goster) {
    Write-Host ""
    Write-Host "  SIFRE: $pw" -ForegroundColor Magenta
}
exit 0
