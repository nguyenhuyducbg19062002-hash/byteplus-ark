# Đóng gói Ark Studio thành app desktop Windows (kèm sẵn Java, không cần cài gì thêm):
#   1. build giao diện (frontend\dist)  2. build backend jar (có giao diện bên trong)
#   3. jlink → Java thu gọn kèm app: build-desktop\Ark Studio\{runtime, app, ark.ico}
#   4. (mặc định) cài vào %LOCALAPPDATA%\Programs\Ark Studio + lối tắt "Ark Studio" trên Desktop / Start Menu
# Lối tắt chạy runtime\bin\javaw.exe (có chữ ký số Oracle) thay vì một file .exe tự build chưa ký – loại exe đó hay bị
# phần mềm diệt virus (Avast…) chặn / cách ly.
# Chạy: packaging\build-desktop.bat   (thêm -NoInstall để chỉ đóng gói)
param([switch]$NoInstall)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$appName = 'Ark Studio'

function Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }

# ---- tìm JDK có jlink ----
$javaHome = $env:JAVA_HOME
if (-not $javaHome -or -not (Test-Path (Join-Path $javaHome 'bin\jlink.exe'))) {
    $line = (cmd /c 'java -XshowSettings:properties -version 2>&1') | Where-Object { $_ -match 'java\.home' } | Select-Object -First 1
    if ($line) { $javaHome = ($line -split '=', 2)[1].Trim() }
}
$jlink = Join-Path $javaHome 'bin\jlink.exe'
if (-not (Test-Path $jlink)) { throw "Không tìm thấy jlink.exe (cần JDK 17+). Đặt JAVA_HOME trỏ tới JDK." }
Write-Host "JDK: $javaHome"

Step 'Build giao diện (Vue)'
Push-Location (Join-Path $root 'frontend')
try {
    if (-not (Test-Path node_modules)) { npm install; if ($LASTEXITCODE) { throw 'npm install lỗi' } }
    npm run build; if ($LASTEXITCODE) { throw 'npm run build lỗi' }
} finally { Pop-Location }

Step 'Build backend (Spring Boot jar kèm giao diện)'
Push-Location (Join-Path $root 'backend')
try {
    & .\mvnw.cmd -q -DskipTests package; if ($LASTEXITCODE) { throw 'mvnw package lỗi' }
} finally { Pop-Location }

Step 'Tạo Java thu gọn (jlink) + gói app'
$out = Join-Path $root 'build-desktop'
$stage = Join-Path $out $appName
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force (Join-Path $stage 'app') | Out-Null
& $jlink --add-modules 'java.se,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.unsupported,jdk.zipfs,jdk.charsets,jdk.localedata,jdk.management,jdk.naming.dns,jdk.net,jdk.accessibility' `
    --output (Join-Path $stage 'runtime') --strip-debug --no-header-files --no-man-pages --compress=2
if ($LASTEXITCODE) { throw 'jlink lỗi' }
Copy-Item (Join-Path $root 'backend\target\byteplus-ark-backend.jar') (Join-Path $stage 'app')
Copy-Item (Join-Path $PSScriptRoot 'ark.ico') $stage
Write-Host "Đã tạo: $stage" -ForegroundColor Green

if ($NoInstall) { return }

Step 'Cài vào máy'
& (Join-Path $PSScriptRoot 'install-desktop.ps1') -Source $stage
