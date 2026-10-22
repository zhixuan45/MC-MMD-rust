param([string]$Jdk = 'C:\Program Files\Zulu\zulu-21', [string]$BslPack = '',
    [string]$Instance = 'D:\minecraft\.minecraft\versions\1.21.1-NeoForge_21.1.252')
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$taskOutput = Join-Path $taskRoot 'build/translucent-performance'
$taskCp = (Get-Content -LiteralPath (Join-Path $taskOutput 'classpath.txt') -Raw).Trim()
foreach ($taskModule in @('lwjgl', 'lwjgl-glfw', 'lwjgl-opengl')) {
    $taskCache = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1/org.lwjgl/$taskModule/3.3.3"
    $taskNative = Get-ChildItem -LiteralPath $taskCache -Recurse -Filter '*-natives-windows.jar' | Select-Object -First 1
    if (!$taskNative) { throw "Missing Windows native: $taskModule" }
    $taskCp += ';' + $taskNative.FullName
}
if ($BslPack) {
    $taskIris = Get-ChildItem -LiteralPath (Join-Path $Instance 'mods') -Filter 'iris-*.jar' | Select-Object -First 1
    $taskCp += ';' + (Join-Path $taskRoot 'build/iris-toon-probe')
    $taskCp += ';' + (Join-Path $taskRoot 'build/iris-toon-probe/jcpp.jar') + ';' + $taskIris.FullName
    # Some Sodium distributions wrap their actual runtime in a JarJar archive.
    $taskSodium = Get-ChildItem -LiteralPath (Join-Path $Instance 'mods') -Filter '*sodium*.jar' | Select-Object -First 1
    if ($taskSodium) {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $taskZip = [System.IO.Compression.ZipFile]::OpenRead($taskSodium.FullName)
        try {
            $taskEntry = $taskZip.Entries | Where-Object { $_.FullName -like '*-mod.jar' } | Select-Object -First 1
            if ($taskEntry) {
                $taskSodiumRuntime = Join-Path $taskOutput 'sodium-runtime.jar'
                [System.IO.Compression.ZipFileExtensions]::ExtractToFile($taskEntry, $taskSodiumRuntime, $true)
                $taskCp += ';' + $taskSodiumRuntime
            } else { $taskCp += ';' + $taskSodium.FullName }
        } finally { $taskZip.Dispose() }
    }
}
Push-Location $taskRoot
try {
    & (Join-Path $Jdk 'bin/javac.exe') -proc:none -encoding UTF-8 -cp $taskCp -d $taskOutput `
        (Join-Path $PSScriptRoot 'BlockEntityTransparencyGlProbe.java')
    if ($LASTEXITCODE -ne 0) { throw 'Block entity probe compilation failed' }
    if ($BslPack) {
        & (Join-Path $Jdk 'bin/javac.exe') -proc:none -encoding UTF-8 -cp $taskCp -d $taskOutput `
            (Join-Path $PSScriptRoot 'IrisTransparencyApiProbe.java')
        if ($LASTEXITCODE -ne 0) { throw 'Iris API probe compilation failed' }
        & (Join-Path $Jdk 'bin/java.exe') -cp "$taskCp;$taskOutput" `
            com.shiroha.mmdskin.render.material.IrisTransparencyApiProbe
        if ($LASTEXITCODE -ne 0) { throw 'Installed Iris API probe failed' }
    }
    $taskArguments = @('-cp', "$taskCp;$taskOutput", 'com.shiroha.mmdskin.render.material.BlockEntityTransparencyGlProbe')
    if ($BslPack) { $taskArguments += $BslPack }
    & (Join-Path $Jdk 'bin/java.exe') @taskArguments
    if ($LASTEXITCODE -ne 0) { throw 'Block entity transparency probe failed' }
} finally { Pop-Location }
