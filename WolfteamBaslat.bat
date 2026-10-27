@echo off
setlocal
cd /d "%~dp0"
title WolfTeam 2012 LAN

set "PS_EXE="
for /f "delims=" %%P in ('where.exe pwsh.exe 2^>nul') do if not defined PS_EXE set "PS_EXE=%%P"
if not defined PS_EXE set "PS_EXE=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
if not exist "%PS_EXE%" (
    echo PowerShell bulunamadi. Windows PowerShell 5 veya PowerShell 7 gerekli.
    pause
    exit /b 1
)

"%PS_EXE%" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0launch.ps1" %*
set "RESULT=%ERRORLEVEL%"
if not "%RESULT%"=="0" (
    echo.
    echo Baslatma tamamlanamadi. Yukaridaki hata bilgisini kontrol et.
    pause
)
exit /b %RESULT%
