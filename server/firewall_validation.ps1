function Test-OfflineFirewallRules($Rules, [string]$Python) {
    $pairs = @()
    # Read each provider table once. Per-rule CIM round trips make every launch
    # take minutes on machines with many executable rules.
    $active = @{}
    Get-NetFirewallRule -PolicyStore ActiveStore -Name @($Rules.Name) -ErrorAction Stop | ForEach-Object { $active[$_.Name] = $_ }
    $applications = @{}
    Get-NetFirewallApplicationFilter -PolicyStore ActiveStore -ErrorAction Stop | ForEach-Object { $applications[$_.InstanceID] = $_ }
    $ports = @{}
    Get-NetFirewallPortFilter -PolicyStore ActiveStore -ErrorAction Stop | ForEach-Object { $ports[$_.InstanceID] = $_ }
    $addresses = @{}
    Get-NetFirewallAddressFilter -PolicyStore ActiveStore -ErrorAction Stop | ForEach-Object { $addresses[$_.InstanceID] = $_ }
    foreach ($spec in $Rules) {
        $rule = $active[$spec.Name]
        if (-not $rule -or $rule.Enabled -ne 'True' -or $rule.Action -ne $spec.Action -or $rule.Direction -ne $spec.Direction -or $rule.Profile -ne 'Any') {
            throw "Firewall rule changed or is inactive: $($spec.Name)"
        }
        $application = $applications[$rule.InstanceID]
        if (-not $application -or $application.Program -ne $spec.Program) { throw "Firewall program scope changed: $($spec.Name)" }
        $port = $ports[$rule.InstanceID]
        if (-not $port -or $port.Protocol -ne 'Any' -or $port.LocalPort -ne 'Any' -or $port.RemotePort -ne 'Any') {
            throw "Firewall protocol/port scope changed: $($spec.Name)"
        }
        $filter = $addresses[$rule.InstanceID]
        if (-not $filter -or @($filter.LocalAddress).Count -ne 1 -or $filter.LocalAddress -ne 'Any') {
            throw "Firewall local address scope changed: $($spec.Name)"
        }
        $pairs += @{name=$spec.Name; action=$spec.Action; expected=@($spec.Addresses); actual=@($filter.RemoteAddress)}
    }
    ConvertTo-Json -InputObject $pairs -Depth 5 -Compress | & $Python (Join-Path $PSScriptRoot 'network_policy.py') --check-addresses
    if ($LASTEXITCODE) { throw 'Firewall address scope validation failed.' }
}
