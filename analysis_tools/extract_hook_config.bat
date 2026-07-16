@echo off
setlocal enabledelayedexpansion

REM ====================================
REM WeChat Hook Config Extract Tool
REM ====================================

title WeChat Hook Config Extract

echo.
echo =========================================
echo    WeChat Hook Config Extract Tool
echo =========================================
echo.

REM ====================================
REM Check Arguments
REM ====================================

if "%~1"=="" (
    echo Error: Please provide APK file path
    echo.
    echo Usage: %~nx0 APK_PATH VERSION
    echo.
    echo Example:
    echo   %~nx0 "D:\project\wx\wx-8.0.76.apk" "8.0.76"
    echo.
    pause
    exit /b 1
)

set "APK_PATH=%~1"
set "VERSION=%~2"

if "%VERSION%"=="" (
    for /f "tokens=2 delims=-" %%a in ("%~n1") do set "VERSION=%%a"
    if "!VERSION!"=="" set "VERSION=unknown"
    echo Info: Version extracted from filename: !VERSION!
)

echo Version: %VERSION%
echo APK Path: %APK_PATH%
echo.

REM ====================================
REM Check Environment
REM ====================================

echo [1/4] Checking environment...

if not exist "%APK_PATH%" (
    echo Error: APK file not found: %APK_PATH%
    pause
    exit /b 1
)

set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
if not exist "%JAVA_HOME%\bin\java.exe" (
    set "JAVA_HOME=C:\Program Files\Java\jdk-17"
)
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo Error: Java not found
    pause
    exit /b 1
)

set "PATH=%JAVA_HOME%\bin;%PATH%"
echo OK: Java environment: %JAVA_HOME%

set "JADX_PATH=D:\project\wxcode\tools\jadx\bin\jadx.bat"
if not exist "%JADX_PATH%" (
    echo Error: jadx tool not found: %JADX_PATH%
    pause
    exit /b 1
)
echo OK: jadx tool: %JADX_PATH%

where python >nul 2>&1
if errorlevel 1 (
    echo Error: Python not found
    pause
    exit /b 1
)
echo OK: Python environment

echo.

REM ====================================
REM Decompiling APK
REM ====================================

echo [2/4] Decompiling APK...

REM Get script directory and project root
set "SCRIPT_DIR=%~dp0"
set "PROJECT_ROOT=%SCRIPT_DIR%.."
for %%i in ("%PROJECT_ROOT%") do set "PROJECT_ROOT=%%~fi"

set "OUTPUT_DIR=%PROJECT_ROOT%\decompiled\%VERSION%"

echo Output directory: %OUTPUT_DIR%

if exist "%OUTPUT_DIR%" (
    echo Removing old decompiled results for version %VERSION%...
    rmdir /s /q "%OUTPUT_DIR%"
)

echo Decompiling, please wait 5-10 minutes...
echo.

"%JADX_PATH%" -d "%OUTPUT_DIR%" --no-res --no-debug-info "%APK_PATH%"

if errorlevel 1 (
    echo.
    echo Warning: Decompilation has errors, but continuing...
)

if not exist "%OUTPUT_DIR%" (
    echo Error: Decompilation failed
    pause
    exit /b 1
)

echo.
echo OK: Decompilation complete
echo Output: %OUTPUT_DIR%
echo.

REM ====================================
REM Extract Hook Config
REM ====================================

echo [3/4] Extracting Hook Config...
echo.

REM Check if decompiled files exist
set "LOGIN_TASK=%OUTPUT_DIR%\sources\com\tencent\mm\plugin\appbrand\jsapi\auth\JsApiLogin$LoginTask.java"
if not exist "%LOGIN_TASK%" (
    echo Error: Core file not found: JsApiLogin$LoginTask.java
    echo.
    echo Please check if this is a WeChat APK.
    pause
    exit /b 1
)

echo Found core file: JsApiLogin$LoginTask.java
echo.

set "SCRIPT_PATH=%SCRIPT_DIR%analyze_hook_enhanced.py"

if exist "%SCRIPT_PATH%" (
    echo Running: python "%SCRIPT_PATH%" "%OUTPUT_DIR%" "%VERSION%" --verbose
    python "%SCRIPT_PATH%" "%OUTPUT_DIR%" "%VERSION%" --verbose
) else (
    set "SCRIPT_PATH=%SCRIPT_DIR%analyze_hook.py"
    if exist "%SCRIPT_PATH%" (
        echo Running: python "%SCRIPT_PATH%" "%OUTPUT_DIR%" "%VERSION%"
        python "%SCRIPT_PATH%" "%OUTPUT_DIR%" "%VERSION%"
    ) else (
        echo Error: Extract script not found
        echo Looking for: %SCRIPT_DIR%analyze_hook_enhanced.py
        echo Looking for: %SCRIPT_DIR%analyze_hook.py
        echo.
        echo Please analyze manually:
        echo File: %LOGIN_TASK%
        echo.
        echo Search for:
        echo   - j1: .d().f(
        echo   - c:  new XX.c(
        echo   - a1: new XX(this)
        echo   - a7: new XX(this,
    )
)

echo.

REM ====================================
REM Complete
REM ====================================

echo [4/4] Complete
echo.
echo =========================================
echo Done!
echo =========================================
echo.
echo Output: %OUTPUT_DIR%
echo.
echo Next Steps:
echo   1. Copy JSON config above
echo   2. Update WxLoginHook.java jsonString
echo   3. Run gradlew assembleDebug
echo   4. Install and test
echo.

if exist "%OUTPUT_DIR%\sources\com\tencent\mm\plugin\appbrand\jsapi\auth" (
    explorer "%OUTPUT_DIR%\sources\com\tencent\mm\plugin\appbrand\jsapi\auth"
) else (
    echo Warning: Auth directory not found, skipping explorer
)

pause
exit /b 0