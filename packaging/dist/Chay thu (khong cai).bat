@echo off
rem Chay Ark Studio ngay tu thu muc giai nen (khong cai, khong tao icon).
cd /d "%~dp0"
start "" "%~dp0runtime\bin\javaw.exe" -Dark.desktop=true -Dfile.encoding=UTF-8 -Xmx2g -jar "%~dp0app\byteplus-ark-backend.jar"
