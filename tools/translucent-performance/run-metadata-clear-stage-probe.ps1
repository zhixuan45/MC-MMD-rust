param([string]$Jdk = 'C:/Program Files/Zulu/zulu-21')
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$taskClasspathFile = Join-Path $taskRoot 'build/translucent-performance/classpath.txt'
$taskOutput = Join-Path $taskRoot 'build/translucent-performance/metadata-clear-stage-probe'
if (-not (Test-Path -LiteralPath $taskClasspathFile)) {
    throw "Missing benchmark classpath: $taskClasspathFile"
}
$taskClasspath = Get-Content -LiteralPath $taskClasspathFile -Raw
$taskSource = Join-Path $PSScriptRoot 'MetadataClearStageProbe.java'
$taskHelper = Join-Path $taskRoot 'common/src/main/java/com/shiroha/mmdskin/render/pipeline/transparency/MetadataClearStage.java'
New-Item -ItemType Directory -Force -Path $taskOutput | Out-Null
Push-Location $taskRoot
try {
    & (Join-Path $Jdk 'bin/javac.exe') -proc:none -encoding UTF-8 -cp $taskClasspath -d $taskOutput $taskHelper $taskSource
    if ($LASTEXITCODE -ne 0) { throw 'metadata clear probe compilation failed' }
    & (Join-Path $Jdk 'bin/java.exe') -cp "$taskOutput;$taskClasspath" `
        com.shiroha.mmdskin.render.pipeline.transparency.MetadataClearStageProbe
    if ($LASTEXITCODE -ne 0) { throw 'metadata clear probe failed' }
} finally { Pop-Location }
