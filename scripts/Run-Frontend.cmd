@echo off
title FilamentVision Frontend - webiste2.0
cd /d "%~dp0..\frontend"
set "FILAMENTVISION_BACKEND_URL=http://127.0.0.1:18080"
set "FILAMENTVISION_LOG=%~1"
if not defined FILAMENTVISION_LOG set "FILAMENTVISION_LOG=%~dp0..\logs\frontend.log"
>>"%FILAMENTVISION_LOG%" echo [%date% %time%] command: npm.cmd run dev -- --host 127.0.0.1 --port 5173 --strictPort
call npm.cmd run dev -- --host 127.0.0.1 --port 5173 --strictPort >>"%FILAMENTVISION_LOG%" 2>&1
set "FILAMENTVISION_EXIT=%ERRORLEVEL%"
>>"%FILAMENTVISION_LOG%" echo [%date% %time%] frontend command exited with %FILAMENTVISION_EXIT%
exit /b %FILAMENTVISION_EXIT%
