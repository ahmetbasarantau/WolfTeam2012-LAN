@echo off
setlocal
cd /d "%~dp0"
title WolfTeam 2012 Yerel Envanter
where py.exe >nul 2>nul
if not errorlevel 1 (
    py.exe -3 "%~dp0inventory_manager.py"
) else (
    python.exe "%~dp0inventory_manager.py"
)
if errorlevel 1 pause
endlocal
