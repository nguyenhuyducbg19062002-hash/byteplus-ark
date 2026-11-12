@echo off
cd /d "%~dp0"
start "Ark backend" cmd /k start-backend.bat
start "Ark frontend" cmd /k start-frontend.bat
echo Doi backend khoi dong...
timeout /t 15 /nobreak >nul
start http://localhost:5173
