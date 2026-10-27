@echo off
setlocal
cd /d "%~dp0"
title WolfTeam 2012 Yerel Envanter
set "PYTHONHOME="
set "PYTHONPATH="
if not exist "%~dp0.runtime\logan\php\php.exe" goto nohost
if not exist "%~dp0.runtime\logan\mariadb\mariadb-11.4.8-winx64\bin\mariadbd.exe" goto nohost
if not exist "%~dp0.runtime\logan\java\bin\java.exe" goto nohost
if not exist "%~dp0.runtime\logan\classes\" goto nohost
set "PYTHONW=%~dp0.runtime\inventory-python\pythonw.exe"
set "READY=%~dp0.runtime\inventory-python\.wolfteam-inventory-ready"
set "PS_EXE="
if not exist "%PYTHONW%" (
    goto prepare
)
if not exist "%READY%" (
    goto prepare
)
goto launch
:prepare
    for /f "delims=" %%P in ('where.exe pwsh.exe 2^>nul') do if not defined PS_EXE set "PS_EXE=%%P"
    if not defined PS_EXE set "PS_EXE=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
    if not exist "%PS_EXE%" (
        echo PowerShell bulunamadi. Windows PowerShell 5 veya PowerShell 7 gerekli.
        pause
        exit /b 1
    )
    "%PS_EXE%" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0inventory_setup.ps1"
    if errorlevel 1 (
        echo Envanter araci hazirlanamadi. Yukaridaki aciklama yardimci olur.
        pause
        exit /b 1
    )
if not exist "%PYTHONW%" (
    echo Tasinabilir Python hazirlanamadi. Yukaridaki hatayi kontrol edin.
    pause
    exit /b 1
)
:launch
start "" "%PYTHONW%" "%~dp0inventory_manager.py"
endlocal
exit /b 0
:nohost
echo Envanter yoneticisi sunucu bilgisayarinda kullanilir.
echo Once Kurulum.bat dosyasini calistirip [1] Sunucu secenegini tamamlayin.
pause
exit /b 1
