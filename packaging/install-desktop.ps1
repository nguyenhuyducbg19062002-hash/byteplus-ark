# Cài bản app đã đóng gói vào %LOCALAPPDATA%\Programs\Ark Studio (không cần quyền admin)
# và tạo lối tắt "Ark Studio" trên Desktop + Start Menu. -Launch: mở app sau khi cài.
# Dữ liệu (DB, log, cấu hình) ở %USERPROFILE%\.byteplus-ark – cài lại / cập nhật không mất.
# Dùng được cả từ repo (build-desktop.ps1 gọi) lẫn từ thư mục giải nén của file zip gửi đi ("Cai dat Ark Studio.bat").
param([Parameter(Mandatory = $true)][string]$Source, [switch]$Launch)
$ErrorActionPreference = 'Stop'
$appName = 'Ark Studio'
$target = Join-Path $env:LOCALAPPDATA "Programs\$appName"
$Source = [IO.Path]::GetFullPath($Source).TrimEnd('\')

if (-not (Test-Path (Join-Path $Source 'runtime\bin\javaw.exe')) -or -not (Test-Path (Join-Path $Source 'app\byteplus-ark-backend.jar'))) {
    throw "Thiếu file app trong $Source – hãy giải nén toàn bộ file zip rồi chạy lại (đừng chạy thẳng trong file zip)."
}

if ($Source -ne $target) {
    # App đang chạy (javaw của bản cài) → không ghi đè được
    $running = Get-CimInstance Win32_Process -Filter "Name='javaw.exe'" |
        Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith($target, [StringComparison]::OrdinalIgnoreCase) }
    if ($running) {
        throw "$appName đang chạy – thoát app (chuột phải biểu tượng ở khay hệ thống › Thoát) rồi chạy lại."
    }
    if (Test-Path $target) { Remove-Item $target -Recurse -Force }
    New-Item -ItemType Directory -Force (Split-Path $target) | Out-Null
    Copy-Item $Source $target -Recurse
}

$javaw = Join-Path $target 'runtime\bin\javaw.exe'
$javaArgs = '-Dark.desktop=true -Dfile.encoding=UTF-8 -Xmx2g -jar "' + (Join-Path $target 'app\byteplus-ark-backend.jar') + '"'
$shell = New-Object -ComObject WScript.Shell
$startMenu = Join-Path ([Environment]::GetFolderPath('Programs')) "$appName.lnk"
$desktop = Join-Path ([Environment]::GetFolderPath('Desktop')) "$appName.lnk"
foreach ($lnk in $startMenu, $desktop) {
    $s = $shell.CreateShortcut($lnk)
    $s.TargetPath = $javaw
    $s.Arguments = $javaArgs
    $s.WorkingDirectory = $target
    $s.IconLocation = (Join-Path $target 'ark.ico') + ',0'
    $s.Description = 'Ark Studio – tạo ảnh / video BytePlus'
    $s.Save()
}
Write-Host "Đã cài: $target" -ForegroundColor Green
Write-Host "Lối tắt: Desktop + Start Menu ($appName)"

if ($Launch) {
    Start-Process -FilePath $desktop
    Write-Host 'Đang mở Ark Studio…'
}
