; Trình cài đặt Ark Studio (Inno Setup 6) → build-desktop\ArkStudio-Setup-<version>.exe
; Được build-desktop.ps1 gọi sau khi đã tạo build-desktop\Ark Studio\ (runtime + app + ark.ico).
; Cài cho người dùng hiện tại, không cần admin: %LOCALAPPDATA%\Programs\Ark Studio.
; Lối tắt chạy runtime\bin\javaw.exe (chữ ký Oracle) – không có exe tự build nào để diệt virus chặn.
; Dữ liệu (DB, log, key) ở %USERPROFILE%\.byteplus-ark – gỡ cài đặt không xoá.

#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif
#define AppName "Ark Studio"
#define Stage "..\build-desktop\Ark Studio"
; Giá trị chèn vào Parameters: "..." nên dấu " quanh đường dẫn jar phải là "" (và nhân đôi lần nữa trong chuỗi #define)
#define JavaArgs "-Dark.desktop=true -Dfile.encoding=UTF-8 -Xmx2g -jar """"{app}\app\byteplus-ark-backend.jar"""""

[Setup]
AppId={{7B4E2C1A-9F3D-4E8B-A6C2-5D1F0E9A3B71}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher=Ark Studio
DefaultDirName={localappdata}\Programs\{#AppName}
DisableProgramGroupPage=yes
DisableDirPage=auto
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=..\build-desktop
OutputBaseFilename=ArkStudio-Setup-{#AppVersion}
SetupIconFile=ark.ico
UninstallDisplayIcon={app}\ark.ico
UninstallDisplayName={#AppName}
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
CloseApplications=no
#ifdef TestBuild
; bản thử nghiệm (build-desktop.ps1 không dùng): không ghi mục gỡ cài đặt, không tạo icon
Uninstallable=no
CreateUninstallRegKey=no
#endif

[Languages]
Name: "en"; MessagesFile: "compiler:Default.isl"

[CustomMessages]
en.DesktopIcon=Tạo biểu tượng Ark Studio trên Desktop
en.LaunchApp=Mở Ark Studio
en.AppRunning=Ark Studio đang chạy.%n%nHãy thoát app (chuột phải biểu tượng Ark Studio ở khay hệ thống, góc phải thanh taskbar › Thoát) rồi bấm OK để tiếp tục.

#ifndef TestBuild
[Tasks]
Name: "desktopicon"; Description: "{cm:DesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"
#endif

[Files]
Source: "{#Stage}\runtime\*"; DestDir: "{app}\runtime"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#Stage}\app\*"; DestDir: "{app}\app"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#Stage}\ark.ico"; DestDir: "{app}"; Flags: ignoreversion
Source: "dist\HUONG DAN.txt"; DestDir: "{app}"; Flags: ignoreversion

[InstallDelete]
; cập nhật: xoá Java / jar cũ để không lẫn file của bản trước
Type: filesandordirs; Name: "{app}\runtime"
Type: filesandordirs; Name: "{app}\app"

#ifndef TestBuild
[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\runtime\bin\javaw.exe"; Parameters: "{#JavaArgs}"; WorkingDir: "{app}"; IconFilename: "{app}\ark.ico"; Comment: "Tạo ảnh / video BytePlus"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\runtime\bin\javaw.exe"; Parameters: "{#JavaArgs}"; WorkingDir: "{app}"; IconFilename: "{app}\ark.ico"; Comment: "Tạo ảnh / video BytePlus"; Tasks: desktopicon
#endif

[Run]
Filename: "{app}\runtime\bin\javaw.exe"; Parameters: "{#JavaArgs}"; WorkingDir: "{app}"; Description: "{cm:LaunchApp}"; Flags: nowait postinstall skipifsilent

[Code]
{ Ark Studio đang chạy = có trả lời ở http://127.0.0.1:18080/api/v1/app/ping }
function AppRunning(): Boolean;
var
  Http: Variant;
  Status: Integer;
  Body: String;
begin
  Result := False;
  try
    Http := CreateOleObject('WinHttp.WinHttpRequest.5.1');
    Http.SetTimeouts(1000, 1000, 1000, 1000);
    Http.Open('GET', 'http://127.0.0.1:18080/api/v1/app/ping', False);
    Http.Send('');
    Status := Http.Status;
    Body := Http.ResponseText;
    Result := (Status = 200) and (Pos('ark-studio', Body) > 0);
  except
    Result := False;
  end;
end;

function WaitAppClosed(Silent: Boolean): Boolean;
begin
  Result := True;
  while AppRunning() do
  begin
    { chạy im lặng: hộp thoại tự trả OK → sẽ lặp mãi, nên dừng luôn }
    if Silent or (MsgBox(CustomMessage('AppRunning'), mbInformation, MB_OKCANCEL) = IDCANCEL) then
    begin
      Result := False;
      Exit;
    end;
  end;
end;

function InitializeSetup(): Boolean;
begin
  Result := WaitAppClosed(WizardSilent());
end;

function InitializeUninstall(): Boolean;
begin
  Result := WaitAppClosed(UninstallSilent());
end;
