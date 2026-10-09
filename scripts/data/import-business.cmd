@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0import-business.ps1" %*
exit /b %ERRORLEVEL%
