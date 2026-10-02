@echo off
setlocal EnableExtensions
cd /d "%~dp0"

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Start-Website.ps1"
set "FILAMENTVISION_EXIT=%ERRORLEVEL%"

if not "%FILAMENTVISION_EXIT%"=="0" (
    echo.
    echo FilamentVision failed to start. See the error above.
    if /I not "%~1"=="--hidden" pause
)

exit /b %FILAMENTVISION_EXIT%
