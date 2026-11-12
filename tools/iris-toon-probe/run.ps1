param(
    [ValidateSet('modern', 'water', 'shadow', 'batch', 'transparency', 'all', 'bsl-batch')]
    [string]$Probe = 'modern',
    [string]$Instance = 'D:\minecraft\2\.minecraft\versions\1.20.1-Forge_47.4.6',
    [string]$Jdk = 'C:\Program Files\Zulu\zulu-17',
    [string]$Shaderpacks = 'D:\minecraft\.minecraft\versions\1.21.1-NeoForge_21.1.252\shaderpacks',
    [string]$OculusJar = '',
    [string]$MinecraftJar = '',
    [string]$ClasspathFile = '',
    [string]$Classes = '',
    [string]$OutputDirectory = '',
    [string]$McVersion = '12001',
    [string]$IrisVersion = '10800',
    [string]$EyeAssetData = '',
    [switch]$RequireWaterAdapter,
    [switch]$AllowStaleProduction,
    [switch]$BslBatchOnly
)
$ErrorActionPreference = 'Stop'
if ($BslBatchOnly) { $Probe = 'bsl-batch' }

$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$taskShaderpacks = (Resolve-Path -LiteralPath $Shaderpacks).Path
$taskInstance = (Resolve-Path -LiteralPath $Instance).Path
$taskJava = Join-Path $Jdk 'bin/java.exe'
$taskJavac = Join-Path $Jdk 'bin/javac.exe'
if (!(Test-Path -LiteralPath $taskJava) -or !(Test-Path -LiteralPath $taskJavac)) {
    throw "JDK 17 not found: $Jdk"
}

if (!$OculusJar) {
    $taskIris = Get-ChildItem -LiteralPath (Join-Path $taskInstance 'mods') -Filter 'oculus-*.jar' |
        Select-Object -First 1
    if (!$taskIris) {
        $taskIris = Get-ChildItem -LiteralPath (Join-Path $taskInstance 'mods') -Filter 'iris-*.jar' |
            Select-Object -First 1
    }
    if (!$taskIris) { throw "No Oculus/Iris JAR in $taskInstance\mods" }
} else {
    $taskIris = Get-Item -LiteralPath $OculusJar
}
$taskIris = $taskIris.FullName
$taskIrisLabel = [IO.Path]::GetFileNameWithoutExtension($taskIris)

if (!$ClasspathFile) {
    $ClasspathFile = Join-Path $taskRoot 'build/translucent-performance/classpath.txt'
}
if (!(Test-Path -LiteralPath $ClasspathFile)) {
    throw "Missing 1.20 production classpath: $ClasspathFile"
}
if (!$Classes) {
    $Classes = Join-Path $taskRoot 'common/build/classes/java/main'
}
$taskClasses = (Resolve-Path -LiteralPath $Classes).Path
$taskResources = Join-Path $taskRoot 'common/build/resources/main'

$taskRequiredClasses = @(
    'com/shiroha/mmdskin/compat/iris/IrisWaterShaderPatcher.class',
    'com/shiroha/mmdskin/render/shader/ToonShaderCpu.class',
    'com/shiroha/mmdskin/render/shader/ToonOutputProfile.class',
    'com/shiroha/mmdskin/render/pipeline/transparency/WorldTransparencyPipeline.class'
)
foreach ($taskRequiredClass in $taskRequiredClasses) {
    $taskPath = Join-Path $taskClasses $taskRequiredClass
    if (!(Test-Path -LiteralPath $taskPath)) {
        throw "Production classes are incomplete: $taskPath"
    }
}

$taskProductionPairs = @(
    @{ Source = 'common/src/main/java/com/shiroha/mmdskin/compat/iris/IrisWaterShaderPatcher.java';
       Class = 'com/shiroha/mmdskin/compat/iris/IrisWaterShaderPatcher.class' },
    @{ Source = 'common/src/main/java/com/shiroha/mmdskin/render/shader/ToonOutputProfile.java';
       Class = 'com/shiroha/mmdskin/render/shader/ToonOutputProfile.class' },
    @{ Source = 'common/src/main/java/com/shiroha/mmdskin/render/shader/ToonShaderBase.java';
       Class = 'com/shiroha/mmdskin/render/shader/ToonShaderBase.class' }
)
foreach ($taskPair in $taskProductionPairs) {
    if ($AllowStaleProduction) { break }
    $taskSource = Get-Item -LiteralPath (Join-Path $taskRoot $taskPair.Source)
    $taskClass = Get-Item -LiteralPath (Join-Path $taskClasses $taskPair.Class)
    if ($taskSource.LastWriteTimeUtc -gt $taskClass.LastWriteTimeUtc) {
        throw "Production class is stale: $($taskClass.FullName); rerun :common:compileJava"
    }
}

$taskOutput = if ($OutputDirectory) {
    [IO.Path]::GetFullPath($OutputDirectory, $taskRoot)
} else {
    Join-Path $taskRoot "build/iris-toon-probe/$taskIrisLabel-$McVersion"
}
$taskHost = Join-Path $taskOutput 'host'
New-Item -ItemType Directory -Force -Path $taskOutput, $taskHost | Out-Null

# Oculus/Iris bundles JCPP; extract only probe dependencies without touching the instance.
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Expand-ProbeDependency($Entry, [string]$Destination) {
    if (Test-Path -LiteralPath $Destination) {
        $dependencyStream = $Entry.Open()
        try {
            $expectedDigest = [Convert]::ToHexString(
                [Security.Cryptography.SHA256]::HashData($dependencyStream))
        } finally { $dependencyStream.Dispose() }
        if ((Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash -eq $expectedDigest) {
            return
        }
    }
    [IO.Compression.ZipFileExtensions]::ExtractToFile($Entry, $Destination, $true)
}
$taskZip = [IO.Compression.ZipFile]::OpenRead($taskIris)
try {
    $taskEntry = $taskZip.Entries |
        Where-Object { $_.FullName -match '/jcpp-[^/]+\.jar$' } |
        Select-Object -First 1
    if (!$taskEntry) { throw "No bundled JCPP in $taskIris" }
    $taskJcpp = Join-Path $taskOutput 'jcpp.jar'
    Expand-ProbeDependency $taskEntry $taskJcpp

    $taskGlsl = ''
    $taskGlslEntry = $taskZip.Entries |
        Where-Object { $_.FullName -match '/glsl-transformer-[^/]+\.jar$' } |
        Select-Object -First 1
    if ($taskGlslEntry) {
        $taskGlsl = Join-Path $taskOutput 'glsl-transformer.jar'
        Expand-ProbeDependency $taskGlslEntry $taskGlsl
    }
} finally {
    $taskZip.Dispose()
}

if (!$MinecraftJar) {
    $taskMinecraftRoot = Join-Path $env:USERPROFILE '.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged'
    $taskMinecraft = Get-ChildItem -LiteralPath $taskMinecraftRoot -Recurse -Filter '*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '1[._]20[._]1' -and $_.Name -notmatch 'sources|javadoc' } |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
    if (!$taskMinecraft) {
        throw 'Mapped Minecraft 1.20.1 JAR not found; pass -MinecraftJar'
    }
} else {
    $taskMinecraft = Get-Item -LiteralPath $MinecraftJar
}
$taskMinecraft = $taskMinecraft.FullName

$taskClasspath = @(
    $taskClasses,
    $taskResources,
    (Get-Content -LiteralPath $ClasspathFile -Raw).Trim(),
    $taskMinecraft,
    $taskIris,
    $taskJcpp
)
if ($taskGlsl) { $taskClasspath += $taskGlsl }
$taskCp = $taskClasspath -join ';'

$taskSharedSources = @(
    'Probe.java',
    'OverlayProbe.java',
    'LightingProbe.java',
    'ToonControlsProbe.java',
    'UniformBridgeProbe.java',
    'OutlineCoverageProbe.java'
) | ForEach-Object { Join-Path $PSScriptRoot $_ }
$taskSources = [Collections.Generic.List[string]]::new()

switch ($Probe) {
    'modern' {
        $taskSources.Add((Join-Path $PSScriptRoot 'ModernPackProbe.java'))
    }
    'water' {
        $taskSources.Add((Join-Path $PSScriptRoot 'WaterShaderProbe.java'))
        $taskSources.Add((Join-Path $taskRoot 'tools/translucent-performance/BlockEntityTransparencyGlProbe.java'))
    }
    'shadow' {
        foreach ($taskSource in $taskSharedSources) { $taskSources.Add($taskSource) }
        $taskSources.Add((Join-Path $PSScriptRoot 'ModernPackProbe.java'))
        $taskSources.Add((Join-Path $PSScriptRoot 'ShadowSourceFixture.java'))
        $taskSources.Add((Join-Path $PSScriptRoot 'TerrainShadowGlProbe.java'))
        $taskSources.Add((Join-Path $PSScriptRoot 'TransmissionGlProbe.java'))
        $taskSources.Add((Join-Path $PSScriptRoot 'SunlightGlProbe.java'))
    }
    { $_ -in @('batch', 'bsl-batch', 'transparency') } {
        foreach ($taskSource in $taskSharedSources) { $taskSources.Add($taskSource) }
        $taskSources.Add((Join-Path $PSScriptRoot 'ModernPackProbe.java'))
        $taskSources.Add((Join-Path $PSScriptRoot 'ShadowSourceFixture.java'))
        $taskSources.Add((Join-Path $PSScriptRoot 'BslBatchGlProbe.java'))
        if ($Probe -eq 'transparency') {
            $taskSources.Add((Join-Path $taskRoot 'tools/translucent-performance/BlockEntityTransparencyGlProbe.java'))
        }
    }
    'all' {
        foreach ($taskSource in $taskSharedSources) { $taskSources.Add($taskSource) }
        $taskSources.Add((Join-Path $PSScriptRoot 'ModernPackProbe.java'))
        $taskSources.Add((Join-Path $PSScriptRoot 'WaterShaderProbe.java'))
        $taskSources.Add((Join-Path $taskRoot 'tools/translucent-performance/BlockEntityTransparencyGlProbe.java'))
        $taskSources.Add((Join-Path $PSScriptRoot 'ShadowSourceFixture.java'))
        $taskSources.Add((Join-Path $PSScriptRoot 'TerrainShadowGlProbe.java'))
        $taskSources.Add((Join-Path $PSScriptRoot 'TransmissionGlProbe.java'))
        $taskSources.Add((Join-Path $PSScriptRoot 'SunlightGlProbe.java'))
        $taskSources.Add((Join-Path $PSScriptRoot 'BslBatchGlProbe.java'))
    }
}
if ($EyeAssetData) {
    $taskSources.Add((Join-Path $PSScriptRoot 'EyeAssetProbe.java'))
    $taskSources.Add((Join-Path $PSScriptRoot 'EyeEmissionBufferProbe.java'))
}

Push-Location $taskRoot
try {
    & $taskJavac -proc:none -encoding UTF-8 -cp $taskCp -d $taskHost `
        (Join-Path $PSScriptRoot 'shims/Iris.java')
    if ($LASTEXITCODE -ne 0) { throw 'Probe host shim compilation failed' }

    & $taskJavac -proc:none -encoding UTF-8 -cp "$taskHost;$taskCp" -d $taskOutput $taskSources
    if ($LASTEXITCODE -ne 0) { throw 'Probe compilation failed' }
    $taskRunCp = "$taskHost;$taskOutput;$taskCp"

    if ($Probe -in @('modern', 'all')) {
        & $taskJava -cp $taskRunCp ModernPackProbe $taskShaderpacks $McVersion $IrisVersion
        if ($LASTEXITCODE -ne 0) { throw 'Modern Complementary probe failed' }
    }
    if ($Probe -in @('water', 'all')) {
        $taskAdapterPolicy = if ($RequireWaterAdapter) { 'required' } else { 'auto' }
        foreach ($taskPack in @('ComplementaryReimagined_r5.9.3.zip', 'ComplementaryUnbound_r5.9.3.zip')) {
            $taskPackPath = Join-Path $taskShaderpacks $taskPack
            if (!(Test-Path -LiteralPath $taskPackPath)) { throw "Missing real shader pack: $taskPackPath" }
            & $taskJava -cp $taskRunCp WaterShaderProbe $taskPackPath $McVersion $IrisVersion $taskAdapterPolicy
            if ($LASTEXITCODE -ne 0) { throw "Water probe failed: $taskPack" }
        }
    }
    if ($Probe -in @('shadow', 'all')) {
        $taskSunlightArgs = @($taskShaderpacks, '', $McVersion, $IrisVersion)
        & $taskJava -cp $taskRunCp SunlightGlProbe @taskSunlightArgs
        if ($LASTEXITCODE -ne 0) { throw 'Modern/BSL shadow probe failed' }
    }
    if ($Probe -in @('batch', 'bsl-batch', 'all')) {
        $taskBatchPacks = @((Join-Path $taskShaderpacks 'BSL_v10.1.8.zip'))
        if ($Probe -ne 'bsl-batch') {
            $taskBatchPacks += Join-Path $taskShaderpacks 'ComplementaryReimagined_r5.9.3.zip'
            $taskBatchPacks += Join-Path $taskShaderpacks 'ComplementaryUnbound_r5.9.3.zip'
        }
        & $taskJava "-Dmmdskin.irisProbe.mcVersion=$McVersion" "-Dmmdskin.irisProbe.irisVersion=$IrisVersion" `
            -cp $taskRunCp BslBatchGlProbe @taskBatchPacks
        if ($LASTEXITCODE -ne 0) { throw 'Shader-pack batch MRT/depth GL probe failed' }
    }
    if ($Probe -in @('transparency', 'all')) {
        $taskBsl = Join-Path $taskShaderpacks 'BSL_v10.1.8.zip'
        & $taskJava -cp $taskRunCp com.shiroha.mmdskin.render.material.BlockEntityTransparencyGlProbe $taskBsl
        if ($LASTEXITCODE -ne 0) { throw 'Shared transparency regression failed' }
        & $taskJava -cp $taskRunCp com.shiroha.mmdskin.render.material.BlockEntityTransparencyGlProbe $taskBsl --water-order
        if ($LASTEXITCODE -ne 0) { throw 'Water alpha/crossing/empty/overflow regression failed' }
    }
    if ($EyeAssetData) {
        & $taskJava -cp $taskRunCp EyeEmissionBufferProbe
        if ($LASTEXITCODE -ne 0) { throw 'Eye buffer probe failed' }
        & $taskJava -cp $taskRunCp com.shiroha.mmdskin.render.shader.EyeAssetProbe $EyeAssetData
        if ($LASTEXITCODE -ne 0) { throw 'Eye asset probe failed' }
    }
} finally {
    Pop-Location
}
