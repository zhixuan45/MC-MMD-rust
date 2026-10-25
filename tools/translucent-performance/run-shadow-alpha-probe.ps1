param(
    [string]$Jdk = 'C:\Program Files\Zulu\zulu-21',
    [string]$Instance = 'D:\minecraft\.minecraft\versions\1.21.1-NeoForge_21.1.252',
    [string]$BslPack = 'BSL_v10.1.8.zip'
)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$taskOutput = Join-Path $taskRoot 'build/translucent-performance'
$taskCp = (Get-Content -LiteralPath (Join-Path $taskOutput 'classpath.txt') -Raw).Trim()
# Fresh output prevents permission failures from falling through to stale probe/helper classes.
$taskClasses = Join-Path $taskOutput ('shadow-alpha-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskClasses | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskIris = Get-ChildItem -LiteralPath (Join-Path $Instance 'mods') -Filter 'iris-*.jar' | Select-Object -First 1
if (!$taskIris) { throw 'Missing Iris jar for actual alpha-test expression' }
$taskZip = [System.IO.Compression.ZipFile]::OpenRead($taskIris.FullName)
try {
    $taskEntry = $taskZip.Entries | Where-Object { $_.FullName -match '/jcpp-[^/]+\.jar$' } | Select-Object -First 1
    if (!$taskEntry) { throw 'Missing Iris preprocessor' }
    $taskJcpp = Join-Path $taskClasses 'jcpp.jar'
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($taskEntry, $taskJcpp)
} finally { $taskZip.Dispose() }
$taskCp += ';' + $taskIris.FullName + ';' + $taskJcpp
$taskCache = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
foreach ($taskModule in @('lwjgl', 'lwjgl-glfw', 'lwjgl-opengl')) {
    $taskNative = Get-ChildItem -LiteralPath (Join-Path $taskCache "org.lwjgl/$taskModule/3.3.3") -Recurse -Filter '*-natives-windows.jar' | Select-Object -First 1
    if (!$taskNative) { throw "Missing Windows native: $taskModule" }
    $taskCp += ';' + $taskNative.FullName
}
Push-Location $taskRoot
try {
    $taskSources = @(
        (Join-Path $PSScriptRoot 'ShadowAlphaGlProbe.java'),
        (Join-Path $PSScriptRoot 'BslShadowFragment.java'),
        (Join-Path $taskRoot 'common/src/main/java/com/shiroha/mmdskin/config/ConfigManager.java'),
        (Join-Path $taskRoot 'common/src/main/java/com/shiroha/mmdskin/config/IToonConfig.java'),
        (Join-Path $taskRoot 'common/src/main/java/com/shiroha/mmdskin/render/material/SubMeshDrawHelper.java'),
        (Join-Path $taskRoot 'common/src/main/java/com/shiroha/mmdskin/render/material/ShadowTextureAlpha.java'),
        (Join-Path $taskRoot 'common/src/main/java/com/shiroha/mmdskin/render/material/DrawMaterialCache.java'),
        (Join-Path $taskRoot 'common/src/main/java/com/shiroha/mmdskin/render/material/MaterialShaderBinding.java'),
        (Join-Path $taskRoot 'common/src/main/java/com/shiroha/mmdskin/render/material/SphereTextureBinding.java'),
        (Join-Path $taskRoot 'common/src/main/java/com/shiroha/mmdskin/render/material/EntityPixelAlphaPass.java'),
        (Join-Path $taskRoot 'common/src/main/java/com/shiroha/mmdskin/render/material/MaterialDepthSeal.java')
    )
    & (Join-Path $Jdk 'bin/javac.exe') -proc:none -encoding UTF-8 -cp $taskCp -d $taskClasses $taskSources
    if ($LASTEXITCODE -ne 0) { throw 'Shadow alpha probe compilation failed' }
    & (Join-Path $Jdk 'bin/java.exe') -cp "$taskClasses;$taskCp" com.shiroha.mmdskin.render.material.ShadowAlphaGlProbe (Join-Path $Instance "shaderpacks/$BslPack")
    if ($LASTEXITCODE -ne 0) { throw 'Shadow alpha GL probe failed' }
} finally { Pop-Location }
