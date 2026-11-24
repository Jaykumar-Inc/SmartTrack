@echo off
cd /d "%~dp0"
echo This uploads the configured firmware to your SmartTrack ESP32 on COM5.
echo Close Arduino Serial Monitor. Run START_SMARTTRACK.cmd first.
python scripts\smarttrack_local.py upload --port COM5
pause
