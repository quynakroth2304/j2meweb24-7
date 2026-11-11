@echo off
chcp 65001 >nul
title J2ME Cloud Server 24/7
echo ===================================================
echo   KHOI DONG J2ME CLOUD SERVER 24/7 (PORT 3000)
echo ===================================================
if not exist node_modules (
    echo [INFO] Dang cai dat thu vien Node.js...
    call npm install
)
set JWT_SECRET=j2me_cloud_windows_secret_key_2026
echo [INFO] Mo trinh duyet tai: http://localhost:3000
node server.js
pause
