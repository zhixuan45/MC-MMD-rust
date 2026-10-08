param(
    [string]$Instance = 'D:\minecraft\.minecraft\versions\1.21.1-NeoForge_21.1.252',
    [string]$Jdk = 'C:\Program Files\Zulu\zulu-21'
)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$taskProbeDir = Join-Path $taskRoot 'build/iris-toon-probe'
New-Item -ItemType Directory -Force -Path $taskProbeDir | Out-Null
$taskIris = Get-ChildItem -LiteralPath (Join-Path $Instance 'mods') -Filter 'iris-*.jar' | Select-Object -First 1
if (!$taskIris) { throw '实例中没有 Iris Jar' }
# 只提取探针所需依赖，不修改实例。
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskZip = [System.IO.Compression.ZipFile]::OpenRead($taskIris.FullName)
try {
    $taskEntry = $taskZip.Entries | Where-Object { $_.FullName -match '/jcpp-[^/]+\.jar$' } | Select-Object -First 1
    $taskJcpp = Join-Path $taskProbeDir 'jcpp.jar'
    $taskSource = $taskEntry.Open()
    $taskDestination = [System.IO.File]::Create($taskJcpp)
    try { $taskSource.CopyTo($taskDestination) } finally { $taskSource.Dispose(); $taskDestination.Dispose() }
} finally { $taskZip.Dispose() }
$taskCache = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
$taskClasspath = [System.Collections.Generic.List[string]]::new()
foreach ($taskRelative in @('common/build/classes/java/main','common/build/resources/main')) {
    $taskClasspath.Add((Join-Path $taskRoot $taskRelative))
}
$taskClasspath.Add($taskIris.FullName)
$taskClasspath.Add($taskJcpp)
foreach ($taskModule in @('lwjgl','lwjgl-glfw','lwjgl-opengl')) {
    Get-ChildItem -LiteralPath (Join-Path $taskCache "org.lwjgl/$taskModule/3.3.3") -Recurse -Filter '*.jar' |
        Where-Object { $_.Name -eq "$taskModule-3.3.3.jar" -or $_.Name -eq "$taskModule-3.3.3-natives-windows.jar" } |
        ForEach-Object { $taskClasspath.Add($_.FullName) }
}
foreach ($taskDependency in @('org.apache.logging.log4j/log4j-api','org.apache.logging.log4j/log4j-core','org.slf4j/slf4j-api','com.google.guava/guava')) {
    $taskDependencyPath = Join-Path $taskCache $taskDependency
    $taskVersion = Get-ChildItem -LiteralPath $taskDependencyPath -Directory | Sort-Object Name -Descending | Select-Object -First 1
    Get-ChildItem -LiteralPath $taskVersion.FullName -Recurse -Filter '*.jar' |
        Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1 |
        ForEach-Object { $taskClasspath.Add($_.FullName) }
}
$taskCp = $taskClasspath -join ';'
$taskJava = Join-Path $Jdk 'bin/java.exe'
$taskJavac = Join-Path $Jdk 'bin/javac.exe'
Push-Location $taskRoot
try {
    & $taskJavac -proc:none -encoding UTF-8 -cp $taskCp -d $taskProbeDir (Join-Path $PSScriptRoot 'Probe.java') (Join-Path $PSScriptRoot 'OverlayProbe.java')
    if ($LASTEXITCODE -ne 0) { throw '探针编译失败' }
    & $taskJava -cp "$taskProbeDir;$taskCp" Probe (Join-Path $Instance 'shaderpacks')
    if ($LASTEXITCODE -ne 0) { throw '探针验证失败' }
} finally { Pop-Location }
