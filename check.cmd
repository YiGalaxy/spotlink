@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\check.ps1" %*
set "taskExit=%ERRORLEVEL%"
echo %* | findstr /I /C:"-NoPause" >nul
if errorlevel 1 pause
exit /b %taskExit%
