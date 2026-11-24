@echo off
cd /d "%~dp0"
if not exist ".local\LOGIN.txt" goto missing
start "" notepad.exe ".local\LOGIN.txt"
exit /b 0
:missing
echo Run START_SMARTTRACK.cmd first.
pause
