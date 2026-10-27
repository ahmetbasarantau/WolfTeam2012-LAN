$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$outputPath = Join-Path $root '.runtime/logan/classes'
New-Item -ItemType Directory -Path $outputPath -Force | Out-Null
$compiler = Join-Path $root '.runtime/logan/java/bin/javac.exe'
if (-not (Test-Path -LiteralPath $compiler)) { $compiler = (Get-Command javac -ErrorAction Stop).Source }
$sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src') -Recurse -Filter '*.java' -File | Sort-Object FullName)
$argsFile = Join-Path $root '.runtime/logan/sources.txt'
[IO.File]::WriteAllLines($argsFile, [string[]]@($sources | ForEach-Object { '"' + $_.FullName.Replace('\','/') + '"' }), [Text.UTF8Encoding]::new($false))
& $compiler -encoding UTF-8 --release 17 -d $outputPath ('@' + $argsFile)
if ($LASTEXITCODE) { throw 'Yerel oyun sunucusu derlenemedi.' }
$stamp = @{}
foreach ($source in $sources) {
    $relative = [IO.Path]::GetRelativePath($PSScriptRoot, $source.FullName).Replace('\','/')
    $stamp[$relative] = (Get-FileHash -LiteralPath $source.FullName -Algorithm SHA256).Hash
}
$stamp | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root '.runtime/logan/build.json') -Encoding utf8
Write-Host "Yerel oyun sunucusu derlendi ($($sources.Count) kaynak dosyası)."
