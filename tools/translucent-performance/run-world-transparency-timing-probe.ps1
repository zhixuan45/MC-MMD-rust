param([string]$Jdk = 'C:\Program Files\Zulu\zulu-21')
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$taskOutput = Join-Path $taskRoot 'build/translucent-performance'
$taskCp = (Get-Content -LiteralPath (Join-Path $taskOutput 'classpath.txt') -Raw).Trim()
foreach ($taskModule in @('lwjgl', 'lwjgl-glfw', 'lwjgl-opengl')) {
    $taskCache = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/org.lwjgl/$taskModule/3.3.3"
    $taskNative = Get-ChildItem -LiteralPath $taskCache -Recurse -Filter '*-natives-windows.jar' | Select-Object -First 1
    if (!$taskNative) { throw "Missing Windows native: $taskModule" }
    $taskCp += ';' + $taskNative.FullName
}
& (Join-Path $Jdk 'bin/javac.exe') -proc:none -encoding UTF-8 -cp $taskCp -d $taskOutput `
    (Join-Path $PSScriptRoot 'WorldTransparencyTimingGlProbe.java')
if ($LASTEXITCODE -ne 0) { throw 'World transparency timing probe compilation failed' }
& (Join-Path $Jdk 'bin/java.exe') -cp "$taskCp;$taskOutput" `
    'com.shiroha.mmdskin.render.pipeline.transparency.WorldTransparencyTimingGlProbe'
if ($LASTEXITCODE -ne 0) { throw 'Timing/readback probe failed' }
