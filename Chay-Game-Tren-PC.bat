@echo off
chcp 65001 >nul
title Hiep Si Online 402 VIP ADMIN - Duy Truong (PC Auto Quest)
cd /d "%~dp0"

where java >nul 2>nul
if %errorlevel% neq 0 (
    echo [LOI] May tinh chua cai dat Java ^(JDK/JRE 17+^).
    echo Vui long tai va cai dat Java 17+ tai: https://adoptium.net/
    pause
    exit /b 1
)

if exist "HSO-AutoQuest-PC-Launcher.jar" (
    echo [PC] Dang khoi dong cua so Game HSO 402 VIP ADMIN + Auto Lam Nhiem Vu...
    start "" javaw -Dfile.encoding=UTF-8 -jar "HSO-AutoQuest-PC-Launcher.jar"
    exit /b 0
)

if exist "j2meweb\HSO-AutoQuest-PC-Launcher.jar" (
    cd /d "%~dp0\j2meweb"
    start "" javaw -Dfile.encoding=UTF-8 -jar "HSO-AutoQuest-PC-Launcher.jar"
    exit /b 0
)

echo [LOI] Khong tim thay file HSO-AutoQuest-PC-Launcher.jar!
pause
