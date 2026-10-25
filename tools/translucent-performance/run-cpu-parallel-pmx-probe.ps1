param(
    [Parameter(Mandatory = $true)][string]$Pmx,
    [string]$Jdk = 'C:/Program Files/Zulu/zulu-21',
    [string]$Dll = 'rust_engine/target/release/mmd_engine.dll'
)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$taskOutput = Join-Path $taskRoot 'build/translucent-performance'
$taskCp = (Get-Content -LiteralPath (Join-Path $taskOutput 'classpath.txt') -Raw).Trim()
$pmxPath = (Resolve-Path -LiteralPath $Pmx).Path
Push-Location $taskRoot
try {
    & (Join-Path $Jdk 'bin/javac.exe') -proc:none -encoding UTF-8 -cp $taskCp -d $taskOutput `
        (Join-Path $PSScriptRoot 'CpuParallelPmxProbe.java')
    if ($LASTEXITCODE -ne 0) { throw 'CPU PMX probe compilation failed' }
    & (Join-Path $Jdk 'bin/java.exe') -cp "$taskOutput;$taskCp" CpuParallelPmxProbe `
        (Resolve-Path -LiteralPath $Dll).Path $pmxPath
    if ($LASTEXITCODE -ne 0) { throw 'CPU PMX parallel probe failed' }
} finally { Pop-Location }
