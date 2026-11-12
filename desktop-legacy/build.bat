@echo off
setlocal
cd /d "%~dp0"
where javac >nul 2>nul || (echo Khong tim thay javac. Cai JDK 17+ va them vao PATH. & exit /b 1)

rem Tim jar.exe: JAVA_HOME, PATH, hoac java.home cua JDK dang dung
set "JAR="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\jar.exe" set "JAR=%JAVA_HOME%\bin\jar.exe"
if not defined JAR for /f "delims=" %%i in ('where jar 2^>nul') do if not defined JAR set "JAR=%%i"
if not defined JAR for /f "tokens=2 delims==" %%i in ('java -XshowSettings:properties -version 2^>^&1 ^| findstr /c:"java.home"') do set "JH=%%i"
if not defined JAR if defined JH for /f "tokens=* delims= " %%j in ("%JH%") do set "JAR=%%j\bin\jar.exe"
if not exist "%JAR%" (echo Khong tim thay jar.exe. Dat JAVA_HOME tro toi JDK. & exit /b 1)

if exist build rmdir /s /q build
mkdir build\classes
if not exist dist mkdir dist
dir /s /b src\main\java\*.java > build\sources.txt
javac -encoding UTF-8 --release 17 -d build\classes @build\sources.txt || exit /b 1
"%JAR%" --create --file dist\byteplus-ark.jar --main-class ark.Main -C build\classes . || exit /b 1
echo Build OK: dist\byteplus-ark.jar
