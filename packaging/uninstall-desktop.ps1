# Gỡ Ark Studio đã cài bằng install-desktop.ps1 (build-desktop.bat / "Cai dat Ark Studio.bat"):
# xoá lối tắt Desktop + Start Menu, thư mục %LOCALAPPDATA%\Programs\Ark Studio và mục trong Cài đặt › Ứng dụng.
# GIỮ dữ liệu ở %USERPROFILE%\.byteplus-ark (DB, log, API key) và ảnh / video trong thư mục lưu.
# Windows gọi script này khi bấm Gỡ cài đặt (install-desktop.ps1 ghi đường dẫn vào UninstallString).
param([switch]$Quiet)
$ErrorActionPreference = 'Stop'
$appName = 'Ark Studio'
$target = Join-Path $env:LOCALAPPDATA "Programs\$appName"
# Cùng AppId với trình cài Inno Setup (ark-studio.iss) → chỉ một mục "Ark Studio" trong danh sách ứng dụng
$regKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\{7B4E2C1A-9F3D-4E8B-A6C2-5D1F0E9A3B71}_is1'

Add-Type -AssemblyName System.Windows.Forms
function Say($msg, $icon = 'Information') {
    if ($Quiet) { Write-Host $msg } else { [void][System.Windows.Forms.MessageBox]::Show($msg, "Gỡ $appName", 'OK', $icon) }
}

# App đang chạy → không xoá được file đang dùng
function AppRunning {
    try {
        $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 'http://127.0.0.1:18080/api/v1/app/ping'
        return $r.Content -match 'ark-studio'
    } catch { return $false }
}
if (AppRunning) {
    Say "$appName đang chạy.`n`nHãy thoát app (chuột phải biểu tượng Ark Studio ở khay hệ thống › Thoát) rồi gỡ lại." 'Warning'
    exit 1
}

# Lối tắt: chỉ xoá lối tắt trỏ vào bản cài này (không đụng lối tắt khác trùng tên)
$shell = New-Object -ComObject WScript.Shell
$links = @(
    (Join-Path ([Environment]::GetFolderPath('Desktop')) "$appName.lnk"),
    (Join-Path ([Environment]::GetFolderPath('Programs')) "$appName.lnk")
)
foreach ($lnk in $links) {
    if (Test-Path $lnk) {
        $t = $shell.CreateShortcut($lnk).TargetPath
        if (-not $t -or $t.StartsWith($target, [StringComparison]::OrdinalIgnoreCase)) { Remove-Item $lnk -Force }
    }
}

if (Test-Path $regKey) { Remove-Item $regKey -Recurse -Force }

# Xoá thư mục cài (đổi thư mục làm việc ra ngoài trước, kẻo Windows khoá thư mục đang đứng)
Set-Location $env:TEMP
if (Test-Path $target) {
    try {
        Remove-Item $target -Recurse -Force
    } catch {
        Say "Đã xoá lối tắt nhưng chưa xoá hết thư mục:`n$target`n`n$($_.Exception.Message)`n`nXoá tay thư mục này nếu cần." 'Warning'
        exit 1
    }
}
Say "Đã gỡ $appName.`n`nDữ liệu (lịch sử, API key) vẫn giữ ở $env:USERPROFILE\.byteplus-ark – cài lại là dùng tiếp."
