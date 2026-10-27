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
echo [1] Bu bilgisayarda sunucu kur ve oyunu baslat
echo [2] Arkadasin sunucusuna katil
echo.
choice /c 12 /n /m "Bu bilgisayar sunucu olacak mi? [1/2]: "
if errorlevel 2 (
    set "ROLE=Join"
    "%PS_EXE%" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup.ps1" -Role Join
) else (
    set "ROLE=Host"
    "%PS_EXE%" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup.ps1" -Role Host
)
set "RESULT=%ERRORLEVEL%"
if not "%RESULT%"=="0" (
    echo.
    echo Kurulum tamamlanamadi. Yukaridaki aciklama ne yapilacagini soyluyor.
    echo Ekrani fotograf cekip oyun kurucusuna gonderebilirsin.
    echo.
    pause
    exit /b %RESULT%
)
echo.
choice /c YN /n /m "Kurulum bitti. Oyunu simdi baslatmak ister misin? [Y/N]: "
if errorlevel 2 goto done
call "%~dp0WolfteamBaslat.bat" -Mode %ROLE%
:done
exit /b %RESULT%
