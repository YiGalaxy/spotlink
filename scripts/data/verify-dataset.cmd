@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0verify-dataset.ps1" %*
exit /b %ERRORLEVEL%
