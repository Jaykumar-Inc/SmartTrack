@echo off
setlocal
title SmartTrack Phone Network Access
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\enable-phone-access.ps1" %*
set "setupResult=%errorlevel%"
echo.
pause
exit /b %setupResult%
