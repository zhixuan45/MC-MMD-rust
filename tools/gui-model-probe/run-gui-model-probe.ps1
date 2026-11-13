param(
    [string]$Jdk = 'C:\Program Files\Zulu\zulu-17',
    [string]$Output = ''
)

$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$classpathFile = Join-Path $taskRoot 'build/translucent-performance/classpath.txt'
if (!(Test-Path -LiteralPath $classpathFile)) {
    throw "Missing classpath file: $classpathFile"
}
if (!$Output) {
    $Output = Join-Path $taskRoot 'build/diagnostics/1201-camera-gui-euphoria-20261113/gui-gl'
}
$outputPath = [System.IO.Path]::GetFullPath($Output)
$classesPath = Join-Path $outputPath 'classes'
New-Item -ItemType Directory -Path $classesPath -Force | Out-Null

$taskCp = (Get-Content -LiteralPath $classpathFile -Raw).Trim()
if ($taskCp -notmatch '(?i)lwjgl-(\d+\.\d+\.\d+)\.jar') {
    throw 'Unable to determine LWJGL binding version from classpath.txt'
}
$lwjglVersion = $Matches[1]
$taskCache = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
foreach ($taskModule in @('lwjgl', 'lwjgl-glfw', 'lwjgl-opengl')) {
    $taskNative = Get-ChildItem -LiteralPath (Join-Path $taskCache "org.lwjgl/$taskModule/$lwjglVersion") `
        -Recurse -Filter '*-natives-windows.jar' | Select-Object -First 1
    if (!$taskNative) {
        throw "Missing Windows native for LWJGL $lwjglVersion`: $taskModule"
    }
    $taskCp += ';' + $taskNative.FullName
}

Push-Location $taskRoot
try {
    Write-Host "Using LWJGL binding/native version $lwjglVersion"
    & (Join-Path $Jdk 'bin/javac.exe') -proc:none -encoding UTF-8 -cp $taskCp -d $classesPath `
        (Join-Path $PSScriptRoot 'GuiModelVisibilityProbe.java')
    if ($LASTEXITCODE -ne 0) {
        throw 'GUI model probe compilation failed'
    }
    & (Join-Path $Jdk 'bin/java.exe') '-Dfile.encoding=UTF-8' `
        -cp "$classesPath;$taskCp" com.shiroha.mmdskin.render.backend.GuiModelVisibilityProbe $outputPath
    if ($LASTEXITCODE -ne 0) {
        throw 'GUI model visibility probe failed'
    }
} finally {
    Pop-Location
}
