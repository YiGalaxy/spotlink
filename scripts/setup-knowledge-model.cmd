@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-knowledge-model.ps1" %*
exit /b %errorlevel%
