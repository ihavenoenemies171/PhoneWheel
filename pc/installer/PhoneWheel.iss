; PhoneWheel one-click Windows installer (Inno Setup 6)
; Built by GitHub Actions:  iscc /DAppVersion=1.0.N /DWithVJoy=1 PhoneWheel.iss
;
; What it does:
;   1. installs PhoneWheel.exe + helpers + docs
;   2. installs the vJoy 2.2.2.0 driver if it isn't installed yet (bundled, silent)
;   3. sets up vJoy device 1 for PhoneWheel: axes X Y Z Rx Ry, 32 buttons, no POV hats, enabled
;   4. allows PhoneWheel through the Windows Firewall on all network types
;   5. Start-menu + optional desktop shortcut, optional start with Windows

#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif

[Setup]
AppId={{6E7C2F1A-3B5D-4C8E-9F21-PHONEWHEEL01}
AppName=PhoneWheel
AppVersion={#AppVersion}
AppPublisher=PhoneWheel contributors
AppPublisherURL=https://github.com/ihavenoenemies171/PhoneWheel
AppSupportURL=https://github.com/ihavenoenemies171/PhoneWheel/blob/main/docs/TROUBLESHOOTING.md
DefaultDirName={autopf}\PhoneWheel
DefaultGroupName=PhoneWheel
DisableProgramGroupPage=yes
OutputDir=..\..\installer_out
OutputBaseFilename=PhoneWheel-Setup
SetupIconFile=..\icon.ico
UninstallDisplayIcon={app}\PhoneWheel.exe
Compression=lzma2
SolidCompression=yes
PrivilegesRequired=admin
WizardStyle=modern
LicenseFile=..\..\LICENSE
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible

[Tasks]
Name: "vjoy"; Description: "Install and set up the vJoy driver (needed for games to see the wheel)"; Check: VJoyBundled
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Shortcuts:"
Name: "autostart"; Description: "Start PhoneWheel when Windows starts"; GroupDescription: "Shortcuts:"; Flags: unchecked

[Files]
Source: "..\dist\PhoneWheel.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\usb_android.bat"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\network_setup.bat"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\..\README.md"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\..\LICENSE"; DestDir: "{app}"; DestName: "LICENSE.txt"; Flags: ignoreversion
Source: "..\..\docs\*"; DestDir: "{app}\docs"; Flags: ignoreversion recursesubdirs
#ifdef WithVJoy
Source: "vJoySetup.exe"; DestDir: "{tmp}"; Flags: deleteafterinstall; Tasks: vjoy
#endif

[Icons]
Name: "{group}\PhoneWheel"; Filename: "{app}\PhoneWheel.exe"; WorkingDir: "{app}"
Name: "{group}\PhoneWheel setup guide"; Filename: "https://github.com/ihavenoenemies171/PhoneWheel/blob/main/docs/SETUP.md"
Name: "{group}\Session report"; Filename: "http://localhost:8765/report"
Name: "{group}\Uninstall PhoneWheel"; Filename: "{uninstallexe}"
Name: "{autodesktop}\PhoneWheel"; Filename: "{app}\PhoneWheel.exe"; WorkingDir: "{app}"; Tasks: desktopicon
Name: "{userstartup}\PhoneWheel"; Filename: "{app}\PhoneWheel.exe"; WorkingDir: "{app}"; Tasks: autostart

[Run]
#ifdef WithVJoy
Filename: "{tmp}\vJoySetup.exe"; Parameters: "/VERYSILENT /SUPPRESSMSGBOXES /NORESTART"; StatusMsg: "Installing the vJoy driver..."; Tasks: vjoy; Check: not VJoyInstalled; Flags: waituntilterminated
#endif
Filename: "{code:VJoyConfigPath}"; Parameters: "1 -f -a x y z rx ry -b 32 -p 0"; StatusMsg: "Setting up vJoy device 1 for PhoneWheel..."; Check: VJoyConfigExists; Flags: runhidden waituntilterminated
Filename: "{code:VJoyConfigPath}"; Parameters: "enable on"; Check: VJoyConfigExists; Flags: runhidden waituntilterminated
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""PhoneWheel"""; Flags: runhidden waituntilterminated
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""PhoneWheel"" dir=in action=allow protocol=UDP localport=8767 profile=any"; StatusMsg: "Allowing PhoneWheel through the firewall..."; Flags: runhidden waituntilterminated
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""PhoneWheel"" dir=in action=allow protocol=TCP localport=8765-8767 profile=any"; Flags: runhidden waituntilterminated
Filename: "{app}\PhoneWheel.exe"; Description: "Start PhoneWheel now"; WorkingDir: "{app}"; Flags: postinstall nowait skipifsilent

[UninstallRun]
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""PhoneWheel"""; Flags: runhidden; RunOnceId: "PWFirewall"

[Messages]
FinishedLabel=PhoneWheel is installed.%n%nNext: install the PhoneWheel app on your Android phone, open it on the same WiFi (or USB tethering) and drive. If Windows asks to restart for the vJoy driver, restart before playing.

[Code]
function VJoyBundled(): Boolean;
begin
#ifdef WithVJoy
  Result := True;
#else
  Result := False;
#endif
end;

function VJoyDir(): String;
begin
  Result := ExpandConstant('{commonpf64}\vJoy');
  if not DirExists(Result) then
    Result := ExpandConstant('{commonpf32}\vJoy');
end;

function VJoyInstalled(): Boolean;
begin
  Result := FileExists(VJoyDir() + '\x64\vJoyInterface.dll') or FileExists(VJoyDir() + '\vJoyInterface.dll');
end;

function VJoyConfigPath(Param: String): String;
begin
  Result := VJoyDir() + '\x64\vJoyConfig.exe';
  if not FileExists(Result) then
    Result := VJoyDir() + '\vJoyConfig.exe';
end;

function VJoyConfigExists(): Boolean;
begin
  Result := FileExists(VJoyConfigPath(''));
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if (CurStep = ssPostInstall) and (not VJoyInstalled()) then
    MsgBox('vJoy is not installed yet. PhoneWheel needs it so games can see the wheel.' + #13#10 +
           'Download vJoy 2.2.2.0 from github.com/BrunnerInnovation/vJoy/releases, install it, then start PhoneWheel.',
           mbInformation, MB_OK);
end;
