param(
    [string]$Jdk = 'C:\Program Files\Zulu\zulu-21',
    [string]$Dll = 'rust_engine/target/release/mmd_engine.dll',
    [string]$LegacyDll = ''
)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$taskOutput = Join-Path $taskRoot 'build/translucent-performance'
$taskCp = (Get-Content -LiteralPath (Join-Path $taskOutput 'classpath.txt') -Raw).Trim()
Push-Location $taskRoot
try {
    & (Join-Path $Jdk 'bin/javac.exe') -proc:none -encoding UTF-8 -cp $taskCp -d $taskOutput (Join-Path $PSScriptRoot 'NativeProfileProbe.java')
    if ($LASTEXITCODE -ne 0) { throw 'Native probe compilation failed' }
    & (Join-Path $Jdk 'bin/java.exe') -cp "$taskOutput;$taskCp" NativeProfileProbe (Resolve-Path -LiteralPath $Dll).Path $taskOutput
    if ($LASTEXITCODE -ne 0) { throw 'Native profile probe failed' }
    if ($LegacyDll) {
        & (Join-Path $Jdk 'bin/java.exe') -cp "$taskOutput;$taskCp" NativeProfileProbe (Resolve-Path -LiteralPath $LegacyDll).Path $taskOutput legacy
        if ($LASTEXITCODE -ne 0) { throw 'Legacy profile fallback probe failed' }
    }
} finally { Pop-Location }
