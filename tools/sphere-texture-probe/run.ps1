param(
    [string]$PmxPath,
    [string]$JdkHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$classpathFile = Join-Path $repo 'build/sphere-texture-probe/classpath.txt'
$output = Join-Path $repo 'build/sphere-texture-probe/classes'
if (-not (Test-Path -LiteralPath $classpathFile)) {
    throw "缺少 Gradle runtimeClasspath: $classpathFile"
}
if (-not $JdkHome) {
    $javac = (Get-Command javac -ErrorAction Stop).Source
    $java = (Get-Command java -ErrorAction Stop).Source
} else {
    $javac = Join-Path $JdkHome 'bin/javac.exe'
    $java = Join-Path $JdkHome 'bin/java.exe'
}
if (-not (Test-Path -LiteralPath $javac) -or -not (Test-Path -LiteralPath $java)) {
    throw "找不到 JDK javac/java，请传入 -JdkHome。"
}

New-Item -ItemType Directory -Force -Path $output | Out-Null
$runtimeClasspath = (Get-Content -Raw -LiteralPath $classpathFile).Trim()
$lwjglNatives = @(Get-ChildItem -Path (Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/org.lwjgl') `
    -Filter '*-3.3.3-natives-windows.jar' -Recurse -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch 'arm64|x86' } | ForEach-Object FullName)
if ($lwjglNatives.Count -gt 0) {
    $runtimeClasspath += ';' + ($lwjglNatives -join ';')
}

$source = Join-Path $PSScriptRoot 'Probe.java'
& $javac -encoding UTF-8 -proc:none -cp "$runtimeClasspath" -d $output $source
if ($LASTEXITCODE -ne 0) { throw "javac 失败: $LASTEXITCODE" }
$runClasspath = "$output;$runtimeClasspath"
$probeArgs = @()
if ($PmxPath) {
    $probeArgs += @('-PmxPath', (Resolve-Path -LiteralPath $PmxPath).Path)
}
$log = Join-Path $repo 'build/sphere-texture-probe/sphere.log'
"java Probe $($probeArgs -join ' ')" | Set-Content -LiteralPath $log -Encoding UTF8
& $java '-Djava.awt.headless=true' -cp $runClasspath Probe @probeArgs 2>&1 |
    Tee-Object -FilePath $log -Append
$javaExitCode = $LASTEXITCODE
if ($javaExitCode -ne 0) { throw "sphere texture probe 失败: $javaExitCode" }
