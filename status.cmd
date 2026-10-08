@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\status.ps1" %*
exit /b %ERRORLEVEL%
