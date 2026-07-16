@echo off
setlocal enabledelayedexpansion

REM ====================================
REM Extract Hook Config Only (No Decompilation)
REM ====================================

title Extract Hook Config

echo.
echo =========================================
echo    Extract Hook Config Only
echo =========================================
echo.

REM ====================================
REM Check Arguments
REM ====================================

if "%~1"=="" (
    echo Error: Please provide decompiled directory or version
    echo.
    echo Usage: %~nx0 VERSION
    echo.
    echo Example:
    echo   %~nx0 8.0.76
    echo.
    pause
    exit /b 1
)

set "VERSION=%~1"

REM Get script directory and project root
set "SCRIPT_DIR=%~dp0"
set "PROJECT_ROOT=%SCRIPT_DIR%.."
for %%i in ("%PROJECT_ROOT%") do set "PROJECT_ROOT=%%~fi"

set "OUTPUT_DIR=%PROJECT_ROOT%\decompiled\%VERSION%"

if not exist "%OUTPUT_DIR%" (
    echo Error: Decompiled directory not found: %OUTPUT_DIR%
    echo.
    echo Please run extract_hook_config.bat first to decompile the APK.
    pause
    exit /b 1
)

echo Version: %VERSION%
echo Decompiled directory: %OUTPUT_DIR%
echo.

REM ====================================
REM Extract Hook Config
REM ====================================

echo [1/2] Checking decompiled files...

set "LOGIN_TASK=%OUTPUT_DIR%\sources\com\tencent\mm\plugin\appbrand\jsapi\auth\JsApiLogin$LoginTask.java"
if not exist "%LOGIN_TASK%" (
    echo Error: Core file not found: JsApiLogin$LoginTask.java
    echo.
    echo Please check if this is a WeChat APK.
    pause
    exit /b 1
)

echo OK: Found core file
echo.

echo [2/2] Extracting Hook Config...
echo.

set "SCRIPT_PATH=%SCRIPT_DIR%analyze_hook_enhanced.py"

if exist "%SCRIPT_PATH%" (
    python "%SCRIPT_PATH%" "%OUTPUT_DIR%" "%VERSION%" --verbose
) else (
    set "SCRIPT_PATH=%SCRIPT_DIR%analyze_hook.py"
    if exist "%SCRIPT_PATH%" (
        python "%SCRIPT_PATH%" "%OUTPUT_DIR%" "%VERSION%"
    ) else (
        echo Error: Extract script not found
        pause
        exit /b 1
    )
)

echo.
echo =========================================
echo Done!
echo =========================================
echo.

pause