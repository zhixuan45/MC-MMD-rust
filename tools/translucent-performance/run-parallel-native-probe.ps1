param([string]$Jdk='C:/Program Files/Zulu/zulu-21', [string]$Dll='rust_engine/target/release/mmd_engine.dll')
$ErrorActionPreference='Stop'
$taskRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$taskOutput=Join-Path $taskRoot 'build/translucent-performance'
$taskCp=(Get-Content (Join-Path $taskOutput 'classpath.txt') -Raw).Trim()
Push-Location $taskRoot
try {
    & (Join-Path $Jdk 'bin/javac.exe') -proc:none -encoding UTF-8 -cp $taskCp -d $taskOutput `
        (Join-Path $PSScriptRoot 'NativeProfileProbe.java') (Join-Path $PSScriptRoot 'ParallelEvaluationProbe.java')
    if ($LASTEXITCODE -ne 0) { throw 'Parallel JNI probe compilation failed' }
    & (Join-Path $Jdk 'bin/java.exe') -cp "$taskOutput;$taskCp" ParallelEvaluationProbe (Resolve-Path $Dll).Path $taskOutput
    if ($LASTEXITCODE -ne 0) { throw 'Parallel JNI probe failed' }
} finally { Pop-Location }
