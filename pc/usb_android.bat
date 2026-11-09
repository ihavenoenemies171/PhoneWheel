@echo off
title PhoneWheel - Android USB
cd /d "%~dp0"
set ADB=adb
if exist "%~dp0platform-tools\adb.exe" set ADB="%~dp0platform-tools\adb.exe"
%ADB% version >nul 2>nul || (
  echo adb not found.
  echo Download "SDK Platform-Tools for Windows" from developer.android.com/tools/releases/platform-tools
  echo and unzip it so this folder contains platform-tools\adb.exe
  pause & exit /b 1
)
echo Waiting for phone (enable USB debugging and accept the prompt on the phone)...
%ADB% wait-for-device
%ADB% reverse tcp:8767 tcp:8767 || (echo adb reverse failed & pause & exit /b 1)
%ADB% reverse tcp:8765 tcp:8765 >nul
echo.
echo  USB link ready.
echo    App:      choose "USB cable" in PhoneWheel settings
echo    Browser:  http://localhost:8765
echo  Re-run this after unplugging the phone.
echo.
pause
