@echo off
title PhoneWheel setup
cd /d "%~dp0"
where python >nul 2>nul || (echo Python not found. Install Python 3.10+ from python.org and tick "Add python.exe to PATH". & pause & exit /b 1)
python -m pip install --upgrade pip
python -m pip install -r requirements.txt
echo.
echo Done. Now install vJoy 2.2.2.0 (see README) and run start.bat
pause
