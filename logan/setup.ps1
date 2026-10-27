$ErrorActionPreference = 'Stop'
# Keep first-time downloads working on older Windows PowerShell installations.
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch {}
$root = Split-Path $PSScriptRoot -Parent
$runtimePath = Join-Path $root '.runtime/logan'
New-Item -ItemType Directory -Path (Join-Path $runtimePath 'downloads') -Force | Out-Null
function Get-VerifiedArchive($url, $destination, $expectedHash) {
    for ($attempt = 1; $attempt -le 2; $attempt++) {
        if (Test-Path -LiteralPath $destination) {
            try {
                if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -eq $expectedHash) { return }
            } catch {}
            Remove-Item -LiteralPath $destination -Force -ErrorAction SilentlyContinue
        }
        try {
            Invoke-WebRequest -Uri $url -OutFile $destination -UseBasicParsing -TimeoutSec 900
            if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -eq $expectedHash) { return }
        } catch {}
        Remove-Item -LiteralPath $destination -Force -ErrorAction SilentlyContinue
    }
    throw "İndirme doğrulanamadı: $destination. Kurulum.bat dosyasını yeniden çalıştırın."
}
if (-not (Test-Path -LiteralPath (Join-Path $runtimePath 'php/php.exe'))) {
    $archive = Join-Path $runtimePath 'downloads/php.zip'
    Get-VerifiedArchive 'https://downloads.php.net/~windows/releases/php-8.4.27-1-nts-Win32-vs17-x64.zip' $archive '93BD8A815A52156606FB906D0573437908C61F96E2CC52C03A51E36A41BF5236'
    Expand-Archive -LiteralPath $archive -DestinationPath (Join-Path $runtimePath 'php') -Force
}
if (-not (Test-Path -LiteralPath (Join-Path $runtimePath 'mariadb/mariadb-11.4.8-winx64/bin/mariadbd.exe'))) {
    $archive = Join-Path $runtimePath 'downloads/mariadb.zip'
    $base = 'https://archive.mariadb.org/mariadb-11.4.8/winx64-packages/'
    $hashList = (Invoke-WebRequest -Uri ($base + 'sha256sums.txt') -UseBasicParsing -TimeoutSec 60).Content
    $line = @($hashList -split "`n" | Where-Object { $_ -match '\s\*?mariadb-11\.4\.8-winx64\.zip\s*$' })[0]
    if (-not $line) { throw 'MariaDB archive checksum is unavailable.' }
    Get-VerifiedArchive ($base + 'mariadb-11.4.8-winx64.zip') $archive ($line.Trim() -split '\s+')[0]
    Expand-Archive -LiteralPath $archive -DestinationPath (Join-Path $runtimePath 'mariadb') -Force
}
if (-not (Test-Path -LiteralPath (Join-Path $runtimePath 'java/bin/java.exe')) -or
    -not (Test-Path -LiteralPath (Join-Path $runtimePath 'java/bin/javac.exe'))) {
    Write-Host 'Tasinabilir Java JDK 17 indiriliyor...' -ForegroundColor Cyan
    $api = 'https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'
    try {
        $releases = @(Invoke-RestMethod -Uri $api -UseBasicParsing -TimeoutSec 60)
        $package = $releases | Where-Object { $_.binary.image_type -eq 'jdk' -and $_.binary.package.link -and $_.binary.package.checksum } | Select-Object -First 1
    } catch {
        throw 'Tasinabilir Java bilgisi alinamadi. Internet baglantisini kontrol edin.'
    }
    if (-not $package) { throw 'Tasinabilir Java indirme bilgisi bulunamadi.' }

    $archive = Join-Path $runtimePath 'downloads/temurin-jdk17.zip'
    Get-VerifiedArchive $package.binary.package.link $archive $package.binary.package.checksum
    $extractPath = Join-Path $runtimePath ('jdk-extract-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $extractPath -Force | Out-Null
    try {
        Expand-Archive -LiteralPath $archive -DestinationPath $extractPath -Force
        $javac = Get-ChildItem -LiteralPath $extractPath -Filter 'javac.exe' -File -Recurse | Select-Object -First 1
        if (-not $javac) { throw 'Tasinabilir Java arsivinde derleyici bulunamadi.' }
        $javaHome = Split-Path (Split-Path $javac.FullName -Parent) -Parent
        if (-not (Test-Path -LiteralPath (Join-Path $javaHome 'bin/java.exe'))) { throw 'Tasinabilir Java arsivi eksik.' }
        $javaPath = Join-Path $runtimePath 'java'
        if (Test-Path -LiteralPath $javaPath) { Remove-Item -LiteralPath $javaPath -Recurse -Force }
        Move-Item -LiteralPath $javaHome -Destination $javaPath
    } finally {
        Remove-Item -LiteralPath $extractPath -Recurse -Force -ErrorAction SilentlyContinue
    }
}
& (Join-Path $PSScriptRoot 'build.ps1')
