@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\data\run-step.ps1" -Step status %*
exit /b %ERRORLEVEL%
