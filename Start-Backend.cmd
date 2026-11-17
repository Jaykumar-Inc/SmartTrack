@echo off
setlocal
title SmartTrack Backend
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\launch-backend.ps1" %*
set "launchResult=%errorlevel%"
echo.
pause
exit /b %launchResult%
