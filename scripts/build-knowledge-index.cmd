@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-knowledge-index.ps1" %*
exit /b %errorlevel%
