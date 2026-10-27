function Get-RadminAddresses {
    foreach($adapter in [Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()){
        if($adapter.OperationalStatus -ne 'Up' -or ($adapter.Name+' '+$adapter.Description) -notmatch '(?i)Radmin|Famatech'){continue}
        foreach($entry in $adapter.GetIPProperties().UnicastAddresses){
            if($entry.Address.AddressFamily -eq [Net.Sockets.AddressFamily]::InterNetwork -and $entry.Address.ToString() -match '^26\.'){
                [pscustomobject]@{IPAddress=$entry.Address.ToString();InterfaceName=$adapter.Name}
            }
        }
    }
}

function Test-RadminServer([string]$Address, [int]$TimeoutMilliseconds=4000) {
    $client=[Net.Sockets.TcpClient]::new([Net.Sockets.AddressFamily]::InterNetwork)
    try{
        $task=$client.ConnectAsync($Address,40706)
        if(-not $task.Wait($TimeoutMilliseconds)){throw 'Connection timed out'}
        if(-not $client.Connected){throw 'Connection failed'}
        return $client.Client.LocalEndPoint.Address.ToString()
    }finally{$client.Dispose()}
}

function New-RadminJoinFile([string]$Root, [string]$ServerIP) {
    $parsed=$null
    if(-not [Net.IPAddress]::TryParse($ServerIP,[ref]$parsed) -or
        $parsed.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork -or
        $parsed.ToString() -notmatch '^26\.'){throw 'Gecerli bir Radmin sunucu adresi gerekli.'}
    $address=$parsed.ToString()
    $template=@'
@echo off
setlocal
cd /d "%~dp0"
title WolfTeam Radmin - Sunucuya Katil
if not exist "%~dp0WolfteamBaslat.bat" (
    echo Bu dosyayi WolfteamBaslat.bat ile ayni oyun klasorune koyup tekrar acin.
    pause
    exit /b 1
)
> "%~dp0RadminSunucu.json" echo {"version":1,"ip":"__SERVER_IP__","port":40706,"name":"WolfTeam Radmin Sunucusu"}
call "%~dp0WolfteamBaslat.bat" -Mode Join -ServerIP __SERVER_IP__
'@
    $joinPath=Join-Path $Root 'RadminKatil.bat'
    [IO.File]::WriteAllText($joinPath,($template.Replace('__SERVER_IP__',$address) -replace "`r?`n","`r`n")+"`r`n",[Text.Encoding]::ASCII)
    @{version=1;ip=$address;port=40706;name='WolfTeam Radmin Sunucusu'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $Root 'RadminSunucu.json') -Encoding UTF8
    return $joinPath
}
