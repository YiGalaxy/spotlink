@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0import-knowledge.ps1" %*
exit /b %ERRORLEVEL%
