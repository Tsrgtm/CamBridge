@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Install-OBS-Plugin.ps1"
if errorlevel 1 (
  echo Installation failed. Close OBS and try running this file as administrator.
  pause
  exit /b 1
)
pause