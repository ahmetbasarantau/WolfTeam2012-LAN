[CmdletBinding()]
param(
    [ValidateSet('Host','Join')][string]$Role='Host',
    [switch]$PythonOnly
)

$ErrorActionPreference = 'Stop'
# Windows PowerShell 5 may otherwise negotiate an obsolete TLS version.
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch {}
if (-not [Environment]::Is64BitOperatingSystem) {
    throw 'WolfTeam LAN kurulumu icin 64 bit Windows gereklidir.'
}
$root = $PSScriptRoot
if ($PythonOnly) { $Role = 'Join' }
$runtimePath = Join-Path $root '.runtime'
$pythonPath = Join-Path $runtimePath 'python'
$pythonExe = Join-Path $pythonPath 'python.exe'
$downloadsPath = Join-Path $runtimePath 'downloads'
New-Item -ItemType Directory -Path $downloadsPath -Force | Out-Null

function Test-PythonRuntime([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $false }
    try {
        $ErrorActionPreference = 'SilentlyContinue'
        & $Path -c 'import asyncio, ipaddress, json, socket' 2>$null | Out-Null
        return $LASTEXITCODE -eq 0
    } catch { return $false }
}

Write-Host ''
Write-Host 'WolfTeam 2012 yerel oyun kurulumu' -ForegroundColor Cyan
Write-Host 'Bu klasorde oyun istemcisi ve wolf.xfs bulunmali.'
$missing = @('Wolfteam.exe','wolf.xfs','sound.xfs') | Where-Object { -not (Test-Path -LiteralPath (Join-Path $root $_) -PathType Leaf) }
if ($missing.Count) {
    throw ('Oyun dosyalari eksik: ' + ($missing -join ', ') + '. Archive.org oyun arsivini bu klasore cikarin: https://archive.org/details/wolfteamturkiye')
}

if (-not (Test-PythonRuntime $pythonExe)) {
    Write-Host 'Oyun icin tasinabilir Python indiriliyor...' -ForegroundColor Cyan
    $archive = Join-Path $downloadsPath 'python-3.14.10-embed-amd64.zip'
    $url = 'https://www.python.org/ftp/python/3.14.10/python-3.14.10-embed-amd64.zip'
    $expectedHash = '90905E64A840557D8C1BC4F5B74DB3D7EE85E3FFB217611CAC1C7813ACEA4159'
    if (-not (Test-Path -LiteralPath $archive)) {
        try { Invoke-WebRequest -Uri $url -OutFile $archive -UseBasicParsing -TimeoutSec 300 }
        catch {
            Remove-Item -LiteralPath $archive -Force -ErrorAction SilentlyContinue
            throw 'Python indirilemedi. İnternet bağlantısını kontrol edip Kurulum.bat dosyasını yeniden çalıştırın.'
        }
    }
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $expectedHash) {
        Remove-Item -LiteralPath $archive -Force
        throw 'Python indirmesi dogrulanamadi. Kurulum dosyasini yeniden calistirin.'
    }
    New-Item -ItemType Directory -Path $pythonPath -Force | Out-Null
    Expand-Archive -LiteralPath $archive -DestinationPath $pythonPath -Force
}
if (-not (Test-PythonRuntime $pythonExe)) {
    throw 'Tasinabilir Python acilamadi. .runtime klasorunu silip Kurulum.bat dosyasini yeniden calistirin.'
}
$env:PYTHONHOME = $pythonPath
$env:PYTHONPATH = Join-Path $root 'server'
Write-Host 'Oyun calisma ortami hazir.' -ForegroundColor Green

$compatibilityModule = Join-Path $root 'D3DHookFull.netmodule'
$compatibilityDll = Join-Path $root 'd3d9.dll'
if ((Test-Path -LiteralPath $compatibilityModule -PathType Leaf) -and
    (Test-Path -LiteralPath $compatibilityDll -PathType Leaf)) {
    Write-Host 'Direct3D uyumluluk dosyalari pakette hazir.' -ForegroundColor Green
} else {
    Write-Host 'Direct3D uyumluluk dosyalari derleniyor...' -ForegroundColor Cyan
    $compatibilityBuild = Join-Path $root 'compatibility/build.ps1'
    if (-not (Test-Path -LiteralPath $compatibilityBuild -PathType Leaf)) {
        throw 'Uyumluluk dosyalari pakette veya kaynak klasorunde bulunamadi.'
    }
    & $compatibilityBuild -Install
    if ($LASTEXITCODE) { throw 'Direct3D uyumluluk dosyalari hazirlanamadi.' }
}

if ($Role -eq 'Host') {
    Write-Host 'Sunucu ve envanter araci icin tasinabilir bilesenler hazirlaniyor...' -ForegroundColor Cyan
    & (Join-Path $root 'inventory_setup.ps1')
    if ($LASTEXITCODE) { throw 'Tasinabilir envanter araci kurulumu tamamlanamadi.' }
    Write-Host 'Oyunun esya ve set listeleri hazirlaniyor...' -ForegroundColor Cyan
    $catalogTool = Join-Path $root 'logan/tools/import_asset_catalog.py'
    $bundleTool = Join-Path $root 'logan/tools/import_client_bundles.py'
    $assetCatalog = Join-Path $root 'logan/web/wolfteam/asset_catalog.json'
    $bundleCatalog = Join-Path $root 'logan/web/wolfteam/client_bundles.json'
    & $pythonExe $catalogTool (Join-Path $root 'wolf.xfs') $assetCatalog
    if ($LASTEXITCODE) { throw 'Esya listesi hazirlanamadi. Ekrandaki hata ile birlikte Wolfteam oyun klasorunu gonder.' }
    & $pythonExe $bundleTool (Join-Path $root 'wolf.xfs') $bundleCatalog
    if ($LASTEXITCODE) { throw 'Set listesi hazirlanamadi. Ekrandaki hata ile birlikte Wolfteam oyun klasorunu gonder.' }
    Write-Host 'LAN sunucusu icin gerekli bilesenler kuruluyor; ilk kurulumda biraz surebilir...' -ForegroundColor Cyan
    & (Join-Path $root 'logan/setup.ps1')
    if ($LASTEXITCODE) { throw 'LAN sunucusu kurulumu tamamlanamadi.' }
}

Write-Host ''
if ($Role -eq 'Join') {
    Write-Host 'Kurulum tamamlandi. Simdi WolfteamBaslat.bat dosyasini acip [2] secin.' -ForegroundColor Green
} else {
    Write-Host 'Kurulum tamamlandi. Sunucuyu baslatmak icin WolfteamBaslat.bat dosyasini acip [1] secin.' -ForegroundColor Green
}
