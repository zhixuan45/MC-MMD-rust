param(
    [string]$Jdk = 'C:\Program Files\Zulu\zulu-21',
    [string]$ClasspathFile = '',
    [string]$Classes = ''
)

$ErrorActionPreference = 'Stop'
$testRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$testOutput = Join-Path $testRoot 'build/render-boundary-tests'
New-Item -ItemType Directory -Force -Path $testOutput | Out-Null

if (!$ClasspathFile) {
    $ClasspathFile = Join-Path $testRoot 'build/stage-prop-probe/classpath.txt'
}
if (!(Test-Path -LiteralPath $ClasspathFile)) {
    throw "Missing test classpath: $ClasspathFile; run gradlew :common:classes :common:writeStagePropProbeClasspath --init-script tools/stage-prop-probe/classpath.gradle"
}
if (!$Classes) {
    $Classes = Join-Path $testRoot 'common/build/classes/java/main'
}
$Classes = (Resolve-Path -LiteralPath $Classes).Path

$junitRoot = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
$junitJars = @(
    'org.junit.jupiter/junit-jupiter-api/5.11.4',
    'org.junit.jupiter/junit-jupiter-engine/5.11.4',
    'org.junit.platform/junit-platform-commons/1.11.4',
    'org.junit.platform/junit-platform-engine/1.11.4',
    'org.junit.platform/junit-platform-launcher/1.11.4',
    'org.opentest4j/opentest4j/1.3.0',
    'org.apiguardian/apiguardian-api/1.1.2'
) | ForEach-Object {
    $jar = Get-ChildItem -LiteralPath (Join-Path $junitRoot $_) -Recurse -Filter '*.jar' |
        Where-Object { $_.Name -notmatch 'sources|javadoc' } |
        Select-Object -First 1
    if (!$jar) { throw "Missing JUnit dependency: $_" }
    $jar.FullName
}

$testClasspath = $Classes + ';' +
    (Join-Path $testRoot 'common/build/resources/main') + ';' +
    (Join-Path $testRoot 'common/src/main/resources') + ';' +
    (Get-Content -LiteralPath $ClasspathFile -Raw).Trim() + ';' +
    ($junitJars -join ';')

$testSources = @(
    'common/src/test/java/com/shiroha/mmdskin/config/ConfigDataTest.java',
    'common/src/test/java/com/shiroha/mmdskin/compat/iris/TerrainShadowFrameTest.java',
    'common/src/test/java/com/shiroha/mmdskin/compat/iris/IrisTerrainShadowReplayTest.java',
    'common/src/test/java/com/shiroha/mmdskin/render/backend/ShadowModelViewMatrixTest.java',
    'tools/iris-toon-probe/RunRenderBoundaryTests.java'
) | ForEach-Object { Join-Path $testRoot $_ }

Push-Location $testRoot
try {
    & (Join-Path $Jdk 'bin/javac.exe') -proc:none -encoding UTF-8 `
        -cp $testClasspath -d $testOutput $testSources
    if ($LASTEXITCODE -ne 0) { throw 'Focused test compilation failed' }
    & (Join-Path $Jdk 'bin/java.exe') -cp "$testOutput;$testClasspath" RunRenderBoundaryTests
    if ($LASTEXITCODE -ne 0) { throw 'Focused render-boundary tests failed' }
} finally {
    Pop-Location
}
