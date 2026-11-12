@echo off
rem Cai Ark Studio vao %LOCALAPPDATA%\Programs\Ark Studio, tao icon Desktop + Start Menu roi mo app.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-desktop.ps1" -Source "%~dp0." -Launch
if errorlevel 1 (echo. & echo Cai dat LOI - xem thong bao o tren & pause & exit /b 1)
