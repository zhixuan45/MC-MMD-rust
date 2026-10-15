param(
    [Parameter(Mandatory = $true)][string[]]$Model,
    [string]$PhysicsConfig,
    [ValidateSet('default', 'legacy', 'geometry')][string]$Variant = 'default',
    [string]$Cases = 'idle,head-stop,head-side-hold,lateral-stop,arm-contact-stop,walk-stop,reset,rebuild',
    [string]$Fps = '60,144',
    [string]$ArmDegrees = '60',
    [string]$HeadDegrees = '18',
    [string]$SettleSeconds = '30',
    [string]$Output = 'Logs/hair-shoulder-20261015/runtime-probe.jsonl'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$oldJoints = [Environment]::GetEnvironmentVariable('MMD_HAIR_JOINT_COMPAT', 'Process')
$oldGeometry = [Environment]::GetEnvironmentVariable('MMD_HAIR_COLLIDER_COMPAT', 'Process')
Push-Location $repoRoot
try {
    # 对照仅影响本次探针进程，不改游戏配置或模型。
    [Environment]::SetEnvironmentVariable('MMD_HAIR_JOINT_COMPAT', $null, 'Process')
    [Environment]::SetEnvironmentVariable('MMD_HAIR_COLLIDER_COMPAT', $null, 'Process')
    if ($Variant -eq 'legacy') { $env:MMD_HAIR_JOINT_COMPAT = 'legacy' }
    if ($Variant -eq 'geometry') { $env:MMD_HAIR_COLLIDER_COMPAT = 'geometry' }
    $probeArgs = @('--out', $Output, '--cases', $Cases, '--fps', $Fps,
        '--arm-degrees', $ArmDegrees, '--head-degrees', $HeadDegrees, '--settle-seconds', $SettleSeconds)
    $sources = @()
    foreach ($item in $Model) {
        $path = (Resolve-Path -LiteralPath $item).Path
        $probeArgs += @('--model', $path)
        $sources += $path
    }
    if ($PhysicsConfig) {
        $configPath = (Resolve-Path -LiteralPath $PhysicsConfig).Path
        $probeArgs += @('--physics-config', $configPath)
        $sources += $configPath
    }
    & cargo build --offline --release --manifest-path rust_engine/Cargo.toml --example probe_hair_runtime
    if ($LASTEXITCODE -ne 0) { throw "探针构建失败：$LASTEXITCODE" }
    $exe = Join-Path $repoRoot 'rust_engine/target/release/examples/probe_hair_runtime.exe'
    $sources += $exe
    $outputPath = if ([IO.Path]::IsPathRooted($Output)) { $Output } else { Join-Path $repoRoot $Output }
    New-Item -ItemType Directory -Path (Split-Path -Parent $outputPath) -Force | Out-Null
    $sources | ForEach-Object { Get-FileHash -LiteralPath $_ -Algorithm SHA256 } |
        Select-Object Path, Hash | ConvertTo-Json |
        Set-Content -LiteralPath "$outputPath.inputs.json" -Encoding utf8
    & $exe @probeArgs
    if ($LASTEXITCODE -ne 0) { throw "探针包含失败 case：$LASTEXITCODE；详见 $outputPath" }
}
finally {
    [Environment]::SetEnvironmentVariable('MMD_HAIR_JOINT_COMPAT', $oldJoints, 'Process')
    [Environment]::SetEnvironmentVariable('MMD_HAIR_COLLIDER_COMPAT', $oldGeometry, 'Process')
    Pop-Location
}
