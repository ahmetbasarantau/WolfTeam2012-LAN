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
