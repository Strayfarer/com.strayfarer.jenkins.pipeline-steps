@echo off
where pwsh >nul 2>nul
if %ERRORLEVEL% equ 0 (
    pwsh -NoLogo -NoProfile -NonInteractive -File "%~dp0fake-docker.ps1" %*
) else (
    powershell -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "%~dp0fake-docker.ps1" %*
)
exit /b %ERRORLEVEL%
