@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0migrate-schema.ps1" %*
exit /b %ERRORLEVEL%
