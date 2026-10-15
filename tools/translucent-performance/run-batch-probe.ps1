param([string]$Jdk = 'C:\Program Files\Zulu\zulu-21')
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$taskOutput = Join-Path $taskRoot 'build/translucent-performance'
$taskCp = (Get-Content -LiteralPath (Join-Path $taskOutput 'classpath.txt') -Raw).Trim()
# The validation Gradle task supplies the real Minecraft runtime; add Windows GLFW natives.
$taskCache = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
foreach ($taskModule in @('lwjgl', 'lwjgl-glfw', 'lwjgl-opengl')) {
    $taskNative = Get-ChildItem -LiteralPath (Join-Path $taskCache "org.lwjgl/$taskModule/3.3.3") -Recurse -Filter '*-natives-windows.jar' | Select-Object -First 1
    if (!$taskNative) { throw "Missing Windows native: $taskModule" }
    $taskCp += ';' + $taskNative.FullName
}
Push-Location $taskRoot
try {
    & (Join-Path $Jdk 'bin/javac.exe') -proc:none -encoding UTF-8 -cp $taskCp -d $taskOutput `
        (Join-Path $PSScriptRoot 'BatchGlProbe.java') (Join-Path $PSScriptRoot 'CacheGlProbe.java') (Join-Path $PSScriptRoot 'GpuTimerGlProbe.java') `
        (Join-Path $PSScriptRoot 'GpuPreparationGlProbe.java') (Join-Path $PSScriptRoot 'StaticBufferGlProbe.java')
    if ($LASTEXITCODE -ne 0) { throw 'GL probe compilation failed' }
    & (Join-Path $Jdk 'bin/java.exe') -cp "$taskOutput;$taskCp" com.shiroha.mmdskin.render.material.BatchGlProbe
    if ($LASTEXITCODE -ne 0) { throw 'Batch GL probe failed' }
    & (Join-Path $Jdk 'bin/java.exe') -cp "$taskOutput;$taskCp" CacheGlProbe
    if ($LASTEXITCODE -ne 0) { throw 'Cache/timer GL probe failed' }
    & (Join-Path $Jdk 'bin/java.exe') -cp "$taskOutput;$taskCp" com.shiroha.mmdskin.render.material.GpuPreparationGlProbe
    if ($LASTEXITCODE -ne 0) { throw 'GPU preparation GL probe failed' }
    & (Join-Path $Jdk 'bin/java.exe') -cp "$taskOutput;$taskCp" StaticBufferGlProbe
    if ($LASTEXITCODE -ne 0) { throw 'Static buffer GL probe failed' }
    & (Join-Path $Jdk 'bin/java.exe') -cp "$taskOutput;$taskCp" StaticBufferGlProbe 0
    if ($LASTEXITCODE -ne 0) { throw 'Static buffer budget GL probe failed' }
} finally { Pop-Location }
