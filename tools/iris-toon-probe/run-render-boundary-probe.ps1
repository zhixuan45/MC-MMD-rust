param(
    [ValidateSet('all', 'lighting', 'transmission')]
    [string]$Probe = 'all',
    [string]$Instance = 'D:\minecraft\.minecraft\versions\1.21.1-NeoForge_21.1.252',
    [string]$Jdk = 'C:\Program Files\Zulu\zulu-21',
    [string]$ClasspathFile = '',
    [string]$Classes = ''
)

$ErrorActionPreference = 'Stop'
$probeRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$probeOutput = Join-Path $probeRoot 'build/render-boundary-probe'
New-Item -ItemType Directory -Force -Path $probeOutput | Out-Null

$probeIris = Get-ChildItem -LiteralPath (Join-Path $Instance 'mods') -Filter 'iris-*.jar' |
    Select-Object -First 1
if (!$probeIris) { throw "Missing Iris JAR in $Instance\mods" }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$probeArchive = [System.IO.Compression.ZipFile]::OpenRead($probeIris.FullName)
try {
    $probeEntry = $probeArchive.Entries |
        Where-Object { $_.FullName -match '/jcpp-[^/]+\.jar$' } |
        Select-Object -First 1
    if (!$probeEntry) { throw 'Missing bundled Iris JCPP dependency' }
    $probeJcpp = Join-Path $probeOutput 'jcpp.jar'
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($probeEntry, $probeJcpp, $true)
} finally {
    $probeArchive.Dispose()
}

if (!$ClasspathFile) {
    $ClasspathFile = Join-Path $probeRoot 'build/stage-prop-probe/classpath.txt'
}
if (!(Test-Path -LiteralPath $ClasspathFile)) {
    throw "Missing probe classpath: $ClasspathFile; run gradlew :common:classes :common:writeStagePropProbeClasspath --init-script tools/stage-prop-probe/classpath.gradle"
}
if (!$Classes) {
    $Classes = Join-Path $probeRoot 'common/build/classes/java/main'
}
$Classes = (Resolve-Path -LiteralPath $Classes).Path

$probeClasspath = $Classes + ';' +
    (Join-Path $probeRoot 'common/build/resources/main') + ';' +
    (Join-Path $probeRoot 'common/src/main/resources') + ';' +
    (Get-Content -LiteralPath $ClasspathFile -Raw).Trim() + ';' +
    $probeIris.FullName + ';' + $probeJcpp
foreach ($probeModule in @('lwjgl', 'lwjgl-glfw', 'lwjgl-opengl')) {
    $probeCache = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/org.lwjgl/$probeModule/3.3.3"
    $probeNative = Get-ChildItem -LiteralPath $probeCache -Recurse -Filter '*-natives-windows.jar' |
        Where-Object { $_.Name -notmatch 'arm64|x86' } |
        Select-Object -First 1
    if (!$probeNative) { throw "Missing Windows native dependency: $probeModule" }
    $probeClasspath += ';' + $probeNative.FullName
}

$probeProductionPairs = @(
    @{
        Source = 'common/src/main/java/com/shiroha/mmdskin/compat/iris/TerrainShadowFrame.java'
        Class = 'com/shiroha/mmdskin/compat/iris/TerrainShadowFrame.class'
    },
    @{
        Source = 'common/src/main/java/com/shiroha/mmdskin/compat/iris/IrisTerrainTransmission.java'
        Class = 'com/shiroha/mmdskin/compat/iris/IrisTerrainTransmission.class'
    },
    @{
        Source = 'common/src/main/java/com/shiroha/mmdskin/compat/iris/ShadowReplayGlState.java'
        Class = 'com/shiroha/mmdskin/compat/iris/ShadowReplayGlState.class'
    },
    @{
        Source = 'common/src/main/java/com/shiroha/mmdskin/render/shader/ToonShaderCpu.java'
        Class = 'com/shiroha/mmdskin/render/shader/ToonShaderCpu.class'
    }
)
foreach ($probeProductionPair in $probeProductionPairs) {
    $probeProductionSource = Join-Path $probeRoot $probeProductionPair.Source
    $probeProductionClass = Join-Path $Classes $probeProductionPair.Class
    if (!(Test-Path -LiteralPath $probeProductionClass)) {
        throw "Production classes are incomplete: $probeProductionClass"
    }
    if ((Get-Item -LiteralPath $probeProductionSource).LastWriteTimeUtc -gt
            (Get-Item -LiteralPath $probeProductionClass).LastWriteTimeUtc) {
        throw "Production class is stale: $probeProductionClass; run the project build before probing"
    }
}

$probeSources = @(
    'Probe.java',
    'ModernPackProbe.java',
    'OverlayProbe.java',
    'LightingProbe.java',
    'ToonControlsProbe.java',
    'UniformBridgeProbe.java',
    'OutlineCoverageProbe.java',
    'ShadowSourceFixture.java',
    'TerrainShadowGlProbe.java',
    'TransmissionGlProbe.java',
    'TransmissionOnlyGlProbe.java',
    'SunlightGlProbe.java'
) | ForEach-Object { Join-Path $PSScriptRoot $_ }

Push-Location $probeRoot
try {
    & (Join-Path $Jdk 'bin/javac.exe') -proc:none -encoding UTF-8 `
        -cp $probeClasspath -d $probeOutput $probeSources
    if ($LASTEXITCODE -ne 0) { throw 'Render-boundary probe compilation failed' }

    $probeMain = if ($Probe -eq 'transmission') { 'TransmissionOnlyGlProbe' } else { 'SunlightGlProbe' }
    $probeArguments = if ($Probe -eq 'transmission') {
        @()
    } elseif ($Probe -eq 'lighting') {
        @((Join-Path $Instance 'shaderpacks'), 'lighting-only')
    } else {
        @((Join-Path $Instance 'shaderpacks'), '')
    }
    & (Join-Path $Jdk 'bin/java.exe') -cp "$probeOutput;$probeClasspath" $probeMain @probeArguments
    if ($LASTEXITCODE -ne 0) { throw "Render-boundary probe failed: $Probe" }
} finally {
    Pop-Location
}
