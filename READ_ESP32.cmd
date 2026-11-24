@echo off
cd /d "%~dp0"
echo Close Arduino Serial Monitor before running this check.
python scripts\check_esp32.py --port COM5 --seconds 25
pause
