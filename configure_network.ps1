[CmdletBinding()]
param(
    [string]$LanSubnet = '127.0.0.0/8',
    [switch]$Radmin,
    [switch]$Remove
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
. (Join-Path $root 'server/firewall_validation.ps1')
$prefix = 'WolfTeamTS-Offline-'
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
if (-not ([Security.Principal.WindowsPrincipal]$identity).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Network policy installation requires an elevated PowerShell. No game has been started.'
}
if ($Remove) {
    Get-NetFirewallRule -PolicyStore PersistentStore | Where-Object { $_.Group -like "$prefix*" } | Remove-NetFirewallRule
    Remove-Item -LiteralPath (Join-Path $root 'network-policy.json') -ErrorAction SilentlyContinue
    return
}
if (@(Get-NetFirewallProfile -PolicyStore ActiveStore | Where-Object { -not $_.Enabled }).Count) {
    throw 'All Windows Firewall profiles must be enabled.'
}
$bundledPython = Join-Path $root '.runtime/python/python.exe'
$systemPython = Get-Command python.exe -ErrorAction SilentlyContinue
$python = if (Test-Path -LiteralPath $bundledPython) { $bundledPython }
          elseif ($systemPython) { $systemPython.Source }
          else { throw 'Python çalışma zamanı bulunamadi. Dagitim paketi eksik olabilir.' }
$policyArgs = @((Join-Path $root 'server/network_policy.py'), $LanSubnet)
if ($Radmin) { $policyArgs += '--radmin' }
$deniedJson = & $python @policyArgs
if ($LASTEXITCODE) { throw 'Invalid LAN subnet' }
$denied = @($deniedJson | ConvertFrom-Json)
# Windows rejects an explicit IPv6 loopback address in firewall rules. Leave
# ::1 outside the block complement; explicit LAN allows cover IPv4 only.
$allowed = @('127.0.0.0/8', $LanSubnet | Select-Object -Unique)
# Give the server its own executable identity: never install rules for shared Python.
$runtime = Join-Path $root '.runtime'
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
$runtimePython = Join-Path $runtime 'python.exe'
$copyRuntimePython = $true
if ($python -ieq $bundledPython) {
    $copyRuntimePython = $false
    $pythonHome = Split-Path $bundledPython
} elseif (Test-Path -LiteralPath $runtimePython) {
    $sourceHash = (Get-FileHash -LiteralPath $python -Algorithm SHA256).Hash
    $runtimeHash = (Get-FileHash -LiteralPath $runtimePython -Algorithm SHA256).Hash
    if ($sourceHash -eq $runtimeHash) {
        # The runtime copy may currently be mapped by an old game watcher.
        # Identical binaries need no replacement, so leave the live file alone.
        $copyRuntimePython = $false
    } else {
        $runtimeUsers = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
            $_.ExecutablePath -and [IO.Path]::GetFullPath($_.ExecutablePath) -ieq [IO.Path]::GetFullPath($runtimePython)
        })
        if ($runtimeUsers.Count) {
            $ids = ($runtimeUsers.ProcessId -join ', ')
            throw "Eski WolfTeam sureci Python dosyasini kullaniyor (PID $ids). WolfteamBaslat.bat'i yeniden calistir; eski istemci otomatik kapatilacak."
        }
    }
}
if ($copyRuntimePython) {
    Copy-Item -LiteralPath $python -Destination $runtimePython -Force
    $pythonHome = Split-Path $python
    Get-ChildItem -LiteralPath $pythonHome -Filter '*.dll' -File | Copy-Item -Destination $runtime -Force
}
$programs = @(Get-ChildItem -LiteralPath $root -Recurse -File | Where-Object {
    $_.FullName -notlike '*\.git\*' -and $_.FullName -notlike '*\runs\*' -and
    $_.FullName -notlike '*\.runtime\logan\*' -and $_.Extension -in '.exe','.bin','.dat','.xoe','.xse'
} | Where-Object {
    $stream = [IO.File]::OpenRead($_.FullName)
    try { $stream.ReadByte() -eq 77 -and $stream.ReadByte() -eq 90 } finally { $stream.Dispose() }
} | Select-Object -ExpandProperty FullName)
$loganJava = Join-Path $root '.runtime/logan/java/bin/java.exe'
if (Test-Path -LiteralPath $loganJava) { $programs += $loganJava }
$generation = $prefix + [guid]::NewGuid().ToString('N')
$old = @(Get-NetFirewallRule -PolicyStore PersistentStore | Where-Object { $_.Group -like "$prefix*" })
$rules = @()
try {
    foreach ($program in $programs) {
        foreach ($direction in 'Inbound','Outbound') {
            $rule = New-NetFirewallRule -DisplayName "$generation $direction $(Split-Path $program -Leaf)" -Group $generation -Program $program -Direction $direction -Action Block -RemoteAddress $denied -Profile Any -Enabled True -PolicyStore PersistentStore -ErrorAction Stop
            $rules += @{Name=$rule.Name;Program=$program;Direction=$direction;Action='Block';Addresses=$denied}
            $allow = New-NetFirewallRule -DisplayName "$generation LAN $direction $(Split-Path $program -Leaf)" -Group $generation -Program $program -Direction $direction -Action Allow -RemoteAddress $allowed -Profile Any -Enabled True -PolicyStore PersistentStore -ErrorAction Stop
            $rules += @{Name=$allow.Name;Program=$program;Direction=$direction;Action='Allow';Addresses=$allowed}
        }
    }
    Test-OfflineFirewallRules $rules $python
    $manifest = @($programs | ForEach-Object { @{ Path = $_; SHA256 = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash } })
    @{ Subnet=$LanSubnet; Radmin=[bool]$Radmin; Group=$generation; Rules=$rules; Programs=$manifest; PythonHome=$pythonHome; Denied=$denied; Created=(Get-Date).ToString('o') } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $root 'network-policy.json.tmp') -Encoding utf8
    Move-Item -LiteralPath (Join-Path $root 'network-policy.json.tmp') -Destination (Join-Path $root 'network-policy.json') -Force
} catch {
    Get-NetFirewallRule -Group $generation -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    throw
}
foreach ($rule in $old) { $rule | Remove-NetFirewallRule }
Write-Host "Installed offline program policy for $($programs.Count) executables; LAN: $LanSubnet"
Write-Host 'DNS delegated to Windows services and external helper programs still require a traffic audit.'
