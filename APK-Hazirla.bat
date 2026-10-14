@echo off
setlocal EnableExtensions EnableDelayedExpansion
title Makay Uzaktan Baglanti - APK Hazirlama + GitHub Yayin

REM ============================================================
REM  MakayRemote - tek adimli yayin hatti
REM
REM   1) Acik (commitlenmemis) degisiklikleri commit et
REM   2) VERSION.txt surumunu artir
REM   3) Release APK derle  -> APK\MakayRemote_vX.Y.Z.apk + .sha256
REM      (+ APK\MakayRemote_vX.Y.Z-mapping.txt  R8 obfuscation haritasi)
REM   4) Surum degisikligini commit et + depoya it (push)
REM   5) GitHub Release olustur + APK/SHA/mapping yukle (3 asset)
REM   6) Eski release'leri sil (yalnizca yeni surum kalir)
REM
REM  TEK APK NE ISE YARAR:
REM   MakayRemote_vX.Y.Z.apk - Play Protect engellemez, ayni Makay
REM     uygulamalari gibi kurulur. Ekran paylasimi, dosya, sohbet, pano,
REM     mikrofon/hoparlor VE Android -> Windows uzak kontrol dahil tum
REM     ozellikler calisir.
REM
REM   Windows -> Android kontrol YOKTUR ve bilincli olarak kaldirildi:
REM     bu yon yalnizca AccessibilityService ile mumkun (INJECT_EVENTS
REM     sistem imzasi gerektirir) ve Google Play Protect, yandan yukleme
REM     yapilan bu servisi bildiren uygulamalari KURULMASINA KADAR
REM     engelliyor - "uyari + Yine de kur" degil. Eko sistemin diger Makay
REM     uygulamalarinda accessibility servisi olmadigi icin onlar sorunsuz
REM     kurulur; bu uygulama engelleniyordu. Bu yon kaldirildi.
REM
REM  APK yalnizca %~dp0APK klasorunde birakilir; gradle
REM  publishReleaseApk gorevi diger tum kopyalari siler.
REM
REM  Gereksinim: git, gh (winget install GitHub.cli), gh auth login
REM  Guncel giris: Gh-Giris.bat
REM  Script ile calistirma: APK-Hazirla.bat /nopause
REM ============================================================

cd /d "%~dp0"
if errorlevel 1 (
    echo [HATA] Proje klasorune gidilemedi: %~dp0
    goto :END_FAIL
)

set "PROJECT_DIR=%CD%"
set "APK_DIR=%PROJECT_DIR%\APK"
set "GRADLEW=%PROJECT_DIR%\gradlew.bat"
set "BUMP_PS1=%PROJECT_DIR%\tools\bump-version.ps1"
set "READ_PS1=%PROJECT_DIR%\tools\read-version.ps1"
set "VERSION_FILE=%PROJECT_DIR%\VERSION.txt"
set "REPO=makaydestek/Makay-Uzak-Baglanti"
set "NOPAUSE=0"
if /i "%~1"=="/nopause" set "NOPAUSE=1"

REM GitHub CLI PATH'e ekle (yeni kurulumlar icin)
set "PATH=%ProgramFiles%\GitHub CLI;%LocalAppData%\Programs\GitHub CLI;%PATH%"

if not exist "%APK_DIR%" mkdir "%APK_DIR%"

echo.
echo ================================================
echo  Makay Uzaktan Baglanti - APK + GitHub yayini
echo ================================================
echo  Proje   : %PROJECT_DIR%
echo  Repo    : https://github.com/%REPO%/releases
echo.

REM --- Onceden tanimli surum (commit mesaji icin) ---
set "VERSION="
for /f "usebackq delims=" %%V in ("%VERSION_FILE%") do (
    if not defined VERSION set "VERSION=%%V"
)
if not defined VERSION (
    echo [HATA] VERSION.txt okunamadi.
    goto :END_FAIL
)

REM --- On kontroller ---
if not exist "%GRADLEW%" (
    echo [HATA] gradlew.bat yok: %GRADLEW%
    goto :END_FAIL
)
if not exist "%BUMP_PS1%" (
    echo [HATA] bump-version.ps1 yok: %BUMP_PS1%
    goto :END_FAIL
)
if not exist "%READ_PS1%" (
    echo [HATA] read-version.ps1 yok: %READ_PS1%
    goto :END_FAIL
)
where git >nul 2>&1
if errorlevel 1 (
    echo [HATA] git bulunamadi.  winget install Git.Git
    goto :END_FAIL
)
where gh >nul 2>&1
if errorlevel 1 (
    echo [HATA] GitHub CLI ^(gh^) bulunamadi.
    echo        winget install --id GitHub.cli
    goto :END_FAIL
)

REM JDK 17+ zorunlu (AGP 8.7 / Gradle 8.9).
REM Once mevcut JAVA_HOME, yoksa bilinen kurulum yerleri taranir.
set "JDK_FOUND="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JDK_FOUND=%JAVA_HOME%"
if not defined JDK_FOUND (
    for %%D in (
        "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
        "C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot"
        "C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot"
        "C:\Program Files\Microsoft\jdk-17.0.16.8-hotspot"
        "C:\Program Files\Android\Android Studio\jbr"
    ) do (
        if not defined JDK_FOUND if exist "%%~D\bin\java.exe" set "JDK_FOUND=%%~D"
    )
)
if not defined JDK_FOUND (
    echo [HATA] JDK 17+ bulunamadi.
    echo        JAVA_HOME tanimlayin veya Android Studio kurun.
    goto :END_FAIL
)
set "JAVA_HOME=!JDK_FOUND!"
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo  JAVA    : %JAVA_HOME%
echo  Surum   : !VERSION!
echo.

REM ============================================================
echo [1/6] Acik degisiklikler commit ediliyor...
REM ============================================================
git add -A >nul 2>&1
git diff --cached --quiet >nul 2>&1
if errorlevel 1 (
    git commit -m "MakayRemote v!VERSION!: birikimli guncelleme" >nul 2>&1
    if errorlevel 1 (
        echo [HATA] Commit basarisiz.
        git commit -m "MakayRemote v!VERSION!: birikimli guncelleme"
        goto :END_FAIL
    )
    for /f "delims=" %%H in ('git rev-parse --short HEAD 2^>nul') do set "C1=%%H"
    echo  [OK] !C1!  "MakayRemote v!VERSION!: birikimli guncelleme"
) else (
    echo  Atlandi - bekleyen degisiklik yok.
)
echo.

REM ============================================================
echo [2/6] Surum artiriliyor (VERSION.txt)...
REM ============================================================
set "OLD_VERSION="
set "VERSION="
for /f "usebackq tokens=1,2 delims=|" %%A in (`powershell -NoProfile -ExecutionPolicy Bypass -File "%BUMP_PS1%"`) do (
    set "OLD_VERSION=%%A"
    set "VERSION=%%B"
)
if not defined VERSION (
    echo [HATA] Surum artirilamadi.
    goto :END_FAIL
)
echo  Onceki : !OLD_VERSION!
echo  Yeni   : !VERSION!

REM Surum dosyasini dogrula (tek kaynak)
set "CHECK_VER="
set "CHECK_CODE="
for /f "usebackq tokens=1,2 delims=|" %%A in (`powershell -NoProfile -ExecutionPolicy Bypass -File "%READ_PS1%"`) do (
    set "CHECK_VER=%%A"
    set "CHECK_CODE=%%B"
)
if /I not "!CHECK_VER!"=="!VERSION!" (
    echo [HATA] VERSION.txt uyumsuz: dosya=!CHECK_VER! beklenen=!VERSION!
    goto :END_FAIL
)
echo  Kod    : !CHECK_CODE!  (versionCode)
echo.

REM ============================================================
echo [3/6] Release APK derleniyor + APK\ klasorune yayinlaniyor (v!VERSION!)...
REM ============================================================
echo  Bu islem birka dakika surebilir.
echo.
call "%GRADLEW%" --stop >nul 2>&1
call "%GRADLEW%" assembleRelease --no-daemon
set "ERR=!ERRORLEVEL!"
if not "!ERR!"=="0" (
    echo.
    echo [HATA] Derleme/yayin basarisiz. Kod: !ERR!
    goto :END_FAIL
)
echo.

REM ============================================================
echo [4/6] Teslimat dogrulaniyor...
REM ============================================================
set "APK_TARGET=%APK_DIR%\MakayRemote_v!VERSION!.apk"
set "SHA_TARGET=%APK_DIR%\MakayRemote_v!VERSION!.apk.sha256"
if not exist "!APK_TARGET!" (
    echo [HATA] Beklenen APK yok:
    echo  !APK_TARGET!
    goto :END_FAIL
)
if not exist "!SHA_TARGET!" (
    echo [UYARI] SHA dosyasi yok, olusturuluyor...
    powershell -NoProfile -ExecutionPolicy Bypass -Command ^
        "$h=(Get-FileHash -Algorithm SHA256 '!APK_TARGET!').Hash.ToLower();" ^
        "[IO.File]::WriteAllText('!SHA_TARGET!', ($h + '  MakayRemote_v!VERSION!.apk' + [Environment]::NewLine));" ^
        "Write-Output $h" >nul 2>&1
    if not exist "!SHA_TARGET!" (
        echo [HATA] SHA-256 olusturulamadi.
        goto :END_FAIL
    )
)
REM Proje kokunda / app\release altinda kopya birakma
if exist "%PROJECT_DIR%\*.apk" del /q "%PROJECT_DIR%\*.apk" >nul 2>&1
if exist "%PROJECT_DIR%\*.apk.sha256" del /q "%PROJECT_DIR%\*.apk.sha256" >nul 2>&1
if exist "%PROJECT_DIR%\app\release" (
    del /q "%PROJECT_DIR%\app\release\*.apk" >nul 2>&1
    del /q "%PROJECT_DIR%\app\release\*.apk.sha256" >nul 2>&1
)

for %%A in ("!APK_TARGET!") do (
    set "SIZE_BYTES=%%~zA"
    set "APK_DATE=%%~tA"
)
set /a SIZE_KB=!SIZE_BYTES! / 1024
echo  [OK] MakayRemote_v!VERSION!.apk  ^(!SIZE_KB! KB^)
echo  [OK] MakayRemote_v!VERSION!.apk.sha256
if exist "%APK_DIR%\MakayRemote_v!VERSION!-mapping.txt" (
    echo  [OK] MakayRemote_v!VERSION!-mapping.txt  ^(R8 haritasi - release asset^)
) else (
    echo  [UYARI] mapping.txt yok - R8 haritasi olusturulmamis olabilir.
)
echo.

REM ============================================================
echo [5/6] Depoya yayinlaniyor (git commit + push)...
REM ============================================================
git add "VERSION.txt" >nul 2>&1
git diff --cached --quiet -- "VERSION.txt" >nul 2>&1
if errorlevel 1 (
    git commit -m "Yayin: MakayRemote v!VERSION!" -- "VERSION.txt" >nul 2>&1
    if errorlevel 1 (
        echo [HATA] Surum commit'i basarisiz.
        git commit -m "Yayin: MakayRemote v!VERSION!" -- "VERSION.txt"
        goto :END_FAIL
    )
    for /f "delims=" %%H in ('git rev-parse --short HEAD 2^>nul') do set "C2=%%H"
    echo  [OK] !C2!  "Yayin: MakayRemote v!VERSION!"
) else (
    echo  Surum dosyasi zaten islenmis.
)
git push origin main >nul 2>&1
if errorlevel 1 (
    echo  [UYARI] Depoya itilemedi - manuel "git push" yapin.
) else (
    echo  [OK] origin/main guncellendi.
)
echo.

REM ============================================================
echo [6/6] GitHub Release olusturuluyor (tag = !VERSION!)...
REM ============================================================
gh auth status >nul 2>&1
if errorlevel 1 (
    echo.
    echo  ----------------------------------------
    echo   GitHub oturumu yok - giris basliyor.
    echo   KOD BU SIYAH PENCEREDE gorunecek.
    echo   Enter'a basin, tarayicida kodu yapistirin.
    echo  ----------------------------------------
    echo.
    if "!NOPAUSE!"=="1" (
        echo [HATA] /nopause modunda etkilesimli giris yapilamaz.
        echo        Once: Gh-Giris.bat
        goto :END_FAIL
    )
    pause >nul
    gh auth login -h github.com -p https -w -c -s "repo,read:org,gist,workflow"
    if errorlevel 1 (
        echo [HATA] gh auth login basarisiz. Alternatif: Gh-Giris.bat
        goto :END_FAIL
    )
    gh auth status >nul 2>&1
    if errorlevel 1 (
        echo [HATA] Hala oturum yok. Gh-Giris.bat ile tekrar deneyin.
        goto :END_FAIL
    )
    echo.
    echo  [OK] GitHub oturumu acildi.
    echo.
)

set "TAG=!VERSION!"
set "MAP=%APK_DIR%\MakayRemote_v!VERSION!-mapping.txt"

REM Var olan tum assetleri topla: APK + SHA-256 + R8 haritasi.
set "ASSETS="
if exist "!APK_TARGET!" set ASSETS=!ASSETS! "!APK_TARGET!"
if exist "!SHA_TARGET!" set ASSETS=!ASSETS! "!SHA_TARGET!"
if exist "!MAP!"        set ASSETS=!ASSETS! "!MAP!"

gh release view "!TAG!" --repo %REPO% >nul 2>&1
if errorlevel 1 (
    echo  Yeni release olusturuluyor...
    set "NOTES_FILE=%TEMP%\makay-remote-release-notes.txt"
    type nul > "!NOTES_FILE!"
    gh release create "!TAG!" !ASSETS! --repo %REPO% --title "!VERSION!" --notes-file "!NOTES_FILE!"
    set "REL_ERR=!ERRORLEVEL!"
    del /q "!NOTES_FILE!" >nul 2>&1
) else (
    echo  Release !TAG! mevcut - assetler guncelleniyor...
    gh release upload "!TAG!" !ASSETS! --repo %REPO% --clobber
    set "REL_ERR=!ERRORLEVEL!"
)
if not "!REL_ERR!"=="0" (
    echo [HATA] Release basarisiz. "gh auth status" ve yetkileri kontrol edin.
    goto :END_FAIL
)
echo  [OK] https://github.com/%REPO%/releases/tag/!TAG!
echo.

echo Eski GitHub surumleri siliniyor (yalnizca !TAG! kalir)...
set "TAGS_FILE=%TEMP%\makay-remote-tags.txt"
set "ALL_TAGS=%TEMP%\makay-all-tags.txt"

REM Once tum tag adlarini topla; sonra gereksiz release'leri sil;
REM en sonda release'siz kalan tag'leri temizle (etiket birikimini onle).
gh api "repos/%REPO%/tags" --paginate --jq ".[].name" > "!ALL_TAGS!" 2>nul

gh release list --repo %REPO% --json tagName -q ".[].tagName" > "!TAGS_FILE!" 2>nul
if exist "!TAGS_FILE!" (
    for /f "usebackq delims=" %%T in ("!TAGS_FILE!") do (
        if /i not "%%T"=="!TAG!" (
            gh release delete "%%T" --repo %REPO% --yes --cleanup-tag >nul 2>&1
            if errorlevel 1 (
                echo  [UYARI] release silinemedi: %%T
            ) else (
                echo  - release silindi: %%T
            )
        )
    )
    del /q "!TAGS_FILE!" >nul 2>&1
)

REM Release'siz kalan tag'ler artik yalnizca bunlar olabilir.
if exist "!ALL_TAGS!" (
    for /f "usebackq delims=" %%T in ("!ALL_TAGS!") do (
        if /i not "%%T"=="!TAG!" (
            gh api -X DELETE "repos/%REPO%/git/refs/tags/%%T" >nul 2>&1
            if errorlevel 1 (
                echo  [UYARI] tag silinemedi: %%T
            ) else (
                echo  - tag silindi: %%T
            )
        )
    )
)
echo.

if "!NOPAUSE!"=="0" start "" explorer "%APK_DIR%"

echo ================================================
echo  Tamamlandi - MakayRemote v!VERSION!
echo  Kurulum : APK\MakayRemote_v!VERSION!.apk  ^(sorunsuz kurulur^)
echo  Release : https://github.com/%REPO%/releases/tag/!VERSION!
echo ================================================
if "!NOPAUSE!"=="0" (
    echo.
    pause
)
endlocal
exit /b 0

:END_FAIL
echo.
echo ================================================
echo  Islem basarisiz - pencereyi kapatmak icin tus
echo ================================================
if not "!NOPAUSE!"=="1" pause
endlocal
exit /b 1
