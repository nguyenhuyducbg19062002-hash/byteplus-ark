@echo off
rem Frontend Vue (Vite dev server) - http://localhost:5173 (proxy /api -> 8080)
cd /d "%~dp0frontend"
if not exist node_modules call npm install
call npm run dev
