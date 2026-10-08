param([string]$Jdk = 'C:\Program Files\Zulu\zulu-21')
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$taskOutput = Join-Path $taskRoot 'build/outline-probe'
New-Item -ItemType Directory -Force -Path $taskOutput | Out-Null
$taskCache = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/org.lwjgl'
$taskJars = foreach ($module in @('lwjgl','lwjgl-glfw','lwjgl-opengl')) {
    Get-ChildItem -LiteralPath (Join-Path $taskCache "$module/3.3.3") -Recurse -Filter '*.jar' |
        Where-Object { $_.Name -eq "$module-3.3.3.jar" -or $_.Name -eq "$module-3.3.3-natives-windows.jar" } |
        ForEach-Object { $_.FullName }
}
$taskClasspath = $taskJars -join ';'
# 仅在隔离测试进程中替代 Minecraft 依赖，生产 pass 仍从源码编译。
$taskStateOutput = Join-Path $taskOutput 'state'
$taskStubRoot = Join-Path $taskStateOutput 'src'
$taskStubs = @{
    'com/mojang/blaze3d/systems/RenderSystem.java' = @'
package com.mojang.blaze3d.systems;
import org.lwjgl.opengl.GL46C;
public final class RenderSystem {
    public static void depthMask(boolean value) { GL46C.glDepthMask(value); }
    public static void depthFunc(int value) { GL46C.glDepthFunc(value); }
    public static void enableCull() { GL46C.glEnable(GL46C.GL_CULL_FACE); }
}
'@
    'com/shiroha/mmdskin/render/shader/ToonConfig.java' = @'
package com.shiroha.mmdskin.render.shader;
public final class ToonConfig {
    private static final ToonConfig INSTANCE = new ToonConfig();
    public static ToonConfig getInstance() { return INSTANCE; }
    public float getOutlineWidth() { return 0.0022f; }
    public float getOutlineColorR() { return 0; }
    public float getOutlineColorG() { return 0; }
    public float getOutlineColorB() { return 0; }
}
'@
    'com/shiroha/mmdskin/render/shader/ToonShaderBase.java' = @'
package com.shiroha.mmdskin.render.shader;
import java.nio.FloatBuffer;
public class ToonShaderBase {
    public int getOutlinePositionLocation() { return 0; }
    public int getOutlineNormalLocation() { return 1; }
    public void useOutline() {}
    public void setOutlineProjectionMatrix(FloatBuffer value) {}
    public void setOutlineModelViewMatrix(FloatBuffer value) {}
    public void setOutlineWidth(float value) {}
    public void setOutlineColor(float r, float g, float b) {}
    public void setOutlineAlpha(float value) {}
}
'@
}
$taskStubFiles = foreach ($taskStub in $taskStubs.GetEnumerator()) {
    $taskStubPath = Join-Path $taskStubRoot $taskStub.Key
    New-Item -ItemType Directory -Force -Path (Split-Path $taskStubPath -Parent) | Out-Null
    [System.IO.File]::WriteAllText($taskStubPath, $taskStub.Value, [System.Text.UTF8Encoding]::new($false))
    $taskStubPath
}
Push-Location $taskRoot
try {
    & (Join-Path $Jdk 'bin/javac.exe') -proc:none -encoding UTF-8 -cp $taskClasspath -d $taskOutput (Join-Path $PSScriptRoot 'Probe.java')
    if ($LASTEXITCODE -ne 0) { throw '描边探针编译失败' }
    & (Join-Path $Jdk 'bin/javac.exe') -proc:none -encoding UTF-8 -cp $taskClasspath -d $taskOutput (Join-Path $PSScriptRoot 'PaperDollProbe.java')
    if ($LASTEXITCODE -ne 0) { throw '纸娃娃描边探针编译失败' }
    & (Join-Path $Jdk 'bin/java.exe') -cp "$taskOutput;$taskClasspath" Probe
    if ($LASTEXITCODE -ne 0) { throw '描边探针验证失败' }
    & (Join-Path $Jdk 'bin/java.exe') -cp "$taskOutput;$taskClasspath" PaperDollProbe --baseline
    if ($LASTEXITCODE -ne 0) { throw '纸娃娃描边基线验证失败' }
    & (Join-Path $Jdk 'bin/java.exe') -cp "$taskOutput;$taskClasspath" PaperDollProbe --fixed
    if ($LASTEXITCODE -ne 0) { throw '纸娃娃描边修复回归验证失败' }
    $taskStateSources = @($taskStubFiles) + @(
        (Join-Path $taskRoot 'common/src/main/java/com/shiroha/mmdskin/render/outline/OutlineRenderPass.java'),
        (Join-Path $PSScriptRoot 'PassStateProbe.java')
    )
    & (Join-Path $Jdk 'bin/javac.exe') -proc:none -encoding UTF-8 -cp $taskClasspath -d $taskStateOutput @taskStateSources
    if ($LASTEXITCODE -ne 0) { throw '描边状态探针编译失败' }
    & (Join-Path $Jdk 'bin/java.exe') -cp "$taskStateOutput;$taskClasspath" PassStateProbe
    if ($LASTEXITCODE -ne 0) { throw '描边状态恢复验证失败' }
} finally { Pop-Location }
