param(
    [Parameter(Mandatory = $true)][string]$Baseline,
    [Parameter(Mandatory = $true)][string]$Candidate,
    [string]$Output = 'hair-runtime-ab-summary.json'
)

$ErrorActionPreference = 'Stop'

function Get-Position($Matrix) {
    if ($null -eq $Matrix -or $Matrix.Count -lt 15) { return $null }
    return @([double]$Matrix[12], [double]$Matrix[13], [double]$Matrix[14])
}

function Get-VectorDelta {
    param([object]$Before, [object]$After)
    if ($null -eq $Before -or $null -eq $After) { return $null }
    $beforeValues = @($Before)
    $afterValues = @($After)
    while ($beforeValues.Count -eq 1 -and $beforeValues[0] -is [array]) { $beforeValues = @($beforeValues[0]) }
    while ($afterValues.Count -eq 1 -and $afterValues[0] -is [array]) { $afterValues = @($afterValues[0]) }
    if ($beforeValues.Count -lt 3 -or $afterValues.Count -lt 3) { return $null }
    $dx = [double]$afterValues[0] - [double]$beforeValues[0]
    $dy = [double]$afterValues[1] - [double]$beforeValues[1]
    $dz = [double]$afterValues[2] - [double]$beforeValues[2]
    return @($dx, $dy, $dz)
}

function Get-VectorDistance($Before, $After) {
    $delta = Get-VectorDelta -Before $Before -After $After
    if ($null -eq $delta) { return $null }
    return [Math]::Sqrt(($delta[0] * $delta[0]) + ($delta[1] * $delta[1]) + ($delta[2] * $delta[2]))
}

function Read-ProbeFile([string]$Path, [string]$Label) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "找不到$Label JSONL：$Path" }
    $records = @{}
    $context = $null
    $lineNumber = 0
    foreach ($line in Get-Content -LiteralPath $Path) {
        $lineNumber++
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        try { $row = $line | ConvertFrom-Json -ErrorAction Stop }
        catch { throw "$Label JSONL 第 $lineNumber 行无法解析：$($_.Exception.Message)" }
        if ($row.kind -eq 'case_start') {
            $key = '{0}|{1}|{2}' -f $row.model_path, $row.case, $row.render_fps
            $context = [pscustomobject]@{
                Key = $key; ModelPath = [string]$row.model_path; ModelName = [string]$row.model_name
                Case = [string]$row.case; RenderFps = [int]$row.render_fps; PhysicsFps = $row.physics_fps
                Bodies = @{}; Constraints = @{}; PeakImpulseByBody = @{}
            }
            $records[$key] = $context
            continue
        }
        if ($row.kind -ne 'sample' -or $null -eq $context) { continue }

        if ($row.contacts.points) {
            foreach ($point in $row.contacts.points) {
                if ($point.a_is_hair) {
                    $bodyKey = [string]$point.a_index
                    $impulse = [double]$point.upward_impulse_on_hair_a
                    if (-not $context.PeakImpulseByBody.ContainsKey($bodyKey) -or $impulse -gt $context.PeakImpulseByBody[$bodyKey]) {
                        $context.PeakImpulseByBody[$bodyKey] = $impulse
                    }
                }
                if ($point.b_is_hair) {
                    $bodyKey = [string]$point.b_index
                    $impulse = [double]$point.upward_impulse_on_hair_b
                    if (-not $context.PeakImpulseByBody.ContainsKey($bodyKey) -or $impulse -gt $context.PeakImpulseByBody[$bodyKey]) {
                        $context.PeakImpulseByBody[$bodyKey] = $impulse
                    }
                }
            }
        }
        foreach ($joint in @($row.constraints)) {
            if ($null -eq $joint) { continue }
            $jointKey = '{0}|{1}|{2}' -f $joint.body_a, $joint.body_b, $joint.name
            $anchorError = [double]$joint.anchor_error
            if (-not $context.Constraints.ContainsKey($jointKey)) {
                $context.Constraints[$jointKey] = [pscustomobject]@{
                    Name = [string]$joint.name; BodyA = [string]$joint.body_a; BodyB = [string]$joint.body_b
                    PeakAnchorError = $anchorError; Final = $null
                }
            }
            $constraint = $context.Constraints[$jointKey]
            if ($anchorError -gt $constraint.PeakAnchorError) { $constraint.PeakAnchorError = $anchorError }
            if ($row.stage -eq 'case-final') {
                $constraint.Final = [pscustomobject]@{
                    AnchorError = $anchorError; AnchorA = @($joint.anchor_a); AnchorB = @($joint.anchor_b)
                }
            }
        }

        if ($row.stage -eq 'case-final') {
            foreach ($body in @($row.body_snapshots)) {
                if ($null -eq $body) { continue }
                $renderPosition = Get-Position $body.render_transform
                $boneSnapshot = $null
                foreach ($bone in @($row.hair_bones_and_skin_points)) {
                    if ($null -ne $bone -and [int]$bone.bone_index -eq [int]$body.bone_index) {
                        $boneSnapshot = $bone
                        break
                    }
                }
                $finalBonePosition = if ($null -ne $boneSnapshot) { Get-Position $boneSnapshot.final_bone_world_matrix } else { $null }
                $representativeVertexPosition = if ($null -ne $boneSnapshot) { @($boneSnapshot.vertex_skinned) } else { $null }
                $visualBodyPosition = $null
                $renderVisualBodyDelta = $null
                $renderVisualBodyDistance = $null
                if ($body.mode -eq 'PhysicsWithBone' -and $null -ne $body.body_from_final_bone_transform) {
                    $visualBodyPosition = Get-Position $body.body_from_final_bone_transform
                    $renderVisualBodyDelta = Get-VectorDelta -Before $visualBodyPosition -After $renderPosition
                    $renderVisualBodyDistance = Get-VectorDistance $visualBodyPosition $renderPosition
                }
                $context.Bodies[[string]$body.index] = [pscustomobject]@{
                    Index = [int]$body.index; Name = [string]$body.name; BoneIndex = [int]$body.bone_index
                    FinalBonePosition = $finalBonePosition; RepresentativeVertexPosition = $representativeVertexPosition
                    Mode = [string]$body.mode; RenderPosition = $renderPosition; VisualBodyPosition = $visualBodyPosition
                    RenderVisualBodyDelta = $renderVisualBodyDelta; RenderVisualBodyDistance = $renderVisualBodyDistance
                    PeakUpwardImpulse = if ($context.PeakImpulseByBody.ContainsKey([string]$body.index)) { [double]$context.PeakImpulseByBody[[string]$body.index] } else { 0.0 }
                }
            }
        }
    }
    return $records
}

$baselineRecords = Read-ProbeFile $Baseline 'baseline'
$candidateRecords = Read-ProbeFile $Candidate 'candidate'
$allKeys = @($baselineRecords.Keys + $candidateRecords.Keys | Sort-Object -Unique)
$comparisons = [System.Collections.Generic.List[object]]::new()

foreach ($key in $allKeys) {
    $before = if ($baselineRecords.ContainsKey($key)) { $baselineRecords[$key] } else { $null }
    $after = if ($candidateRecords.ContainsKey($key)) { $candidateRecords[$key] } else { $null }
    $source = if ($null -ne $after) { $after } else { $before }
    $bodyKeys = @()
    if ($null -ne $before) { $bodyKeys += $before.Bodies.Keys }
    if ($null -ne $after) { $bodyKeys += $after.Bodies.Keys }
    $bodyKeys = @($bodyKeys | Sort-Object -Unique { [int]$_ })
    $bodyRows = [System.Collections.Generic.List[object]]::new()
    foreach ($bodyKey in $bodyKeys) {
        $left = if ($null -ne $before -and $before.Bodies.ContainsKey([string]$bodyKey)) { $before.Bodies[[string]$bodyKey] } else { $null }
        $right = if ($null -ne $after -and $after.Bodies.ContainsKey([string]$bodyKey)) { $after.Bodies[[string]$bodyKey] } else { $null }
        $body = if ($null -ne $right) { $right } else { $left }
        $beforeImpulse = if ($null -ne $left) { $left.PeakUpwardImpulse } else { $null }
        $afterImpulse = if ($null -ne $right) { $right.PeakUpwardImpulse } else { $null }
        $impulseDelta = if ($null -ne $beforeImpulse -and $null -ne $afterImpulse) { $afterImpulse - $beforeImpulse } else { $null }
        $bodyRows.Add([pscustomobject]@{
            index = $body.Index; name = $body.Name; bone_index = $body.BoneIndex; mode = $body.Mode
            baseline_render_position = if ($null -ne $left) { $left.RenderPosition } else { $null }
            candidate_render_position = if ($null -ne $right) { $right.RenderPosition } else { $null }
            render_position_delta = if ($null -ne $left -and $null -ne $right) { Get-VectorDelta -Before $left.RenderPosition -After $right.RenderPosition } else { $null }
            render_position_delta_length = if ($null -ne $left -and $null -ne $right) { Get-VectorDistance -Before $left.RenderPosition -After $right.RenderPosition } else { $null }
            baseline_final_bone_position = if ($null -ne $left) { $left.FinalBonePosition } else { $null }
            candidate_final_bone_position = if ($null -ne $right) { $right.FinalBonePosition } else { $null }
            final_bone_position_delta = if ($null -ne $left -and $null -ne $right) { Get-VectorDelta -Before $left.FinalBonePosition -After $right.FinalBonePosition } else { $null }
            baseline_representative_vertex_position = if ($null -ne $left) { $left.RepresentativeVertexPosition } else { $null }
            candidate_representative_vertex_position = if ($null -ne $right) { $right.RepresentativeVertexPosition } else { $null }
            representative_vertex_position_delta = if ($null -ne $left -and $null -ne $right) { Get-VectorDelta -Before $left.RepresentativeVertexPosition -After $right.RepresentativeVertexPosition } else { $null }
            baseline_peak_upward_normal_impulse = $beforeImpulse
            candidate_peak_upward_normal_impulse = $afterImpulse
            peak_upward_normal_impulse_delta = $impulseDelta
            baseline_mode2_visual_body_position = if ($null -ne $left) { $left.VisualBodyPosition } else { $null }
            candidate_mode2_visual_body_position = if ($null -ne $right) { $right.VisualBodyPosition } else { $null }
            baseline_mode2_render_vs_visual_body_delta = if ($null -ne $left) { $left.RenderVisualBodyDelta } else { $null }
            candidate_mode2_render_vs_visual_body_delta = if ($null -ne $right) { $right.RenderVisualBodyDelta } else { $null }
            baseline_mode2_render_vs_visual_body_distance = if ($null -ne $left) { $left.RenderVisualBodyDistance } else { $null }
            candidate_mode2_render_vs_visual_body_distance = if ($null -ne $right) { $right.RenderVisualBodyDistance } else { $null }
            mode2_render_vs_visual_body_distance_delta = if ($null -ne $left -and $null -ne $right -and $null -ne $left.RenderVisualBodyDistance -and $null -ne $right.RenderVisualBodyDistance) { $right.RenderVisualBodyDistance - $left.RenderVisualBodyDistance } else { $null }
        })
    }
    $ordered = @($bodyRows | Sort-Object bone_index, index)
    $dynamic = @($ordered | Where-Object { $_.mode -eq 'PhysicsWithBone' })
    if ($dynamic.Count -gt 0) { $root = $dynamic[0]; $tip = $dynamic[-1] }
    elseif ($ordered.Count -gt 0) { $root = $ordered[0]; $tip = $ordered[-1] }
    else { $root = $null; $tip = $null }

    $constraintKeys = @()
    if ($null -ne $before) { $constraintKeys += $before.Constraints.Keys }
    if ($null -ne $after) { $constraintKeys += $after.Constraints.Keys }
    $constraintKeys = @($constraintKeys | Sort-Object -Unique)
    $constraintRows = [System.Collections.Generic.List[object]]::new()
    foreach ($constraintKey in $constraintKeys) {
        $left = if ($null -ne $before -and $before.Constraints.ContainsKey($constraintKey)) { $before.Constraints[$constraintKey] } else { $null }
        $right = if ($null -ne $after -and $after.Constraints.ContainsKey($constraintKey)) { $after.Constraints[$constraintKey] } else { $null }
        $constraint = if ($null -ne $right) { $right } else { $left }
        $leftFinal = if ($null -ne $left) { $left.Final } else { $null }
        $rightFinal = if ($null -ne $right) { $right.Final } else { $null }
        $constraintRows.Add([pscustomobject]@{
            name = $constraint.Name; body_a = $constraint.BodyA; body_b = $constraint.BodyB
            baseline_peak_anchor_error = if ($null -ne $left) { $left.PeakAnchorError } else { $null }
            candidate_peak_anchor_error = if ($null -ne $right) { $right.PeakAnchorError } else { $null }
            peak_anchor_error_delta = if ($null -ne $left -and $null -ne $right) { $right.PeakAnchorError - $left.PeakAnchorError } else { $null }
            baseline_final_anchor_error = if ($null -ne $leftFinal) { $leftFinal.AnchorError } else { $null }
            candidate_final_anchor_error = if ($null -ne $rightFinal) { $rightFinal.AnchorError } else { $null }
            baseline_final_anchor_a = if ($null -ne $leftFinal) { $leftFinal.AnchorA } else { $null }
            candidate_final_anchor_a = if ($null -ne $rightFinal) { $rightFinal.AnchorA } else { $null }
            baseline_final_anchor_b = if ($null -ne $leftFinal) { $leftFinal.AnchorB } else { $null }
            candidate_final_anchor_b = if ($null -ne $rightFinal) { $rightFinal.AnchorB } else { $null }
        })
    }
    $comparisons.Add([pscustomobject]@{
        model_path = $source.ModelPath; model_name = $source.ModelName; case = $source.Case
        render_fps = $source.RenderFps
        baseline_physics_fps = if ($null -ne $before) { $before.PhysicsFps } else { $null }
        candidate_physics_fps = if ($null -ne $after) { $after.PhysicsFps } else { $null }
        root_selection = 'PhysicsWithBone中最小bone_index；没有时取全部采样刚体最小bone_index'
        tip_selection = 'PhysicsWithBone中最大bone_index；没有时取全部采样刚体最大bone_index'
        root = if ($null -ne $root) { [pscustomobject]@{ name = $root.name; bone_index = $root.bone_index; baseline = $root.baseline_render_position; candidate = $root.candidate_render_position; delta = $root.render_position_delta; delta_length = $root.render_position_delta_length } } else { $null }
        tip = if ($null -ne $tip) { [pscustomobject]@{ name = $tip.name; bone_index = $tip.bone_index; baseline = $tip.baseline_render_position; candidate = $tip.candidate_render_position; delta = $tip.render_position_delta; delta_length = $tip.render_position_delta_length } } else { $null }
        bodies = @($bodyRows)
        constraints = @($constraintRows)
        metric_limit = '离线JSONL快照比较；mode2=PhysicsWithBone。render差比较使用最终骨骼姿态和绑定骨骼刚体offset重建的visual body transform，不比较刚体中心与骨骼原点；骨骼位置和代表蒙皮顶点位置分开报告，代理中心位移不能单独推出网格位移。接触峰值受采样窗口/截断影响，骨骼索引端点只是root/tip代理，均不构成游戏视觉或游戏验收结论。'
    })
}

$summary = [pscustomobject]@{
    kind = 'hair-runtime-ab-summary'
    baseline = (Resolve-Path -LiteralPath $Baseline).Path
    candidate = (Resolve-Path -LiteralPath $Candidate).Path
    generated_utc = [DateTime]::UtcNow.ToString('o')
    comparisons = @($comparisons)
}
$outputPath = if ([System.IO.Path]::IsPathRooted($Output)) { $Output } else { Join-Path (Get-Location).Path $Output }
$parent = Split-Path -Parent $outputPath
if ($parent) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
$summary | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $outputPath -Encoding utf8
Write-Output "已写入A/B汇总：$outputPath（$($comparisons.Count)组case/FPS）"
