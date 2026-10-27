[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch {}
# The game runtime is isolated with PYTHONHOME/PYTHONPATH. The full local
# interpreter for Tk must use its own standard library and site-packages.
Remove-Item Env:PYTHONHOME,Env:PYTHONPATH -ErrorAction SilentlyContinue
$root = $PSScriptRoot
$runtimePath = Join-Path $root '.runtime/inventory-python'
$downloadsPath = Join-Path $root '.runtime/downloads'
$pythonExe = Join-Path $runtimePath 'python.exe'
New-Item -ItemType Directory -Path $downloadsPath -Force | Out-Null

function Test-InventoryPython {
    if (-not (Test-Path -LiteralPath $pythonExe -PathType Leaf)) { return $false }
    try {
        & $pythonExe -c 'import tkinter; from PIL import Image, ImageDraw, ImageFilter' 2>$null | Out-Null
        return $LASTEXITCODE -eq 0
    } catch { return $false }
}

function Test-TkPython {
    if (-not (Test-Path -LiteralPath $pythonExe -PathType Leaf)) { return $false }
    try {
        & $pythonExe -c 'import tkinter' 2>$null | Out-Null
        return $LASTEXITCODE -eq 0
    } catch { return $false }
}

if (-not [Environment]::Is64BitOperatingSystem) {
    throw 'Envanter yoneticisi icin 64 bit Windows gereklidir.'
}

if (-not (Test-TkPython)) {
    Write-Host 'Envanter araci icin tasinabilir Python ve Tk arayuzu indiriliyor...' -ForegroundColor Cyan
    $installer = Join-Path $downloadsPath 'python-3.14.10-amd64.exe'
    $url = 'https://www.python.org/ftp/python/3.14.10/python-3.14.10-amd64.exe'
    $expectedHash = '92480239264744fe0386a1bd8275786fefa126e3129e56a68e22b52c73329965'
    if (-not (Test-Path -LiteralPath $installer -PathType Leaf)) {
        try { Invoke-WebRequest -Uri $url -OutFile $installer -UseBasicParsing -TimeoutSec 300 }
        catch {
            Remove-Item -LiteralPath $installer -Force -ErrorAction SilentlyContinue
            throw 'Envanter araci icin Python indirilemedi. Internet baglantisini kontrol edip Kurulum.bat dosyasini yeniden calistirin.'
        }
    }
    if ((Get-FileHash -LiteralPath $installer -Algorithm SHA256).Hash -ne $expectedHash) {
        Remove-Item -LiteralPath $installer -Force
        throw 'Envanter Python paketi dogrulanamadi. Kurulum.bat dosyasini yeniden calistirin.'
    }

    $targetArgument = 'TargetDir="' + $runtimePath + '"'
    $installerArguments = @(
        '/quiet', 'InstallAllUsers=0', $targetArgument,
        'Include_launcher=0', 'Include_pip=1', 'Include_tcltk=1',
        'Include_test=0', 'Include_doc=0', 'PrependPath=0', 'AppendPath=0',
        'Shortcuts=0', 'AssociateFiles=0'
    )
    $install = Start-Process -FilePath $installer -ArgumentList $installerArguments -WindowStyle Hidden -Wait -PassThru
    if ($install.ExitCode -ne 0) { throw 'Envanter icin tasinabilir Python kurulumu tamamlanamadi.' }
}

if (-not (Test-TkPython)) { throw 'Tasinabilir Python/Tk arayuzu hazirlanamadi.' }

if (-not (Test-InventoryPython)) {
    Write-Host 'Envanter araci icin goruntu bileseni hazirlaniyor...' -ForegroundColor Cyan
    & $pythonExe -m pip install --disable-pip-version-check --no-warn-script-location --only-binary=:all: 'Pillow==13.0.0'
    if ($LASTEXITCODE -ne 0) { throw 'Envanter goruntu bileseni indirilemedi. Internet baglantisini kontrol edip yeniden deneyin.' }
}
if (-not (Test-InventoryPython)) { throw 'Envanter Python, Tk veya Pillow bileseni dogrulanamadi.' }
Set-Content -LiteralPath (Join-Path $runtimePath '.wolfteam-inventory-ready') -Value 'Python 3.14.10; Tk; Pillow 13.0.0' -Encoding ascii
Write-Host 'Tasinabilir envanter araci hazir.' -ForegroundColor Green
