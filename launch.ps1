[CmdletBinding()]
param(
    [ValidateSet('Host','Join','Server')][string]$Mode,
    [string]$ServerIP='127.0.0.1',
    [string]$BindIP='127.0.0.1',
    [string]$Nick,
    [switch]$CheckOnly
)
$ErrorActionPreference='Stop'
Set-Location -LiteralPath $PSScriptRoot
$runtimePython=Join-Path $PSScriptRoot '.runtime/python/python.exe'
$systemPython=$null
$python=$null
function Test-UsablePython([string]$Executable){
    if(-not $Executable -or -not (Test-Path -LiteralPath $Executable -PathType Leaf)){return $false}
    try{
        $ErrorActionPreference='SilentlyContinue'
        & $Executable -c 'import asyncio, ipaddress, json, socket' 2>$null | Out-Null
        return $LASTEXITCODE -eq 0
    }catch{return $false}
}
if(Test-UsablePython $runtimePython){
    $python=$runtimePython
    $env:PYTHONHOME=Split-Path $runtimePython
    $env:PYTHONPATH=Join-Path $PSScriptRoot 'server'
}
$pythonCommand=Get-Command python.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
if($pythonCommand -and (Test-UsablePython $pythonCommand.Source)){
    $systemPython=$pythonCommand.Source
    if(-not $python){$python=$systemPython}
}
$earlyPolicyFile=Join-Path $PSScriptRoot 'network-policy.json'
$earlyPolicy=if(Test-Path -LiteralPath $earlyPolicyFile){Get-Content -LiteralPath $earlyPolicyFile -Raw | ConvertFrom-Json}else{$null}
if(-not $python -and (Test-Path -LiteralPath (Join-Path $PSScriptRoot '.runtime/python.exe')) -and $earlyPolicy){
    # Backward compatibility with older local installs; release packages ship
    # their own relocatable Python runtime above.
    $python=Join-Path $PSScriptRoot '.runtime/python.exe'
    $env:PYTHONHOME=$earlyPolicy.PythonHome
    $env:PYTHONPATH=Join-Path $PSScriptRoot 'server'
}
if(-not $python){throw 'Python calisma zamani hazir degil. Once Kurulum.bat dosyasini calistirin.'}
$savedNickFile=Join-Path $PSScriptRoot '.runtime/player-name.txt'
$accountCacheFile=Join-Path $PSScriptRoot '.runtime/player-accounts.json'
function Get-LocalProfileNames {
    $names=[Collections.Generic.List[string]]::new()
    $lookupRun=$null
    $lookupProcess=$null
    try {
        $users=$null
        try { $users=Invoke-RestMethod 'http://127.0.0.1:18080/wolfteam/users.php?req=adminusers' -TimeoutSec 1 } catch {}
        if(-not $users -and $systemPython -and (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'logan/host.py'))){
            $lookupRun=Join-Path $PSScriptRoot ('.runtime/account-lookup-'+$PID)
            New-Item -ItemType Directory -Path $lookupRun -Force | Out-Null
            Remove-Item -LiteralPath (Join-Path $lookupRun 'stop.request') -Force -ErrorAction SilentlyContinue
            $hostScript=Join-Path $PSScriptRoot 'logan/host.py'
            $lookupProcess=Start-Process -FilePath $systemPython -ArgumentList @('-u',('"'+$hostScript+'"'),'--services-only','--run',('"'+$lookupRun+'"')) -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $lookupRun 'stdout.log') -RedirectStandardError (Join-Path $lookupRun 'stderr.log') -PassThru
            for($attempt=0;$attempt -lt 80;$attempt++){
                if($lookupProcess.HasExited){break}
                try { $users=Invoke-RestMethod 'http://127.0.0.1:18080/wolfteam/users.php?req=adminusers' -TimeoutSec 1; break } catch {}
                Start-Sleep -Milliseconds 250
            }
        }
        if($users){
            foreach($user in $users){
                $name=[string]$user.username
                if($name -match '^[A-Za-z0-9_.\[\]-]{1,12}$' -and -not $names.Contains($name)){$names.Add($name)}
            }
        }
    } catch {
        Write-Host 'Hesap listesi okunamadi; bu bilgisayarda kaydedilmis adlar gosterilecek.' -ForegroundColor Yellow
    } finally {
        if($lookupProcess -and -not $lookupProcess.HasExited){
            Set-Content -LiteralPath (Join-Path $lookupRun 'stop.request') -Value 'stop' -ErrorAction SilentlyContinue
            if(-not $lookupProcess.WaitForExit(12000)){Stop-Process -Id $lookupProcess.Id -Force -ErrorAction SilentlyContinue}
        }
    }
    if(Test-Path -LiteralPath $accountCacheFile){
        try { foreach($name in (Get-Content -LiteralPath $accountCacheFile -Raw | ConvertFrom-Json)){
            if($name -is [string] -and $name -match '^[A-Za-z0-9_.\[\]-]{1,12}$' -and -not $names.Contains($name)){$names.Add($name)}
        }} catch {}
    }
    if(Test-Path -LiteralPath $savedNickFile){
        $saved=(Get-Content -LiteralPath $savedNickFile -Raw).Trim()
        if($saved -match '^[A-Za-z0-9_.\[\]-]{1,12}$' -and -not $names.Contains($saved)){$names.Insert(0,$saved)}
    }
    return @($names | Sort-Object -Unique)
}
function Select-LocalProfile {
    $profiles=@(Get-LocalProfileNames)
    Write-Host ''
    Write-Host 'Yerel hesaplar (sifre yok):' -ForegroundColor Cyan
    for($i=0;$i -lt $profiles.Count;$i++){Write-Host (" [{0}] {1}" -f ($i+1),$profiles[$i])}
    $newChoice=$profiles.Count+1
    Write-Host (" [{0}] Yeni hesap olustur" -f $newChoice)
    do {
        $choice=Read-Host 'Hesap numarasi (Q cikis)'
        if($choice -match '^[Qq]$'){exit 0}
        if($choice -match '^\d+$' -and [int]$choice -ge 1 -and [int]$choice -le $profiles.Count){return $profiles[[int]$choice-1]}
        if($choice -eq [string]$newChoice){
            do {
                $newName=Read-Host 'Yeni yerel oyuncu adi (1-12 karakter)'
                if($newName -notmatch '^[A-Za-z0-9_.\[\]-]{1,12}$'){
                    Write-Host 'Harf, rakam, nokta, alt cizgi veya tire kullan; en fazla 12 karakter.' -ForegroundColor Yellow
                    $newName=$null
                } elseif($profiles -contains $newName){
                    Write-Host 'Bu ad zaten listede. Mevcut hesabi sec.' -ForegroundColor Yellow
                    $newName=$null
                }
            } while(-not $newName)
            return $newName
        }
        Write-Host 'Listeden bir numara sec.' -ForegroundColor Yellow
    } while($true)
}
if(-not $PSBoundParameters.ContainsKey('Nick')){$Nick=Select-LocalProfile}
if($Nick -notmatch '^[A-Za-z0-9_.\[\]-]{1,12}$'){throw 'Oyuncu adi 1-12 karakter olmali.'}
New-Item -ItemType Directory -Path (Split-Path $savedNickFile) -Force | Out-Null
Set-Content -LiteralPath $savedNickFile -Value $Nick -NoNewline
$cache=@()
if(Test-Path -LiteralPath $accountCacheFile){try{$cache=@(Get-Content -LiteralPath $accountCacheFile -Raw | ConvertFrom-Json)}catch{}}
if($cache -notcontains $Nick){$cache+= $Nick}
$cache | Sort-Object -Unique | ConvertTo-Json | Set-Content -LiteralPath $accountCacheFile -Encoding utf8
function Get-LanAddress {
    $configs=@(Get-NetIPConfiguration | Where-Object {$_.NetAdapter.Status -eq 'Up' -and $_.IPv4Address})
    foreach($config in $configs){
        if($config.NetAdapter.InterfaceDescription -match '(?i)Radmin|Famatech'){
            foreach($address in $config.IPv4Address){if($address.IPAddress -match '^26\.') {return $address}}
        }
    }
    $configs=@($configs | Sort-Object @{Expression={if($_.IPv4DefaultGateway){0}else{1}}})
    foreach($config in $configs){foreach($address in $config.IPv4Address){if($address.IPAddress -match '^(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.)'){return $address}}}
    return $null
}
$menuHost=$false
if(-not $Mode){
    Write-Host ''
    Write-Host ' WOLFTEAM 2012 - LAN'
    Write-Host ' [1] Bu bilgisayarda sunucuyu ve oyunu baslat'
    Write-Host ' [2] Arkadasinin LAN sunucusuna katil'
    $choice=Read-Host 'Secim [1]'
    if(-not $choice -or $choice -eq '1'){
        $Mode='Host';$menuHost=$true
        $address=Get-LanAddress
        if($address){$BindIP=$address.IPAddress}
        $ServerIP=$BindIP
    }elseif($choice -eq '2'){
        $Mode='Join'
        while($true){
            Write-Host 'Ayni agdaki WolfTeam sunuculari araniyor...'
            $found=& $python (Join-Path $PSScriptRoot 'server/lan_discovery.py') --json
            if($LASTEXITCODE -ne 0){throw 'LAN taramasi basarisiz oldu. Oyuncu adi ve ag baglantisini kontrol et.'}
            $servers=@()
            if($found){$servers=@($found | ConvertFrom-Json)}
            if($servers.Count){
                for($i=0;$i -lt $servers.Count;$i++){
                    Write-Host (" [{0}] {1} - {2} kanal ({3})" -f ($i+1),$servers[$i].name,$servers[$i].channels,$servers[$i].ip)
                }
                $pick=Read-Host 'Katilmak icin numara (R yeniden tara, Q cik)'
                if($pick -match '^[Qq]$'){exit 0}
                if($pick -match '^[Rr]$'){continue}
                if($pick -notmatch '^\d+$' -or [int]$pick -lt 1 -or [int]$pick -gt $servers.Count){
                    Write-Host 'Listeden gecerli bir sunucu numarasi sec.' -ForegroundColor Yellow
                    continue
                }
                $ServerIP=$servers[[int]$pick-1].ip
                break
            }
            Write-Host 'Sunucu bulunamadi. Kurucu sunucuyu baslatsin; sonra R ile yeniden tara.' -ForegroundColor Yellow
            $retry=Read-Host '[R] Yeniden tara  [Q] Cik'
            if($retry -match '^[Qq]$'){exit 0}
        }
    }else{throw 'Gecersiz secim.'}
}
if($Mode -eq 'Host' -and $BindIP -eq '127.0.0.1'){
    $address=Get-LanAddress
    if($address){$BindIP=$address.IPAddress;$ServerIP=$BindIP}
}

# Ask for all choices before UAC so the elevated copy never opens a hidden
# prompt. Reuse the PowerShell edition that launched this script (pwsh or PS5).
$identity=[Security.Principal.WindowsIdentity]::GetCurrent()
if (-not $CheckOnly -and -not ([Security.Principal.WindowsPrincipal]$identity).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    $currentShell=(Get-Process -Id $PID).Path
    $forward=@('-NoProfile','-ExecutionPolicy','Bypass','-File',('"'+$PSCommandPath+'"'),
               '-Mode',$Mode,'-ServerIP',$ServerIP,'-BindIP',$BindIP,'-Nick',$Nick)
    if($CheckOnly){$forward+='-CheckOnly'}
    $argumentLine=($forward | ForEach-Object { if($_ -match '[\s"]'){ '"'+($_ -replace '"','\"')+'"' } else { $_ } }) -join ' '
    try {
        # The elevated script owns the full game lifetime. Waiting here makes
        # the unelevated launcher mistake the game's eventual exit code for a
        # UAC launch failure (for example exit code 64).
        [void](Start-Process -FilePath $currentShell -Verb RunAs -WindowStyle Normal -ArgumentList $argumentLine -PassThru -ErrorAction Stop)
    } catch {
        throw "Yonetici baslatma istegi acilamadi veya UAC reddedildi: $($_.Exception.Message)"
    }
    exit 0
}

# Close only this project's prior client. A different WolfTeam installation is
# left untouched. Host-mode cleanup also releases an orphaned local supervisor.
function Stop-ProjectClient {
    $gamePath=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'Wolfteam.exe'))
    $projectRoot=[IO.Path]::GetFullPath($PSScriptRoot)
    $rows=Get-CimInstance Win32_Process -Filter "Name='Wolfteam.exe'" -ErrorAction SilentlyContinue
    foreach($row in $rows){
        if(-not $row.ExecutablePath -or [IO.Path]::GetFullPath($row.ExecutablePath) -ine $gamePath){continue}
        $process=Get-Process -Id $row.ProcessId -ErrorAction SilentlyContinue
        if($process){
            Write-Host 'Eski WolfTeam oturumu kapatiliyor...'
            if($process.MainWindowHandle){[void]$process.CloseMainWindow()}
            try{Wait-Process -Id $row.ProcessId -Timeout 5 -ErrorAction Stop}catch{}
            if(Get-Process -Id $row.ProcessId -ErrorAction SilentlyContinue){Stop-Process -Id $row.ProcessId -Force -ErrorAction SilentlyContinue}
        }
    }
    # A crashed/closed game can leave client_diagnostics.py polling forever.
    # It owns the game Job Object and may keep .runtime/python.exe mapped.
    $diagnostics=Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
        $_.Name -in @('python.exe','pythonw.exe') -and $_.CommandLine -and
        $_.CommandLine.IndexOf('server/client_diagnostics.py',[StringComparison]::OrdinalIgnoreCase) -ge 0 -and
        $_.CommandLine.IndexOf($projectRoot,[StringComparison]::OrdinalIgnoreCase) -ge 0
    }
    foreach($row in $diagnostics){
        Write-Host 'Onceki baslaticidan kalan oyun denetleyicisi temizleniyor...'
        Stop-Process -Id $row.ProcessId -Force -ErrorAction SilentlyContinue
    }
}
function Stop-ProjectHost {
    $root=[IO.Path]::GetFullPath($PSScriptRoot).TrimEnd('\')+'\'
    $uiRows=Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
        $_.Name -in @('python.exe','pythonw.exe') -and $_.CommandLine -and
        $_.CommandLine.IndexOf('inventory_manager.py',[StringComparison]::OrdinalIgnoreCase) -ge 0
    }
    foreach($uiRow in $uiRows){
        $ui=Get-Process -Id $uiRow.ProcessId -ErrorAction SilentlyContinue
        if($ui -and $ui.MainWindowTitle -eq 'WolfTeam 2012 · Yerel Envanter'){
            Write-Host 'Yerel yardimci pencere kapatiliyor; LAN verileri kayitli kalir.'
            if($ui.MainWindowHandle){[void]$ui.CloseMainWindow()}
            try{Wait-Process -Id $uiRow.ProcessId -Timeout 5 -ErrorAction Stop}catch{}
            if(Get-Process -Id $uiRow.ProcessId -ErrorAction SilentlyContinue){Stop-Process -Id $uiRow.ProcessId -Force -ErrorAction SilentlyContinue}
        }
    }
    $rows=Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
        $_.CommandLine -and $_.CommandLine.Replace('\','/').IndexOf('logan/host.py',[StringComparison]::OrdinalIgnoreCase) -ge 0
    }
    foreach($row in $rows){
        $match=[regex]::Match($row.CommandLine,'--run\s+(?:"(?<quoted>[^"]+)"|(?<plain>\S+))')
        if(-not $match.Success){continue}
        $run=if($match.Groups['quoted'].Success){$match.Groups['quoted'].Value}else{$match.Groups['plain'].Value}
        $run=[IO.Path]::GetFullPath($run)
        if(-not $run.StartsWith($root,[StringComparison]::OrdinalIgnoreCase)){continue}
        Write-Host 'Eski LAN sunucu sureci kapatiliyor...'
        Set-Content -LiteralPath (Join-Path $run 'stop.request') -Value 'stop' -ErrorAction SilentlyContinue
        try{Wait-Process -Id $row.ProcessId -Timeout 12 -ErrorAction Stop}catch{}
        if(Get-Process -Id $row.ProcessId -ErrorAction SilentlyContinue){Stop-Process -Id $row.ProcessId -Force -ErrorAction SilentlyContinue}
    }
}
if(-not $CheckOnly){
    Stop-ProjectClient
    if($Mode -eq 'Host'){Stop-ProjectHost}
}

$runtimePath=Join-Path $PSScriptRoot '.runtime/logan'
if($Mode -in 'Host','Server'){
    if(-not(Test-Path -LiteralPath (Join-Path $runtimePath 'classes/wtserver/WTServer.class'))){
        if($CheckOnly){throw 'Yerel oyun sunucusu kurulumu gerekiyor.'}
        & (Join-Path $PSScriptRoot 'logan/setup.ps1')
    }
    $buildStamp=Get-Content -LiteralPath (Join-Path $runtimePath 'build.json') -Raw | ConvertFrom-Json
    $stale=$false
    foreach($file in Get-ChildItem (Join-Path $PSScriptRoot 'logan/src') -Filter '*.java' -Recurse){
        $relative=[IO.Path]::GetRelativePath((Join-Path $PSScriptRoot 'logan'),$file.FullName).Replace('\','/')
        if($buildStamp.$relative -ne (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash){$stale=$true;break}
    }
    if($stale -and -not $CheckOnly){& (Join-Path $PSScriptRoot 'logan/build.ps1')}
}
$targetAddress=if($Mode -eq 'Join'){$ServerIP}else{$BindIP}
$parsed=[Net.IPAddress]::Parse($targetAddress)
if($parsed.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork){throw 'IPv4 gerekli.'}
$isRadmin=$false
if([Net.IPAddress]::IsLoopback($parsed)){$subnet='127.0.0.0/8'}else{
    $route=Find-NetRoute -RemoteIPAddress $targetAddress | Where-Object {$_.PSObject.Properties['IPAddress']} | Select-Object -First 1
    if(-not $route){throw 'LAN rotasi bulunamadi.'}
    $radminTarget=$targetAddress -match '^26\.'
    if($radminTarget){
        $radminAddresses=@(Get-NetIPConfiguration | Where-Object {$_.NetAdapter.Status -eq 'Up' -and $_.NetAdapter.InterfaceDescription -match '(?i)Radmin|Famatech'} | ForEach-Object {$_.IPv4Address} | ForEach-Object {$_.IPAddress} | Where-Object {$_ -match '^26\.'})
        if(-not $radminAddresses.Count){throw 'Radmin VPN bagdastiricisi etkin degil. Iki bilgisayar da ayni Radmin agina baglanmali.'}
        if($radminAddresses -notcontains $route.IPAddress){throw 'Radmin IP rotasi Radmin VPN bagdastiricisini kullanmiyor.'}
        $isRadmin=$true
    }
    $policyArgs=@((Join-Path $PSScriptRoot 'server/network_policy.py'),'--subnet',$route.IPAddress,$route.PrefixLength)
    if($isRadmin){$policyArgs+='--radmin'}
    $subnet=& $python @policyArgs
    if($LASTEXITCODE){throw 'LAN alt agi belirlenemedi.'}
}
$policyFile=Join-Path $PSScriptRoot 'network-policy.json'
$policy=if(Test-Path -LiteralPath $policyFile){Get-Content -LiteralPath $policyFile -Raw | ConvertFrom-Json}else{$null}
if(-not $policy -or $policy.Subnet -ne $subnet -or [bool]$policy.Radmin -ne $isRadmin){
    if($CheckOnly){throw 'Secilen LAN icin ag kurallari kurulmamis.'}
    if($isRadmin){& (Join-Path $PSScriptRoot 'configure_network.ps1') -LanSubnet $subnet -Radmin}
    else{& (Join-Path $PSScriptRoot 'configure_network.ps1') -LanSubnet $subnet}
    $policy=Get-Content -LiteralPath $policyFile -Raw | ConvertFrom-Json
}
$env:PYTHONHOME=$policy.PythonHome
$env:PYTHONPATH=Join-Path $PSScriptRoot 'server'
if(Test-Path -LiteralPath $runtimePython){$python=$runtimePython}
& $python -c 'import ipaddress,sys; n=ipaddress.ip_network(sys.argv[1]); a=ipaddress.ip_address(sys.argv[2]); assert a.version==4 and (a.is_loopback or a in n)' $subnet $targetAddress
if($LASTEXITCODE){throw 'Sunucu secilen yerel agin disinda.'}
if($CheckOnly){Write-Host 'Oyun ve ağ ayarları hazır.';return}
# Scope Java explicitly to this LAN. PHP and database listen only on loopback.
if($Mode -in 'Host','Server'){
    $javaExe=Join-Path $runtimePath 'java/bin/java.exe'
    $group='WolfTeamTS-Logan-LAN'
    $ruleName='WolfTeamTS-Logan-'+($subnet -replace '[^A-Za-z0-9]','_')
    if(-not(Get-NetFirewallRule -Name ($ruleName+'-in') -ErrorAction SilentlyContinue)){
        $firewallArgs=@((Join-Path $PSScriptRoot 'server/network_policy.py'),$subnet)
        if($isRadmin){$firewallArgs+='--radmin'}
        $denied=@((& $python @firewallArgs) | ConvertFrom-Json)
        Get-NetFirewallRule -Group $group -ErrorAction SilentlyContinue | Remove-NetFirewallRule
        foreach($direction in @('Inbound','Outbound')){
            New-NetFirewallRule -Name ($ruleName+'-block-'+$direction) -DisplayName 'WolfTeam LAN dışı bağlantıları engelle' -Group $group -Program $javaExe -Direction $direction -Action Block -RemoteAddress $denied -Profile Any | Out-Null
        }
        New-NetFirewallRule -Name ($ruleName+'-in') -DisplayName 'WolfTeam LAN bağlantısı' -Group $group -Program $javaExe -Direction Inbound -Action Allow -RemoteAddress @('127.0.0.0/8',$subnet) -Profile Any | Out-Null
    }
}
$run=Join-Path $PSScriptRoot ('runs/'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$ownedServer=$null
$started=Get-Date
try{
    if($Mode -in 'Host','Server'){
        $ownedServer=Start-Process -FilePath $python -ArgumentList @('-u','logan/host.py','--bind',$BindIP,'--run',('"'+$run+'"')) -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $run 'server.stdout.log') -RedirectStandardError (Join-Path $run 'server.stderr.log') -PassThru
        $ready=$false
        for($i=0;$i -lt 240;$i++){
            if($ownedServer.HasExited){throw "Sunucu acilamadi: $run"}
            if(Test-Path -LiteralPath (Join-Path $run 'ready.json')){$ready=$true;break}
            Start-Sleep -Milliseconds 250
        }
        if(-not $ready){throw "Sunucu hazir olmadi: $run"}
        $ServerIP=$BindIP
        Write-Host "Sunucu: $ServerIP | Oyuncu: $Nick | LAN sunucusu hazır"
    }
    @{ServerPID=if($ownedServer){$ownedServer.Id}else{$null};ServerIP=$ServerIP;Mode=$Mode;Engine='logan-java';Started=$started.ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $run 'session.json')
    if($Mode -eq 'Server'){$ownedServer.WaitForExit();return}
    & $python 'server/client_diagnostics.py' --root $PSScriptRoot --output $run --nick $Nick --server $ServerIP
    @{SupervisorExitCode=$LASTEXITCODE;Ended=(Get-Date).ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $run 'exit.json')
    Write-Host "Oyun kapandi. Kayitlar: $run"
}finally{
    if($ownedServer -and -not $ownedServer.HasExited){
        Set-Content -LiteralPath (Join-Path $run 'stop.request') -Value 'stop'
        if(-not $ownedServer.WaitForExit(15000)){Stop-Process -Id $ownedServer.Id -Force}
    }
    if(Test-Path -LiteralPath 'd3d9_proxy.log'){Copy-Item -LiteralPath 'd3d9_proxy.log' -Destination $run -ErrorAction SilentlyContinue}
}
