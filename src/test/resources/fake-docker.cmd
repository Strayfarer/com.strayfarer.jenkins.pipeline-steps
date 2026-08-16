@echo off
pwsh -NoLogo -NoProfile -NonInteractive -File "%~dp0fake-docker.ps1" %*
exit /b %ERRORLEVEL%
