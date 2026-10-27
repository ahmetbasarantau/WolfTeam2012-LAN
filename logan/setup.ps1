$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$runtimePath = Join-Path $root '.runtime/logan'
New-Item -ItemType Directory -Path (Join-Path $runtimePath 'downloads') -Force | Out-Null
function Get-VerifiedArchive($url, $destination, $expectedHash) {
    if (-not (Test-Path -LiteralPath $destination)) { Invoke-WebRequest -Uri $url -OutFile $destination }
    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $expectedHash) { throw "Archive checksum mismatch: $destination" }
}
if (-not (Test-Path -LiteralPath (Join-Path $runtimePath 'php/php.exe'))) {
    $archive = Join-Path $runtimePath 'downloads/php.zip'
    Get-VerifiedArchive 'https://downloads.php.net/~windows/releases/php-8.4.27-1-nts-Win32-vs17-x64.zip' $archive '93BD8A815A52156606FB906D0573437908C61F96E2CC52C03A51E36A41BF5236'
    Expand-Archive -LiteralPath $archive -DestinationPath (Join-Path $runtimePath 'php') -Force
}
if (-not (Test-Path -LiteralPath (Join-Path $runtimePath 'mariadb/mariadb-11.4.8-winx64/bin/mariadbd.exe'))) {
    $archive = Join-Path $runtimePath 'downloads/mariadb.zip'
    $base = 'https://archive.mariadb.org/mariadb-11.4.8/winx64-packages/'
    $hashList = (Invoke-WebRequest -Uri ($base + 'sha256sums.txt')).Content
    $line = @($hashList -split "`n" | Where-Object { $_ -match '\s\*?mariadb-11\.4\.8-winx64\.zip\s*$' })[0]
    if (-not $line) { throw 'MariaDB archive checksum is unavailable.' }
    Get-VerifiedArchive ($base + 'mariadb-11.4.8-winx64.zip') $archive ($line.Trim() -split '\s+')[0]
    Expand-Archive -LiteralPath $archive -DestinationPath (Join-Path $runtimePath 'mariadb') -Force
}
if (-not (Test-Path -LiteralPath (Join-Path $runtimePath 'java/bin/java.exe'))) {
    $javaInfoFile = Join-Path $runtimePath 'java-properties.txt'
    $javaProcess = Start-Process -FilePath (Get-Command java).Source -ArgumentList @('-XshowSettings:properties','-version') -RedirectStandardError $javaInfoFile -WindowStyle Hidden -Wait -PassThru
    if ($javaProcess.ExitCode) { throw 'Java runtime could not be inspected.' }
    $javaInfo = Get-Content -LiteralPath $javaInfoFile -Raw
    $javaHomePath = [regex]::Match($javaInfo, 'java.home\s*=\s*([^\r\n]+)').Groups[1].Value.Trim()
    if (-not (Test-Path -LiteralPath (Join-Path $javaHomePath 'bin/javac.exe'))) { throw 'A JDK 17 or newer is needed for the initial build.' }
    Copy-Item -LiteralPath $javaHomePath -Destination (Join-Path $runtimePath 'java') -Recurse
}
& (Join-Path $PSScriptRoot 'build.ps1')
