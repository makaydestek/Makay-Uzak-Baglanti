@echo off
setlocal EnableExtensions
title Makay Uzaktan Baglanti - GitHub Release
cd /d "%~dp0"

REM PATH'e gh ekle (yeni kurulumlar icin)
set "PATH=%ProgramFiles%\GitHub CLI;%LocalAppData%\Programs\GitHub CLI;%PATH%"

echo.
echo ================================================
echo  Makay Uzaktan Baglanti - GitHub Release
echo  Repo: https://github.com/makaydestek/Makay-Uzak-Baglanti/releases
echo ================================================
echo.

set "VERSION_FILE=%CD%\VERSION.txt"
set "APK_DIR=%CD%\APK"

if not exist "%VERSION_FILE%" (
  echo [HATA] VERSION.txt yok
  goto :FAIL
)

for /f "usebackq delims=" %%V in ("%VERSION_FILE%") do set "VERSION=%%V"
if not defined VERSION (
  echo [HATA] VERSION.txt bos
  goto :FAIL
)

set "TAG=!VERSION!"
set "APK=%APK_DIR%\MakayRemote_v!VERSION!.apk"
set "SHA=%APK_DIR%\MakayRemote_v!VERSION!.apk.sha256"

if not exist "%APK%" (
  echo [HATA] APK yok: %APK%
  echo Once APK-Hazirla.bat calistirin. Teslim APK yalnizca APK\ klasorundedir.
  goto :FAIL
)

if not exist "%SHA%" (
  echo [UYARI] SHA dosyasi yok, olusturuluyor...
  powershell -NoProfile -Command ^
    "$h=(Get-FileHash -Algorithm SHA256 '%APK%').Hash.ToLower(); Set-Content -Path '%SHA%' -Value ($h + '  MakayRemote_v!VERSION!.apk') -Encoding ASCII"
  if not exist "%SHA%" (
    echo [HATA] SHA olusturulamadi
    goto :FAIL
  )
)

where gh >nul 2>&1
if errorlevel 1 (
  echo [HATA] GitHub CLI ^(gh^) bulunamadi.
  echo 1^) winget install --id GitHub.cli
  echo 2^) gh auth login
  goto :FAIL
)

gh auth status >nul 2>&1
if errorlevel 1 (
  echo [UYARI] GitHub oturumu yok.
  echo.
  echo  ----------------------------------------
  echo   KOD BU SIYAH PENCEREDE gorunecek.
  echo   Ayrica panoya ^(Ctrl+V^) kopyalanir.
  echo.
  echo   1^) Bu penceredeki XXXX-XXXX kodunu okuyun
  echo   2^) Tarayici acilinca kutuya yapistirin
  echo   3^) Authorize / Confirm
  echo  ----------------------------------------
  echo.
  echo  Enter'a basin - giris baslar...
  pause >nul
  echo.
  REM -w tarayici, -c kodu panoya kopyalar
  gh auth login -h github.com -p https -w -c -s "repo,read:org,gist,workflow"
  if errorlevel 1 (
    echo.
    echo [HATA] gh auth login basarisiz.
    echo Alternatif: Gh-Giris.bat dosyasini calistirin.
    goto :FAIL
  )
  gh auth status >nul 2>&1
  if errorlevel 1 (
    echo [HATA] Hala oturum yok. Gh-Giris.bat ile tekrar deneyin.
    goto :FAIL
  )
  echo.
  echo [OK] GitHub oturumu acildi.
  echo.
)

echo Surum : !VERSION!
echo Tag   : !TAG!
echo APK   : %APK%
echo SHA   : %SHA%
echo.

gh release view "!TAG!" --repo makaydestek/Makay-Uzak-Baglanti >nul 2>&1
if not errorlevel 1 (
  echo Release !TAG! mevcut - assetler guncelleniyor...
  gh release upload "!TAG!" "%APK%" "%SHA%" --repo makaydestek/Makay-Uzak-Baglanti --clobber
) else (
  echo Yeni release olusturuluyor...
  REM Baslik=surum, aciklama tamamen bos
  set "NOTES_FILE=%TEMP%\makay-remote-release-notes.txt"
  type nul > "!NOTES_FILE!"
  gh release create "!TAG!" "%APK%" "%SHA%" --repo makaydestek/Makay-Uzak-Baglanti --title "!VERSION!" --notes-file "!NOTES_FILE!"
  del /q "!NOTES_FILE!" >nul 2>&1
)

if errorlevel 1 (
  echo [HATA] Release basarisiz.
  goto :FAIL
)

echo.
echo [OK] https://github.com/makaydestek/Makay-Uzak-Baglanti/releases/tag/!TAG!
echo Kullanicilar uygulamayi acinca bildirim + diyalog gorur.
echo.
pause
endlocal
exit /b 0

:FAIL
echo.
echo ================================================
echo  Basarisiz - pencereyi kapatmak icin bir tus
echo ================================================
pause
endlocal
exit /b 1
