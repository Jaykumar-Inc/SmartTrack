@echo off
setlocal
title SmartTrack ESP32 USB Monitor
"%~dp0.tools\pio-env\Scripts\python.exe" "%~dp0scripts\usb-monitor.py" %*
set "monitorResult=%errorlevel%"
echo.
pause
exit /b %monitorResult%
