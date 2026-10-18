param([string]$Jdk = 'C:\Program Files\Zulu\zulu-21')
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$taskOutput = Join-Path $taskRoot 'build/translucent-performance'
$taskCp = (Get-Content -LiteralPath (Join-Path $taskOutput 'classpath.txt') -Raw).Trim()
$taskCache = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
foreach ($taskModule in @('lwjgl', 'lwjgl-glfw', 'lwjgl-opengl')) {
    $taskNative = Get-ChildItem -LiteralPath (Join-Path $taskCache "org.lwjgl/$taskModule/3.3.3") -Recurse -Filter '*-natives-windows.jar' | Select-Object -First 1
    if (!$taskNative) { throw "Missing Windows native: $taskModule" }
    $taskCp += ';' + $taskNative.FullName
}
Push-Location $taskRoot
try {
    & (Join-Path $Jdk 'bin/javac.exe') -proc:none -encoding UTF-8 -cp $taskCp -d $taskOutput `
        (Join-Path $PSScriptRoot 'VanillaEntityProbe.java') (Join-Path $PSScriptRoot 'VanillaEntityGlProbe.java')
    if ($LASTEXITCODE -ne 0) { throw 'Vanilla probe compilation failed' }
    & (Join-Path $Jdk 'bin/java.exe') -cp "$taskOutput;$taskCp" probe.VanillaEntityGlProbe
    if ($LASTEXITCODE -ne 0) { throw 'Vanilla entity GL probe failed' }
} finally { Pop-Location }
