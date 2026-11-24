@echo off
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\Allow-WiFi.ps1
if errorlevel 1 echo Run this file as administrator after turning on the laptop hotspot.
pause
