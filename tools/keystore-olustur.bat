@echo off
setlocal EnableExtensions
title MAKAY Remote - Uretim anahtari (keystore)

REM ============================================================
REM  local.properties'in isaret ettigi betik.
REM  Mevcut anahtari ASLA uzerine yazmaz; sadece ilk kez uretir.
REM
REM  Goster:  keystore-olustur.bat /goster
REM ============================================================

cd /d "%~dp0\.."
set "PS1=%~dp0keystore-olustur.ps1"

if not exist "%PS1%" (
    echo [HATA] Bulunamadi: %PS1%
    goto :FAIL
)

set "ARGS=-ExecutionPolicy Bypass -NoProfile -File "%PS1%""
if /i "%~1"=="/goster" set "ARGS=%ARGS% -Goster"
if /i "%~1"=="-goster" set "ARGS=%ARGS% -Goster"

powershell %ARGS%
set "ERR=%ERRORLEVEL%"
if not "%ERR%"=="0" (
    echo.
    echo [HATA] Islem basarisiz. Cikis kodu: %ERR%
    goto :FAIL
)

echo.
echo Tamamlandi.
if /i not "%~1"=="/goster" if /i not "%~1"=="-goster" (
    echo Sifreyi gormek icin: keystore-olustur.bat /goster
)
echo.
pause
endlocal & exit /b 0

:FAIL
echo.
pause
endlocal & exit /b 1
