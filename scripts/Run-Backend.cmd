@echo off
title FilamentVision Backend - webiste2.0
cd /d "%~dp0.."
set "FILAMENTVISION_LOG=%~1"
if not defined FILAMENTVISION_LOG set "FILAMENTVISION_LOG=%~dp0..\logs\backend.log"
>>"%FILAMENTVISION_LOG%" echo [%date% %time%] command: gradlew.bat :backend:run --args="--host 127.0.0.1 --port 18080 --database build/launcher/phase2.db --snapshot-root build/launcher/snapshots"
call gradlew.bat :backend:run --args="--host 127.0.0.1 --port 18080 --database build/launcher/phase2.db --snapshot-root build/launcher/snapshots" >>"%FILAMENTVISION_LOG%" 2>&1
set "FILAMENTVISION_EXIT=%ERRORLEVEL%"
>>"%FILAMENTVISION_LOG%" echo [%date% %time%] backend command exited with %FILAMENTVISION_EXIT%
exit /b %FILAMENTVISION_EXIT%
