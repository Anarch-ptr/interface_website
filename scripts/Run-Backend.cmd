@echo off
title FilamentVision Backend - webiste2.0
cd /d "%~dp0.."
call gradlew.bat :backend:run --args="--host 127.0.0.1 --port 18080 --database build/launcher/phase2.db --snapshot-root build/launcher/snapshots --scenario NORMAL"
