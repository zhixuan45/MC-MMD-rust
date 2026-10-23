param([string]$Jdk = 'C:\Program Files\Zulu\zulu-21', [string]$BslPack = '', [switch]$WaterOrder,
    [switch]$SharedWaterReference, [switch]$NativeWater, [switch]$WaterPerformance,
    [string]$BaselineRuntime = '',
    [ValidateRange(64, 2048)][int]$PerformanceSize = 1024,
    [string]$Instance = 'D:\minecraft\.minecraft\versions\1.21.1-NeoForge_21.1.252')
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$taskOutput = Join-Path $taskRoot 'build/translucent-performance'
$taskCp = (Get-Content -LiteralPath (Join-Path $taskOutput 'classpath.txt') -Raw).Trim()
if ($BaselineRuntime) {
    $taskCp = (Join-Path $BaselineRuntime 'classes') + ';' + (Join-Path $BaselineRuntime 'resources') + ';' + $taskCp
}
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
    if ($NativeWater) {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $taskZip = [System.IO.Compression.ZipFile]::OpenRead($taskIris.FullName)
        try {
            $taskEntry = $taskZip.Entries | Where-Object { $_.FullName -match '/glsl-transformer-[^/]+\.jar$' } | Select-Object -First 1
            $taskGlsl = Join-Path $taskRoot 'build/iris-toon-probe/glsl-transformer.jar'
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($taskEntry, $taskGlsl, $true)
            $taskCp += ';' + $taskGlsl
        } finally { $taskZip.Dispose() }
        $taskAntlr = Get-ChildItem (Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/org.antlr/antlr4-runtime') -Recurse -Filter 'antlr4-runtime-*.jar' |
            Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1
        if (!$taskAntlr) { throw 'Missing ANTLR runtime for actual Iris shader transform' }
        $taskCp += ';' + $taskAntlr.FullName
    }
    # Some Sodium distributions wrap their actual runtime in a JarJar archive.
    $taskSodium = Get-ChildItem -LiteralPath (Join-Path $Instance 'mods') -Filter '*sodium*.jar' | Select-Object -First 1
    if ($taskSodium) {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $taskZip = [System.IO.Compression.ZipFile]::OpenRead($taskSodium.FullName)
        try {
            $taskEntry = $taskZip.Entries | Where-Object { $_.FullName -like '*-mod.jar' } | Select-Object -First 1
            if ($taskEntry) {
                $taskSodiumRuntime = Join-Path $taskOutput 'sodium-runtime.jar'
                [System.IO.Compression.ZipFileExtensions]::ExtractToFile($taskEntry, $taskSodiumRuntime, $true)
                $taskCp += ';' + $taskSodiumRuntime
            } else { $taskCp += ';' + $taskSodium.FullName }
        } finally { $taskZip.Dispose() }
    }
}
Push-Location $taskRoot
try {
    if ($NativeWater) {
        if (!$BslPack) { throw 'Native water probe requires -BslPack' }
        $taskShim = Join-Path $taskOutput 'water-host-shim'
        New-Item -ItemType Directory -Force -Path $taskShim | Out-Null
        & (Join-Path $Jdk 'bin/javac.exe') -proc:none -encoding UTF-8 -cp $taskCp -d $taskShim `
            (Join-Path $taskRoot 'tools/iris-toon-probe/shims/Iris.java')
        if ($LASTEXITCODE -ne 0) { throw 'Water host configuration shim compilation failed' }
        & (Join-Path $Jdk 'bin/javac.exe') -proc:none -encoding UTF-8 -cp $taskCp -d $taskOutput `
            (Join-Path $PSScriptRoot 'BlockEntityTransparencyGlProbe.java') `
            (Join-Path $taskRoot 'tools/iris-toon-probe/WaterShaderProbe.java')
        if ($LASTEXITCODE -ne 0) { throw 'Native water probe compilation failed' }
        & (Join-Path $Jdk 'bin/java.exe') -cp "$taskShim;$taskCp;$taskOutput" WaterShaderProbe $BslPack
        if ($LASTEXITCODE -ne 0) { throw 'Native water probe failed' }
        return
    }
    & (Join-Path $Jdk 'bin/javac.exe') -proc:none -encoding UTF-8 -cp $taskCp -d $taskOutput `
        (Join-Path $PSScriptRoot 'BlockEntityTransparencyGlProbe.java')
    if ($LASTEXITCODE -ne 0) { throw 'Block entity probe compilation failed' }
    if ($BslPack) {
        & (Join-Path $Jdk 'bin/javac.exe') -proc:none -encoding UTF-8 -cp "$taskCp;$taskOutput" -d (Join-Path $taskRoot 'build/iris-toon-probe') `
            (Join-Path $taskRoot 'tools/iris-toon-probe/BslBatchGlProbe.java')
        if ($LASTEXITCODE -ne 0) { throw 'BSL profile helper compilation failed' }
        & (Join-Path $Jdk 'bin/javac.exe') -proc:none -encoding UTF-8 -cp $taskCp -d $taskOutput `
            (Join-Path $PSScriptRoot 'IrisTransparencyApiProbe.java')
        if ($LASTEXITCODE -ne 0) { throw 'Iris API probe compilation failed' }
        & (Join-Path $Jdk 'bin/java.exe') -cp "$taskCp;$taskOutput" `
            com.shiroha.mmdskin.render.material.IrisTransparencyApiProbe
        if ($LASTEXITCODE -ne 0) { throw 'Installed Iris API probe failed' }
    }
    $taskArguments = @('-cp', "$taskCp;$taskOutput", 'com.shiroha.mmdskin.render.material.BlockEntityTransparencyGlProbe')
    if ($WaterPerformance) {
        if (!$BslPack) { throw 'Water performance probe requires -BslPack' }
        $taskArguments = @("-Dmmdskin.transparencyProbeSize=$PerformanceSize") + $taskArguments
    }
    if ($BslPack) { $taskArguments += $BslPack }
    if ($WaterOrder -or $SharedWaterReference) {
        if (!$BslPack) { throw 'Water order reproduction requires -BslPack' }
        $taskArguments += '--water-order'
        if ($SharedWaterReference) { $taskArguments += '--shared-water-reference' }
    }
    if ($WaterPerformance) { $taskArguments += '--water-performance' }
    & (Join-Path $Jdk 'bin/java.exe') @taskArguments
    if ($LASTEXITCODE -ne 0) { throw 'Block entity transparency probe failed' }
} finally { Pop-Location }
