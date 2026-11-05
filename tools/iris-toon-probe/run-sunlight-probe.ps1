param(
    [string]$Instance = 'D:\minecraft\.minecraft\versions\1.21.1-NeoForge_21.1.252',
    [string]$Jdk = 'C:\Program Files\Zulu\zulu-21',
    [string]$Mode = ''
)
$ErrorActionPreference = 'Stop'
$probeRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$probeOutput = Join-Path $probeRoot 'build/sunlight-probe'
New-Item -ItemType Directory -Force -Path $probeOutput | Out-Null
$probeOldJavaHome = $env:JAVA_HOME
$probeOldPath = $env:PATH
try {
    $env:JAVA_HOME = $Jdk
    $env:PATH = "$Jdk\bin;$probeOldPath"
    & (Join-Path $probeRoot 'gradlew.bat') -p $probeRoot -I (Join-Path $probeRoot 'tools/translucent-performance/validation.gradle') `
        :common:compileJava :common:processResources :common:writeTranslucentProbeClasspath -x :prepareNativeResources --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Production classes/resources preparation failed' }
} finally {
    $env:JAVA_HOME = $probeOldJavaHome
    $env:PATH = $probeOldPath
}
$probeClasspathFile = Join-Path $probeRoot 'build/translucent-performance/classpath.txt'
if (!(Test-Path -LiteralPath $probeClasspathFile)) {
    throw 'Run :common:writeTranslucentProbeClasspath with tools/translucent-performance/validation.gradle first'
}
$probeIris = Get-ChildItem -LiteralPath (Join-Path $Instance 'mods') -Filter 'iris-*.jar' | Select-Object -First 1
if (!$probeIris) { throw 'Missing instance Iris JAR' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$probeArchive = [System.IO.Compression.ZipFile]::OpenRead($probeIris.FullName)
try {
    $probeEntry = $probeArchive.Entries | Where-Object { $_.FullName -match '/jcpp-[^/]+\.jar$' } | Select-Object -First 1
    if (!$probeEntry) { throw 'Missing bundled Iris JCPP dependency' }
    $probeJcpp = Join-Path $probeOutput 'jcpp.jar'
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($probeEntry, $probeJcpp, $true)
} finally { $probeArchive.Dispose() }
$probeClasspath = (Join-Path $probeRoot 'common/src/main/resources') + ';' +
    (Get-Content -LiteralPath $probeClasspathFile -Raw).Trim() + ';' + $probeIris.FullName + ';' + $probeJcpp
foreach ($probeModule in @('lwjgl', 'lwjgl-glfw', 'lwjgl-opengl')) {
    $probeCache = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/org.lwjgl/$probeModule/3.3.3"
    $probeNative = Get-ChildItem -LiteralPath $probeCache -Recurse -Filter '*-natives-windows.jar' | Select-Object -First 1
    if (!$probeNative) { throw "Missing Windows native dependency: $probeModule" }
    $probeClasspath += ';' + $probeNative.FullName
}
$probeSources = @('Probe.java', 'ModernPackProbe.java', 'OverlayProbe.java', 'LightingProbe.java',
    'ToonControlsProbe.java', 'UniformBridgeProbe.java', 'OutlineCoverageProbe.java', 'ShadowSourceFixture.java', 'TerrainShadowGlProbe.java', 'TransmissionGlProbe.java', 'SunlightGlProbe.java') |
    ForEach-Object { Join-Path $PSScriptRoot $_ }
Push-Location $probeRoot
try {
    & (Join-Path $Jdk 'bin/javac.exe') -proc:none -encoding UTF-8 -cp $probeClasspath -d $probeOutput $probeSources
    if ($LASTEXITCODE -ne 0) { throw 'Sunlight probe compilation failed' }
    & (Join-Path $Jdk 'bin/java.exe') -cp "$probeClasspath;$probeOutput" SunlightGlProbe (Join-Path $Instance 'shaderpacks') $Mode
    if ($LASTEXITCODE -ne 0) { throw 'Sunlight GL validation failed' }
} finally { Pop-Location }
