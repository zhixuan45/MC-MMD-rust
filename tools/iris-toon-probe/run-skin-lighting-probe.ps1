param([string]$Jdk = 'C:\Program Files\Zulu\zulu-21')
$ErrorActionPreference = 'Stop'
$probeRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$probeOutput = Join-Path $probeRoot 'build/skin-lighting-diagnostics'
New-Item -ItemType Directory -Force -Path $probeOutput | Out-Null
$probeClasspath = (Join-Path $probeRoot 'common/src/main/resources') + ';' +
    (Get-Content (Join-Path $probeRoot 'build/translucent-performance/classpath.txt') -Raw).Trim()
foreach ($probeModule in @('lwjgl', 'lwjgl-glfw', 'lwjgl-opengl')) {
    $probeNative = Get-ChildItem (Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/org.lwjgl/$probeModule/3.3.3") `
        -Recurse -Filter '*-natives-windows.jar' | Select-Object -First 1
    if (!$probeNative) { throw "Missing Windows native dependency: $probeModule" }
    $probeClasspath += ';' + $probeNative.FullName
}
$probeArgs = Join-Path $probeOutput 'javac.args'
@('-proc:none', '-encoding', 'UTF-8', '-cp', ('"' + $probeClasspath.Replace('\', '/') + '"'),
    '-d', ('"' + $probeOutput.Replace('\', '/') + '"'),
    ('"' + (Join-Path $PSScriptRoot 'SkinLightingGlProbe.java').Replace('\', '/') + '"')) |
    Set-Content -Encoding utf8 $probeArgs
& (Join-Path $Jdk 'bin/javac.exe') "@$probeArgs"
if ($LASTEXITCODE -ne 0) { throw 'Skin lighting probe compilation failed' }
$probeArgs = Join-Path $probeOutput 'java.args'
@('-cp', ('"' + ($probeClasspath + ';' + $probeOutput).Replace('\', '/') + '"'),
    'SkinLightingGlProbe') | Set-Content -Encoding utf8 $probeArgs
Push-Location $probeRoot
try {
    & (Join-Path $Jdk 'bin/java.exe') "@$probeArgs"
    if ($LASTEXITCODE -ne 0) { throw 'Skin lighting OpenGL regression failed' }
} finally { Pop-Location }
