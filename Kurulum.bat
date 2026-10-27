@echo off
setlocal
cd /d "%~dp0"
title WolfTeam 2012 LAN Kurulumu

set "PS_EXE="
for /f "delims=" %%P in ('where.exe pwsh.exe 2^>nul') do if not defined PS_EXE set "PS_EXE=%%P"
if not defined PS_EXE set "PS_EXE=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
if not exist "%PS_EXE%" (
    echo PowerShell bulunamadi. Windows PowerShell 5 veya PowerShell 7 gerekli.
    pause
    exit /b 1
)

echo WolfTeam 2012 LAN kurulumu
echo [1] Sunucu bilgisayari
echo [2] Sunucuya katilacak bilgisayar
choice /c 12 /n /m "Secim [1]: "
if errorlevel 2 (
    "%PS_EXE%" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup.ps1" -PythonOnly
) else (
    "%PS_EXE%" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup.ps1"
)
set "RESULT=%ERRORLEVEL%"
if not "%RESULT%"=="0" (
    echo.
    echo Kurulum tamamlanamadi. Yukaridaki hata bilgisini kontrol edin.
    pause
)
exit /b %RESULT%
