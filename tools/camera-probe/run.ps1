param(
    [string]$Jdk = 'C:\Program Files\Zulu\zulu-17',
    [string]$OutputDirectory = ''
)
$ErrorActionPreference = 'Stop'
$probeRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (!$OutputDirectory) { $OutputDirectory = Join-Path $probeRoot 'build/camera-probe' }
$probeOutput = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $probeOutput | Out-Null
$probeClasspath = @(
    (Join-Path $probeRoot 'forge/build/classes/java/main'),
    (Join-Path $probeRoot 'common/build/classes/java/main'),
    (Join-Path $probeRoot 'common/build/resources/main'),
    (Join-Path $probeRoot 'forge/build/moddev/artifacts/forge-1.20.1-47.3.0.jar'),
    ((Get-Content -LiteralPath (Join-Path $probeRoot 'forge/build/moddev/clientLegacyClasspath.txt')) -join ';')
) -join ';'
& (Join-Path $Jdk 'bin/javac.exe') -proc:none -encoding UTF-8 -cp $probeClasspath `
    -d $probeOutput (Join-Path $PSScriptRoot 'StageCameraEventProbe.java')
if ($LASTEXITCODE -ne 0) { throw 'Camera event probe compilation failed' }
& (Join-Path $Jdk 'bin/java.exe') -cp "$probeOutput;$probeClasspath" `
    com.shiroha.mmdskin.stage.client.camera.StageCameraEventProbe
if ($LASTEXITCODE -ne 0) { throw 'Camera event probe failed' }
