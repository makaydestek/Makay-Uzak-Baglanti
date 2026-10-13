@echo off
setlocal EnableExtensions
title Makay Uzaktan Baglanti - APK Hazirla

REM ============================================================
REM  KISA YOL - asil betik: APK-Hazirla.bat
REM  Cift tiklayinca pencere hemen kapanmasin diye pause var.
REM  Gecilen argumanlar (ornek /nopause) asil betige aktarilir.
REM ============================================================

cd /d "%~dp0"

if not exist "%~dp0APK-Hazirla.bat" (
    echo [HATA] Bulunamadi: %~dp0APK-Hazirla.bat
    echo.
    pause
    exit /b 1
)

REM Asil betikteki pause'u kapat, bekleme burada yapilir (cift pause olmasin).
if "%~1"=="" (
    call "%~dp0APK-Hazirla.bat" /nopause
) else (
    call "%~dp0APK-Hazirla.bat" %*
)
set "ERR=%ERRORLEVEL%"

if not "%ERR%"=="0" (
    echo.
    echo [Bilgi] Ana betik cikis kodu: %ERR%
)

echo.
pause
endlocal & exit /b %ERR%
