param([string]$Jdk = 'C:\Program Files\Zulu\zulu-21', [string]$BslPack = '',
    [string]$Instance = 'D:\minecraft\.minecraft\versions\1.21.1-NeoForge_21.1.252')
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
if ($BslPack) {
    $taskIris = Get-ChildItem -LiteralPath (Join-Path $Instance 'mods') -Filter 'iris-*.jar' | Select-Object -First 1
    $taskCp += ';' + (Join-Path $taskRoot 'build/iris-toon-probe')
    $taskCp += ';' + (Join-Path $taskRoot 'build/iris-toon-probe/jcpp.jar') + ';' + $taskIris.FullName
}
Push-Location $taskRoot
try {
    & (Join-Path $Jdk 'bin/javac.exe') -proc:none -encoding UTF-8 -cp $taskCp -d $taskOutput `
        (Join-Path $PSScriptRoot 'BlockEntityTransparencyGlProbe.java')
    if ($LASTEXITCODE -ne 0) { throw 'Block entity probe compilation failed' }
    $taskArguments = @('-cp', "$taskCp;$taskOutput", 'com.shiroha.mmdskin.render.material.BlockEntityTransparencyGlProbe')
    if ($BslPack) { $taskArguments += $BslPack }
    & (Join-Path $Jdk 'bin/java.exe') @taskArguments
    if ($LASTEXITCODE -ne 0) { throw 'Block entity transparency probe failed' }
} finally { Pop-Location }
