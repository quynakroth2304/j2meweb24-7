@echo off
chcp 65001 >nul
title J2ME Web Cloud Emulator 24/7 - Duy Truong (PC Web Server)
cd /d "%~dp0"

if exist "j2meweb\server.js" (
    cd /d "%~dp0\j2meweb"
)

where node >nul 2>nul
if %errorlevel% neq 0 (
    echo [LOI] May tinh chua cai dat Node.js!
    echo Neu ban chi muon choi ngay khong can Node.js, hay chay file: Chay-Game-Tren-PC.bat hoac HSO-AutoQuest-PC-Launcher.jar
    pause
    exit /b 1
)

if not exist "node_modules" (
    echo [SETUP] Dang cai dat thu vien Web Server lan dau...
    call npm install
)

echo [SERVER] Dang khoi dong J2ME Web Cloud Server tai http://localhost:8080 ...
start "" "http://localhost:8080"
node server.js
pause
