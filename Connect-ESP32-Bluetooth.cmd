@echo off
setlocal
title SmartTrack ESP32 Bluetooth Monitor
"%~dp0.tools\pio-env\Scripts\python.exe" "%~dp0scripts\ble-monitor.py" %*
set "monitorResult=%errorlevel%"
echo.
pause
exit /b %monitorResult%
