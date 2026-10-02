@echo off
title FilamentVision Frontend - webiste2.0
cd /d "%~dp0..\frontend"
set "FILAMENTVISION_BACKEND_URL=http://127.0.0.1:18080"
call npm run dev -- --host 127.0.0.1 --port 5173 --strictPort
