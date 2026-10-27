[CmdletBinding()]
param([switch]$PythonOnly)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$runtimePath = Join-Path $root '.runtime'
$pythonPath = Join-Path $runtimePath 'python'
$pythonExe = Join-Path $pythonPath 'python.exe'
$downloadsPath = Join-Path $runtimePath 'downloads'
New-Item -ItemType Directory -Path $downloadsPath -Force | Out-Null

function Test-PythonRuntime($path) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $false }
    & $path -c 'import asyncio, ipaddress, json, socket, sys; print(sys.version)' *> $null
    return $LASTEXITCODE -eq 0
}

if (-not (Test-PythonRuntime $pythonExe)) {
    Write-Host 'Tasinabilir Python indiriliyor...' -ForegroundColor Cyan
    $archive = Join-Path $downloadsPath 'python-3.14.10-embed-amd64.zip'
    $url = 'https://www.python.org/ftp/python/3.14.10/python-3.14.10-embed-amd64.zip'
    $expectedHash = '90905E64A840557D8C1BC4F5B74DB3D7EE85E3FFB217611CAC1C7813ACEA4159'
    if (-not (Test-Path -LiteralPath $archive)) {
        Invoke-WebRequest -Uri $url -OutFile $archive
    }
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $expectedHash) {
        Remove-Item -LiteralPath $archive -Force
        throw 'Python arsivinin SHA-256 dogrulamasi basarisiz. Kurulumu yeniden calistirin.'
    }
    New-Item -ItemType Directory -Path $pythonPath -Force | Out-Null
    Expand-Archive -LiteralPath $archive -DestinationPath $pythonPath -Force
}

if (-not (Test-PythonRuntime $pythonExe)) {
    throw 'Python kurulumu tamamlanamadi. Indirilen Python calistirilamiyor.'
}
Write-Host 'Tasinabilir Python hazir.' -ForegroundColor Green

$framework = Join-Path $env:WINDIR 'Microsoft.NET/Framework/v4.0.30319'
if (-not (Test-Path -LiteralPath (Join-Path $framework 'csc.exe')) -or
    -not (Test-Path -LiteralPath (Join-Path $framework 'ilasm.exe'))) {
    throw 'Direct3D uyumluluk derlemesi icin Windows .NET Framework csc.exe ve ilasm.exe gerekli.'
}
Write-Host 'Direct3D uyumluluk bilesenleri kuruluyor...' -ForegroundColor Cyan
& (Join-Path $root 'compatibility/build.ps1') -Install
if ($LASTEXITCODE) { throw 'Direct3D uyumluluk bilesenleri derlenemedi.' }

$xfs = Join-Path $root 'wolf.xfs'
if (Test-Path -LiteralPath $xfs) {
    Write-Host 'Yerel oyun esya kataloglari olusturuluyor...' -ForegroundColor Cyan
    & $pythonExe 'logan/tools/import_asset_catalog.py' 'wolf.xfs' 'logan/web/wolfteam/asset_catalog.json'
    if ($LASTEXITCODE) { throw 'Yerel esya katalogu olusturulamadi.' }
    & $pythonExe 'logan/tools/import_client_bundles.py' 'wolf.xfs' 'logan/web/wolfteam/client_bundles.json'
    if ($LASTEXITCODE) { throw 'Yerel set katalogu olusturulamadi.' }
}

if (-not $PythonOnly) {
    if (-not (Get-Command java.exe -ErrorAction SilentlyContinue) -or
        -not (Get-Command javac.exe -ErrorAction SilentlyContinue)) {
        throw 'Sunucu kurulumu icin Java JDK 17 veya yenisi PATH icinde olmali. Katilimci bilgisayarinda 2 secin.'
    }
    Write-Host 'Yerel sunucu bilesenleri kuruluyor...' -ForegroundColor Cyan
    & (Join-Path $root 'logan/setup.ps1')
    if ($LASTEXITCODE) { throw 'Yerel sunucu kurulumu basarisiz oldu.' }
}

Write-Host ''
if ($PythonOnly) {
    Write-Host 'Katilimci kurulumu tamamlandi. WolfteamBaslat.bat dosyasini calistirabilirsiniz.' -ForegroundColor Green
} else {
    Write-Host 'Kurulum tamamlandi. WolfteamBaslat.bat dosyasini calistirabilirsiniz.' -ForegroundColor Green
}
