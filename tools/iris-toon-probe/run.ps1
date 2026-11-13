param(
    [ValidateSet('modern', 'water', 'shadow', 'batch', 'transparency', 'hooks', 'all', 'bsl-batch')]
    [string]$Probe = 'modern',
    [string]$Instance = 'D:\minecraft\.minecraft\versions\1.21.1-NeoForge_21.1.252',
    [string]$Jdk = 'C:\Program Files\Zulu\zulu-21',
    [string]$Shaderpacks = 'D:\minecraft\.minecraft\versions\1.21.1-NeoForge_21.1.252\shaderpacks',
    [string]$ExtraPack = '',
    [string]$IrisJar = '',
    [string]$MinecraftJar = '',
    [string]$ClasspathFile = '',
    [string]$Classes = '',
    [string]$OutputDirectory = '',
    [ValidateSet('12101')]
    [string]$McVersion = '12101',
    [ValidateSet('10814')]
    [string]$IrisVersion = '10814',
    [string]$EyeAssetData = '',
    [switch]$RequireWaterAdapter,
    [switch]$BslBatchOnly
)
$ErrorActionPreference = 'Stop'
if ($BslBatchOnly) { $Probe = 'bsl-batch' }

$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$taskShaderpacks = (Resolve-Path -LiteralPath $Shaderpacks).Path
$taskInstance = (Resolve-Path -LiteralPath $Instance).Path
$taskJavaOptions = @()
if ($ExtraPack) {
    $taskExtraPack = (Resolve-Path -LiteralPath $ExtraPack).Path
    $taskJavaOptions += "-Dmmdskin.irisProbe.extraPack=$taskExtraPack"
}
$taskJava = Join-Path $Jdk 'bin/java.exe'
$taskJavac = Join-Path $Jdk 'bin/javac.exe'
if (!(Test-Path -LiteralPath $taskJava) -or !(Test-Path -LiteralPath $taskJavac)) {
    throw "JDK 21 not found: $Jdk"
}

if (!$IrisJar) {
    $taskIris = Get-ChildItem -LiteralPath (Join-Path $taskInstance 'mods') -Filter 'iris-*.jar' |
        Select-Object -First 1
    if (!$taskIris) { throw "No Iris JAR in $taskInstance\mods" }
} else {
    $taskIris = Get-Item -LiteralPath $IrisJar
}
$taskIris = $taskIris.FullName
$taskIrisLabel = [IO.Path]::GetFileNameWithoutExtension($taskIris)

if (!$ClasspathFile) {
    $ClasspathFile = Join-Path $taskRoot 'build/translucent-performance/classpath.txt'
}
if (!(Test-Path -LiteralPath $ClasspathFile)) {
    throw "Missing 1.21 production classpath: $ClasspathFile"
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
    @{ Source = 'common/src/main/java/com/shiroha/mmdskin/compat/iris/IrisTerrainShadowReplay.java'; Class = 'com/shiroha/mmdskin/compat/iris/IrisTerrainShadowReplay.class' },
    @{ Source = 'common/src/main/java/com/shiroha/mmdskin/compat/iris/IrisTerrainTransmission.java'; Class = 'com/shiroha/mmdskin/compat/iris/IrisTerrainTransmission.class' },
    @{ Source = 'common/src/main/java/com/shiroha/mmdskin/compat/iris/TerrainShadowFrame.java'; Class = 'com/shiroha/mmdskin/compat/iris/TerrainShadowFrame.class' },
    @{ Source = 'common/src/main/java/com/shiroha/mmdskin/render/shader/ComplementaryToonLighting.java'; Class = 'com/shiroha/mmdskin/render/shader/ComplementaryToonLighting.class' },
    @{ Source = 'common/src/main/java/com/shiroha/mmdskin/render/pipeline/transparency/WorldTransparencyPipeline.java'; Class = 'com/shiroha/mmdskin/render/pipeline/transparency/WorldTransparencyPipeline.class' },

    @{ Source = 'common/src/main/java/com/shiroha/mmdskin/compat/iris/IrisWaterShaderPatcher.java';
       Class = 'com/shiroha/mmdskin/compat/iris/IrisWaterShaderPatcher.class' },
    @{ Source = 'common/src/main/java/com/shiroha/mmdskin/render/shader/ToonOutputProfile.java';
       Class = 'com/shiroha/mmdskin/render/shader/ToonOutputProfile.class' },
    @{ Source = 'common/src/main/java/com/shiroha/mmdskin/render/shader/ToonShaderBase.java';
       Class = 'com/shiroha/mmdskin/render/shader/ToonShaderBase.class' }
)
foreach ($taskPair in $taskProductionPairs) {
    $taskSource = Get-Item -LiteralPath (Join-Path $taskRoot $taskPair.Source)
    $taskClass = Get-Item -LiteralPath (Join-Path $taskClasses $taskPair.Class)
    if ($taskSource.LastWriteTimeUtc -gt $taskClass.LastWriteTimeUtc) {
        throw "Production class is stale: $($taskClass.FullName); rerun :common:compileJava"
    }
}

$taskOutput = if ($OutputDirectory) {
    [IO.Path]::GetFullPath($OutputDirectory, $taskRoot)
} else {
    Join-Path $taskRoot "build/iris-toon-probe/$taskIrisLabel-$McVersion/$(Get-Date -Format yyyyMMdd-HHmmss-fff)"
}
$taskHost = Join-Path $taskOutput 'host'
New-Item -ItemType Directory -Force -Path $taskOutput, $taskHost | Out-Null

# Iris bundles JCPP; extract only probe dependencies without touching the instance.
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
    $taskMapped = (Get-Content -LiteralPath $ClasspathFile -Raw).Trim().Split(';') |
        Where-Object { $_ -match 'minecraft-merged-1\.21\.1-' -and $_ -notmatch 'sources|javadoc|backup' } |
        Select-Object -First 1
    $taskMinecraft = if ($taskMapped) { Get-Item -LiteralPath $taskMapped } else { $null }
    if (!$taskMinecraft) { throw 'Mapped Minecraft 1.21.1 JAR not found; pass -MinecraftJar' }
} else {
    $taskMinecraft = Get-Item -LiteralPath $MinecraftJar
}
$taskMinecraft = $taskMinecraft.FullName
if ($taskMinecraft -notmatch '1[._]21[._]1') {
    throw "Expected mapped Minecraft 1.21.1: $taskMinecraft"
}

$taskClasspath = @(
    $taskClasses,
    $taskResources,
    (Get-Content -LiteralPath $ClasspathFile -Raw).Trim(),
    $taskMinecraft,
    $taskIris,
    $taskJcpp
)
if ($taskGlsl) { $taskClasspath += $taskGlsl }
$taskSodium = Get-ChildItem -LiteralPath (Join-Path $taskInstance 'mods') -Filter 'sodium-*.jar' |
    Select-Object -First 1
if (!$taskSodium) { throw "Missing installed 1.21 Sodium JAR in $taskInstance\mods" }
$taskClasspath += $taskSodium.FullName
# NeoForge's installed Sodium JAR wraps the implementation in a Jar-in-Jar.
$taskSodiumArchive = [IO.Compression.ZipFile]::OpenRead($taskSodium.FullName)
try {
    foreach ($taskEntry in $taskSodiumArchive.Entries |
            Where-Object { $_.FullName -match '^META-INF/jarjar/.*sodium.*-mod\.jar$' }) {
        $taskSodiumCore = Join-Path $taskOutput ([IO.Path]::GetFileName($taskEntry.FullName))
        Expand-ProbeDependency $taskEntry $taskSodiumCore
        $taskClasspath += $taskSodiumCore
    }
} finally { $taskSodiumArchive.Dispose() }
foreach ($taskDependency in @('org.antlr/antlr4-runtime/4.13.1',
        'org.ow2.asm/asm/9.7.1', 'org.ow2.asm/asm-tree/9.7.1')) {
    $taskLibrary = Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/$taskDependency") -Recurse -Filter '*.jar' |
        Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1
    if (!$taskLibrary) { throw "Missing probe dependency: $taskDependency" }
    $taskClasspath += $taskLibrary.FullName
}
# Refuse cross-version or foreign production output on the runtime classpath.
$taskEntries = ($taskClasspath -join ';').Split(';', [StringSplitOptions]::RemoveEmptyEntries)
foreach ($taskEntry in $taskEntries) {
    if ($taskEntry -match 'oculus|(?:^|[/\\])1\.20\.1(?:[/\\-]|$)') {
        throw "Foreign 1.20/Oculus dependency in 1.21 probe: $taskEntry"
    }
    if ($taskEntry -match '[/\\]build[/\\](classes|resources)[/\\]' -and
        ![IO.Path]::GetFullPath($taskEntry).StartsWith($taskRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Foreign production output in probe classpath: $taskEntry"
    }
}
foreach ($taskModule in @('lwjgl','lwjgl-glfw','lwjgl-opengl')) {
    $taskNative = Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/org.lwjgl/$taskModule/3.3.3") -Recurse -Filter "$taskModule-3.3.3-natives-windows.jar" | Select-Object -First 1
    if (!$taskNative) { throw "Missing Windows native: $taskModule" }
    $taskClasspath += $taskNative.FullName
}
$taskCp = $taskClasspath -join ';'
$taskClasspath | Set-Content -LiteralPath (Join-Path $taskOutput 'classpath.txt')
Write-Host "1.21 probe=$Probe MC_VERSION=$McVersion IRIS_VERSION=$IrisVersion Iris=$taskIris"
Write-Host "Probe output: $taskOutput"

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
        foreach ($taskSource in $taskSharedSources) { $taskSources.Add($taskSource) }
        $taskSources.Add((Join-Path $PSScriptRoot 'ShadowSourceFixture.java'))
        $taskSources.Add((Join-Path $PSScriptRoot 'ModernPackProbe.java'))
    }
    'water' {
        $taskSources.Add((Join-Path $PSScriptRoot 'WaterShaderProbe.java'))
        $taskSources.Add((Join-Path $taskRoot 'tools/translucent-performance/BlockEntityTransparencyGlProbe.java'))
    }
    'hooks' {
        $taskSources.Add((Join-Path $PSScriptRoot 'ShadowHookProbe.java'))
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
        $taskSources.Add((Join-Path $PSScriptRoot 'ShadowHookProbe.java'))
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

    if ($Probe -in @('hooks', 'all')) {
        & $taskJava @taskJavaOptions -cp $taskRunCp ShadowHookProbe $taskIris
        if ($LASTEXITCODE -ne 0) { throw 'Installed Iris / compiled platform hook contract failed' }
    }
    if ($Probe -in @('modern', 'all')) {
        & $taskJava @taskJavaOptions -cp $taskRunCp ModernPackProbe $taskShaderpacks $McVersion $IrisVersion
        if ($LASTEXITCODE -ne 0) { throw 'Modern Complementary probe failed' }
    }
    if ($Probe -in @('water', 'all')) {
        $taskAdapterPolicy = 'required'
        $taskWaterPacks = @('ComplementaryReimagined_r5.9.3.zip', 'ComplementaryUnbound_r5.9.3.zip', 'BSL_v10.1.8.zip') |
            ForEach-Object { Join-Path $taskShaderpacks $_ }
        if ($ExtraPack) { $taskWaterPacks += $taskExtraPack }
        foreach ($taskPackPath in $taskWaterPacks) {
            if (!(Test-Path -LiteralPath $taskPackPath)) { throw "Missing real shader pack: $taskPackPath" }
            & $taskJava @taskJavaOptions -cp $taskRunCp WaterShaderProbe $taskPackPath $McVersion $IrisVersion $taskAdapterPolicy
            if ($LASTEXITCODE -ne 0) { throw "Water probe failed: $taskPackPath" }
        }
    }
    if ($Probe -in @('shadow', 'all')) {
        $taskSunlightArgs = @($taskShaderpacks, '', $McVersion, $IrisVersion)
        & $taskJava @taskJavaOptions -cp $taskRunCp SunlightGlProbe @taskSunlightArgs
        if ($LASTEXITCODE -ne 0) { throw 'Modern/BSL shadow probe failed' }
    }
    if ($Probe -in @('batch', 'bsl-batch', 'all')) {
        $taskBatchPacks = @((Join-Path $taskShaderpacks 'BSL_v10.1.8.zip'))
        if ($Probe -ne 'bsl-batch') {
            $taskBatchPacks += Join-Path $taskShaderpacks 'ComplementaryReimagined_r5.9.3.zip'
            $taskBatchPacks += Join-Path $taskShaderpacks 'ComplementaryUnbound_r5.9.3.zip'
        }
        & $taskJava @taskJavaOptions "-Dmmdskin.irisProbe.mcVersion=$McVersion" "-Dmmdskin.irisProbe.irisVersion=$IrisVersion" `
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
