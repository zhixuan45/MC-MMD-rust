param(
    [string]$Jdk = 'C:\Program Files\Zulu\zulu-21',
    [string]$Jar
)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$taskOutput = Join-Path $taskRoot 'build/shader-state-probe'
$taskCpFile = Join-Path $taskRoot 'build/sphere-texture-probe/classpath.txt'
if (!(Test-Path -LiteralPath $taskCpFile)) {
    throw 'Run gradlew :common:writeSphereProbeClasspath -I tools/sphere-texture-probe/classpath.gradle first.'
}
$taskCp = (Get-Content -LiteralPath $taskCpFile -Raw).Trim()
if ($Jar) {
    $taskCp = (Resolve-Path -LiteralPath $Jar).Path + ';' + $taskCp
}
$taskCache = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/org.lwjgl'
foreach ($taskModule in @('lwjgl', 'lwjgl-glfw', 'lwjgl-opengl')) {
    $taskNative = Get-ChildItem -LiteralPath (Join-Path $taskCache "$taskModule/3.3.3") -Recurse -Filter '*-natives-windows.jar' |
        Select-Object -First 1
    if (!$taskNative) { throw "Missing Windows native: $taskModule" }
    $taskCp += ';' + $taskNative.FullName
}
New-Item -ItemType Directory -Force -Path $taskOutput | Out-Null
Push-Location $taskRoot
try {
    & (Join-Path $Jdk 'bin/javac.exe') -encoding UTF-8 -proc:none -cp $taskCp -d $taskOutput (Join-Path $PSScriptRoot 'Probe.java')
    if ($LASTEXITCODE -ne 0) { throw 'Shader state probe compilation failed.' }
    & (Join-Path $Jdk 'bin/java.exe') '-Djava.awt.headless=true' -cp "$taskOutput;$taskCp" Probe 2>&1 |
        Tee-Object -FilePath (Join-Path $taskOutput 'result.log')
    if ($LASTEXITCODE -ne 0) { throw 'Shader state probe failed.' }
} finally { Pop-Location }
