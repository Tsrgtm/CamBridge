@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Install-Windows.ps1"
if errorlevel 1 echo Close camera apps and run this installer again.
pause
