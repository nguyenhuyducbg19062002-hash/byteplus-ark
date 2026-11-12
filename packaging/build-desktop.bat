@echo off
rem Dong goi + cai Ark Studio thanh app desktop (Ark Studio.exe). Them -NoInstall de chi dong goi.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-desktop.ps1" %*
if errorlevel 1 (echo. & echo LOI - xem thong bao o tren & pause & exit /b 1)
echo.
echo Xong. Mo "Ark Studio" tren Desktop / Start Menu.
pause
