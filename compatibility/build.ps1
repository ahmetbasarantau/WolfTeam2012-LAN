[CmdletBinding()]
param([switch]$Install)
$ErrorActionPreference = 'Stop'
$framework = Join-Path $env:WINDIR 'Microsoft.NET/Framework/v4.0.30319'
$out = Join-Path $PSScriptRoot 'build'
New-Item -ItemType Directory -Path $out -Force | Out-Null
& (Join-Path $framework 'csc.exe') /nologo /target:module /platform:x86 /optimize+ "/out:$out/D3DHookFull.netmodule" (Join-Path $PSScriptRoot 'D3DHookFull.cs')
if ($LASTEXITCODE) { throw 'Compatibility module compilation failed.' }
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'd3d9_full.il') -Destination $out
Push-Location $out
try {
    & (Join-Path $framework 'ilasm.exe') /dll /output=d3d9.dll d3d9_full.il
    if ($LASTEXITCODE) { throw 'D3D9 export assembly failed.' }
} finally { Pop-Location }
Get-FileHash -LiteralPath (Join-Path $out 'd3d9.dll'),(Join-Path $out 'D3DHookFull.netmodule') -Algorithm SHA256
if ($Install) {
    $root = Split-Path $PSScriptRoot
    foreach ($file in 'd3d9.dll','D3DHookFull.netmodule') {
        Copy-Item -LiteralPath (Join-Path $out $file) -Destination (Join-Path $root $file) -Force
    }
}
