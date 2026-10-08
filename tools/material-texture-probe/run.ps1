param(
    [string]$Jdk = 'C:\Program Files\Zulu\zulu-21'
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$out = Join-Path $root 'build/material-texture-probe'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$cache = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
$cp = [System.Collections.Generic.List[string]]::new()
foreach ($p in @('common/build/classes/java/main','common/build/resources/main')) { $cp.Add((Join-Path $root $p)) }
foreach ($module in @('lwjgl','lwjgl-glfw','lwjgl-opengl')) {
    $dir = Join-Path $cache "org.lwjgl/$module/3.3.3"
    if (!(Test-Path $dir)) { throw "缺少 LWJGL 缓存: $module" }
    Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.jar' |
        Where-Object { $_.Name -eq "$module-3.3.3.jar" -or $_.Name -eq "$module-3.3.3-natives-windows.jar" } |
        ForEach-Object { $cp.Add($_.FullName) }
}
foreach ($dep in @('org.apache.logging.log4j/log4j-api','org.apache.logging.log4j/log4j-core','org.slf4j/slf4j-api','com.google.guava/guava')) {
    $dir = Join-Path $cache $dep
    if (Test-Path $dir) {
        $ver = Get-ChildItem -LiteralPath $dir -Directory | Sort-Object Name -Descending | Select-Object -First 1
        Get-ChildItem -LiteralPath $ver.FullName -Recurse -Filter '*.jar' |
            Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1 |
            ForEach-Object { $cp.Add($_.FullName) }
    }
}
$classpath = $cp -join ';'
$javac = Join-Path $Jdk 'bin/javac.exe'
$java = Join-Path $Jdk 'bin/java.exe'
Push-Location $root
try {
    & $javac -proc:none -encoding UTF-8 -cp $classpath -d $out (Join-Path $PSScriptRoot 'Probe.java')
    if ($LASTEXITCODE -ne 0) { throw '探针编译失败' }
    & $java -cp "$out;$classpath" probe.Probe
    if ($LASTEXITCODE -ne 0) { throw '探针验证失败' }
} finally { Pop-Location }
