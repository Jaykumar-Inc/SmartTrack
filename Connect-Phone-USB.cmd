@echo off
setlocal
title SmartTrack Phone USB Connection
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\connect-phone-usb.ps1" %*
set "connectResult=%errorlevel%"
echo.
pause
exit /b %connectResult%
