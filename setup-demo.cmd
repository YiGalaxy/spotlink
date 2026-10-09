@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\setup-demo.ps1" %*
exit /b %ERRORLEVEL%
