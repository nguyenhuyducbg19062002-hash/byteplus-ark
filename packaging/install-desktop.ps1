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

# Mục "Ark Studio" trong Cài đặt › Ứng dụng → Gỡ cài đặt chạy uninstall-desktop.ps1 (xoá cả lối tắt).
# Cùng khoá với trình cài Inno Setup để không hiện hai mục; cài bằng script đã xoá bộ gỡ cũ của Inno (unins000.exe) nếu có.
$uninstall = Join-Path $target 'uninstall-desktop.ps1'
if (Test-Path $uninstall) {
    $regKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\{7B4E2C1A-9F3D-4E8B-A6C2-5D1F0E9A3B71}_is1'
    if (Test-Path $regKey) { Remove-Item $regKey -Recurse -Force }
    New-Item $regKey -Force | Out-Null
    $cmd = 'powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + $uninstall + '"'
    $values = @{
        DisplayName          = $appName
        DisplayIcon          = (Join-Path $target 'ark.ico')
        Publisher            = $appName
        InstallLocation      = $target
        UninstallString      = $cmd
        QuietUninstallString = $cmd + ' -Quiet'
    }
    foreach ($k in $values.Keys) { New-ItemProperty $regKey -Name $k -Value $values[$k] -PropertyType String -Force | Out-Null }
    New-ItemProperty $regKey -Name NoModify -Value 1 -PropertyType DWord -Force | Out-Null
    New-ItemProperty $regKey -Name NoRepair -Value 1 -PropertyType DWord -Force | Out-Null
    New-ItemProperty $regKey -Name EstimatedSize -PropertyType DWord -Force -Value ([int]((Get-ChildItem $target -Recurse -File | Measure-Object Length -Sum).Sum / 1KB)) | Out-Null
}
Write-Host "Đã cài: $target" -ForegroundColor Green
Write-Host "Lối tắt: Desktop + Start Menu ($appName)"

if ($Launch) {
    Start-Process -FilePath $desktop
    Write-Host 'Đang mở Ark Studio…'
}
