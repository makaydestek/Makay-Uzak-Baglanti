@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem  MakayCleaner Gradle Wrapper
@rem  - Argumansiz: assembleRelease
@rem  - Argumanli: verilen Gradle gorevleri (clean, assembleRelease, vb.)
@rem ##########################################################################

if "%OS%"=="Windows_NT" setlocal
set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

@rem Wrapper JVM (sadece baslatici). Asil bellek gradle.properties org.gradle.jvmargs
set DEFAULT_JVM_OPTS="-Xmx256m" "-Xms64m"

if defined JAVA_HOME goto findJavaFromJavaHome
set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute
echo.
echo [HATA] JAVA_HOME ayarli degil ve PATH'te java yok.
echo        JDK 17+ kurup JAVA_HOME tanimlayin.
echo.
goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe
if exist "%JAVA_EXE%" goto execute
echo.
echo [HATA] JAVA_HOME gecersiz: %JAVA_HOME%
echo.
goto fail

:execute
set CLASSPATH=
set WRAPPER_JAR=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar
if not exist "%WRAPPER_JAR%" (
    echo [HATA] gradle-wrapper.jar bulunamadi: %WRAPPER_JAR%
    goto fail
)

if "%~1"=="" (
    echo.
    echo [MakayCleaner] Arguman yok - assembleRelease calistiriliyor...
    echo.
    "%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%CLASSPATH%" -jar "%WRAPPER_JAR%" assembleRelease
) else (
    "%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%CLASSPATH%" -jar "%WRAPPER_JAR%" %*
)

goto end

:fail
set EXIT_CODE=%ERRORLEVEL%
if %EXIT_CODE% equ 0 set EXIT_CODE=1
exit /b %EXIT_CODE%

:end
if %ERRORLEVEL% neq 0 goto fail
if "%OS%"=="Windows_NT" endlocal
exit /b 0
