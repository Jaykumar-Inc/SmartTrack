@echo off
cd /d "%~dp0"
python scripts\smarttrack_local.py start
if errorlevel 1 goto failed
echo.
echo Open SHOW_LOGIN.cmd for the shared server URL and app login.
echo Keep the laptop awake. Close this window if you like; the servers stay running.
pause
exit /b 0
:failed
echo.
echo Startup failed. Read the message above and .local\logs.
pause
exit /b 1
