@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0check-environment.ps1" %*
exit /b %ERRORLEVEL%
