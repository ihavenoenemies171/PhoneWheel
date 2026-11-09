@echo off
title PhoneWheel server
cd /d "%~dp0"
python server.py %*
pause
