@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\setup-local-model.ps1" %*
exit /b %ERRORLEVEL%
