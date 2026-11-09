@echo off
title PhoneWheel network setup
:: Re-launch as administrator if needed
net session >nul 2>&1
if %errorlevel% neq 0 (
  echo Asking for administrator permission...
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)
echo.
echo  1) Allowing PhoneWheel through Windows Firewall on ALL networks
echo     (USB tethering and phone hotspots are usually treated as "Public").
netsh advfirewall firewall delete rule name="PhoneWheel" >nul 2>&1
netsh advfirewall firewall add rule name="PhoneWheel" dir=in action=allow protocol=UDP localport=8767 profile=any >nul
netsh advfirewall firewall add rule name="PhoneWheel" dir=in action=allow protocol=TCP localport=8765-8767 profile=any >nul
echo     done.
echo.
echo  2) Keeping this PC's internet on its own WiFi/Ethernet when the phone is USB-tethered.
powershell -NoProfile -Command "$a = Get-NetAdapter | Where-Object { $_.InterfaceDescription -match 'NDIS|Tether|Android|RNDIS' }; if ($a) { $a | ForEach-Object { Set-NetIPInterface -InterfaceIndex $_.ifIndex -InterfaceMetric 9000 -ErrorAction SilentlyContinue; Write-Host ('     ' + $_.Name + ': lowered priority') } } else { Write-Host '     No USB tethering adapter found right now - run this again with tethering switched on if your PC internet goes through the phone.' }"
echo.
echo  All set. Start PhoneWheel.exe and open the app.
echo.
pause
