param(
    [string]$Jdk = 'C:\Program Files\Zulu\zulu-21',
    [string]$BslPack = '',
    [string]$Instance = 'D:\minecraft\.minecraft\versions\1.21.1-NeoForge_21.1.252'
)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$taskOutput = Join-Path $taskRoot 'build/translucent-performance'
$taskCp = (Get-Content -LiteralPath (Join-Path $taskOutput 'classpath.txt') -Raw).Trim()
foreach ($taskModule in @('lwjgl', 'lwjgl-glfw', 'lwjgl-opengl')) {
    $taskCache = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/org.lwjgl/$taskModule/3.3.3"
    $taskNative = Get-ChildItem -LiteralPath $taskCache -Recurse -Filter '*-natives-windows.jar' | Select-Object -First 1
    if (!$taskNative) { throw "缺少本机库: $taskModule" }
    $taskCp += ';' + $taskNative.FullName
}
Push-Location $taskRoot
try {
    $taskSources = @('BatchGlProbe.java', 'ToonTransparencyGlProbe.java', 'ToonTransparencyLayersGlProbe.java') |
        ForEach-Object { Join-Path $PSScriptRoot $_ }
    & (Join-Path $Jdk 'bin/javac.exe') -proc:none -encoding UTF-8 -cp $taskCp -d $taskOutput $taskSources
    if ($LASTEXITCODE -ne 0) { throw '透明探针编译失败' }
    foreach ($taskProbe in @('ToonTransparencyGlProbe', 'ToonTransparencyLayersGlProbe')) {
        & (Join-Path $Jdk 'bin/java.exe') -cp "$taskOutput;$taskCp" "com.shiroha.mmdskin.render.material.$taskProbe"
        if ($LASTEXITCODE -ne 0) { throw "透明探针失败: $taskProbe" }
    }
    if ($BslPack) {
        $taskIris = Get-ChildItem -LiteralPath (Join-Path $Instance 'mods') -Filter 'iris-*.jar' | Select-Object -First 1
        $taskIrisOutput = Join-Path $taskRoot 'build/iris-toon-probe'
        $taskJcpp = Join-Path $taskIrisOutput 'jcpp.jar'
        if (!$taskIris -or !(Test-Path $taskJcpp) -or !(Test-Path (Join-Path $taskIrisOutput 'BslBatchGlProbe.class'))) {
            throw '请先运行 tools/iris-toon-probe/run.ps1 准备真实光影探针依赖'
        }
        $taskBslCp = "$taskCp;$taskOutput;$taskIrisOutput;$($taskIris.FullName);$taskJcpp"
        & (Join-Path $Jdk 'bin/javac.exe') -proc:none -encoding UTF-8 -cp $taskBslCp -d $taskOutput `
            (Join-Path $taskRoot 'tools/iris-toon-probe/BslTransparencyGlProbe.java')
        if ($LASTEXITCODE -ne 0) { throw 'BSL 分层探针编译失败' }
        foreach ($taskDimension in @('world0', 'world-1', 'world1')) {
            & (Join-Path $Jdk 'bin/java.exe') -cp "$taskOutput;$taskBslCp" BslTransparencyGlProbe $BslPack $taskDimension
            if ($LASTEXITCODE -ne 0) { throw "BSL 分层探针失败: $taskDimension" }
        }
    }
} finally { Pop-Location }
