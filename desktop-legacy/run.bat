@echo off
setlocal
cd /d "%~dp0"
if not exist dist\byteplus-ark.jar call build.bat || exit /b 1
if "%~1"=="" (
  start "" javaw -Dfile.encoding=UTF-8 -jar dist\byteplus-ark.jar
) else (
  java -Dfile.encoding=UTF-8 -jar dist\byteplus-ark.jar %*
)
