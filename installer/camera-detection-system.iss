#define MyAppName "产线缺陷在线AI检测系统"
#define MyAppVersion "1.0.0"
#define MyAppPublisher "Camera Detection Team"
#ifndef SourceDir
#define SourceDir "..\dist-release\CameraDetectionSystem"
#endif
#ifndef OutputDir
#define OutputDir "..\dist-release\installer"
#endif

[Setup]
AppId={{7B0D9C7A-AE9F-4F9B-9E53-AC0A6B5E8091}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\CameraDetectionSystem
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
PrivilegesRequired=admin
ArchitecturesInstallIn64BitMode=x64
OutputDir={#OutputDir}
OutputBaseFilename=CameraDetectionSystem-Setup-{#MyAppVersion}
Compression=lzma
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\service\CameraDetectionService.exe
SetupLogging=yes

[Tasks]
Name: "desktopicon"; Description: "创建桌面访问入口"; GroupDescription: "附加任务："; Flags: unchecked

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Excludes: "config\application.yml"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#SourceDir}\config\application.yml"; DestDir: "{app}\config"; Flags: ignoreversion onlyifdoesntexist

[Dirs]
Name: "{app}\logs"
Name: "{app}\data\uploads\defect-images"
Name: "{app}\data\uploads\images"

[Icons]
Name: "{group}\打开产线缺陷在线AI检测系统"; Filename: "http://localhost:8091"
Name: "{group}\初始化数据库"; Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\scripts\init-database.ps1"""; WorkingDir: "{app}"
Name: "{group}\启动系统服务"; Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\scripts\start-service.ps1"""; WorkingDir: "{app}"
Name: "{group}\停止系统服务"; Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\scripts\stop-service.ps1"""; WorkingDir: "{app}"
Name: "{group}\查看服务状态"; Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\scripts\service-status.ps1"""; WorkingDir: "{app}"
Name: "{group}\安装自检"; Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\scripts\verify-installation.ps1"""; WorkingDir: "{app}"
Name: "{group}\产线状态P1原型"; Filename: "{app}\docs\dashboard-line-status-prototype.html"
Name: "{group}\部署说明"; Filename: "{app}\docs\部署说明和验收清单.md"
Name: "{group}\卸载产线缺陷在线AI检测系统"; Filename: "{uninstallexe}"
Name: "{autodesktop}\产线缺陷在线AI检测系统"; Filename: "http://localhost:8091"; Tasks: desktopicon

[Run]
Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\scripts\install-service.ps1"" -WorkDir ""{code:GetWorkDir}"""; StatusMsg: "正在安装并启动 Windows 服务..."; Flags: runhidden waituntilterminated
Filename: "http://localhost:8091"; Description: "打开产线缺陷在线AI检测系统"; Flags: postinstall shellexec skipifsilent nowait

[UninstallRun]
Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\scripts\uninstall-service.ps1"""; Flags: runhidden waituntilterminated

[Code]
var
  WorkDirPage: TInputDirWizardPage;

function NormalizedWorkDir(): String;
begin
  Result := '';
  if WorkDirPage <> nil then
    Result := Trim(WorkDirPage.Values[0]);
  if Result = '' then
    Result := ExpandConstant('{app}\runtime');
end;

function GetWorkDir(Param: String): String;
begin
  Result := NormalizedWorkDir();
end;

procedure InitializeWizard;
begin
  WorkDirPage := CreateInputDirPage(
    wpSelectDir,
    '选择工作路径',
    '请选择运行数据、上传图片和日志保存位置',
    '工作路径用于保存日志、相机上传图片、运行数据等内容；建议选择非系统盘或磁盘空间充足的目录。',
    False,
    ''
  );
  WorkDirPage.Add('工作路径：');
end;

procedure CurPageChanged(CurPageID: Integer);
begin
  if CurPageID = WorkDirPage.ID then
  begin
    if Trim(WorkDirPage.Values[0]) = '' then
      WorkDirPage.Values[0] := ExpandConstant('{app}\runtime');
  end;
end;

function NextButtonClick(CurPageID: Integer): Boolean;
begin
  Result := True;
  if CurPageID = WorkDirPage.ID then
  begin
    if Trim(WorkDirPage.Values[0]) = '' then
    begin
      MsgBox('请选择工作路径。', mbError, MB_OK);
      Result := False;
    end;
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  WorkDir: String;
begin
  if CurStep = ssPostInstall then
  begin
    WorkDir := NormalizedWorkDir();
    ForceDirectories(WorkDir);
    ForceDirectories(ExpandConstant('{app}\config'));
    SaveStringToFile(ExpandConstant('{app}\config\workdir.txt'), WorkDir, False);
  end;
end;
