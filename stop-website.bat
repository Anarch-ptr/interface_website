@echo off
setlocal EnableExtensions
cd /d "%~dp0"

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Stop-Website.ps1"
set "FILAMENTVISION_EXIT=%ERRORLEVEL%"

if not "%FILAMENTVISION_EXIT%"=="0" (
    echo.
    echo One or more FilamentVision processes could not be stopped safely.
    pause
)

exit /b %FILAMENTVISION_EXIT%
