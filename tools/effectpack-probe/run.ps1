param(
    [Parameter(Mandatory = $true)][string]$FxZip,
    [string]$Jdk = 'C:\Program Files\Zulu\zulu-21'
)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$taskOutput = Join-Path $taskRoot ('build/effectpack-probe-' + [guid]::NewGuid().ToString('N'))
$taskCpFile = Join-Path $taskRoot 'build/sphere-texture-probe/classpath.txt'
if (!(Test-Path -LiteralPath $taskCpFile)) { throw '先运行 gradlew :common:writeSphereProbeClasspath -I tools/sphere-texture-probe/classpath.gradle' }
$taskCp = (Get-Content -LiteralPath $taskCpFile -Raw).Trim()
$taskCache = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
foreach ($taskModule in @('lwjgl', 'lwjgl-glfw', 'lwjgl-opengl')) {
    $taskNative = Get-ChildItem -LiteralPath (Join-Path $taskCache "org.lwjgl/$taskModule/3.3.3") -Recurse -Filter '*-natives-windows.jar' | Select-Object -First 1
    if (!$taskNative) { throw "Missing Windows native: $taskModule" }
    $taskCp += ';' + $taskNative.FullName
}
New-Item -ItemType Directory -Force -Path $taskOutput | Out-Null
Push-Location $taskRoot
try {
    & (Join-Path $Jdk 'bin/javac.exe') -encoding UTF-8 -proc:none -cp $taskCp -d $taskOutput (Join-Path $PSScriptRoot 'Probe.java') (Join-Path $PSScriptRoot 'MixedFxGlProbe.java')
    if ($LASTEXITCODE -ne 0) { throw 'FX probe compilation failed' }
    & (Join-Path $Jdk 'bin/java.exe') '-Djava.awt.headless=true' -cp "$taskOutput;$taskCp" com.shiroha.mmdskin.render.effectpack.Probe $FxZip
    if ($LASTEXITCODE -ne 0) { throw 'FX GL probe failed' }
} finally { Pop-Location }
