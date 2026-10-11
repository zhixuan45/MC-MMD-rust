param(
    [Parameter(Mandatory = $true)][string]$LogPath,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\..\Logs\latest-log-audit')
)
$ErrorActionPreference = 'Stop'
$culture = [Globalization.CultureInfo]::InvariantCulture
$lines = Get-Content -LiteralPath $LogPath
$anchors = [Collections.Generic.List[object]]::new()
$contacts = [Collections.Generic.List[object]]::new()
$worlds = [Collections.Generic.List[object]]::new()
$textureFailures = [Collections.Generic.List[object]]::new()
$categories = @{}
$levels = @{}
for ($i = 0; $i -lt $lines.Count; $i++) {
    $line = $lines[$i]
    $lineNumber = $i + 1
    if ($line -match '/(INFO|WARN|ERROR)\]') { $levels[$Matches[1]]++ }
    if ($line -match '\[MMD诊断\]\[([^]]+)\]') { $categories[$Matches[1]]++ }
    # 原样保存日志时间；不推断本地化日期或时区。
    $time = if ($line -match '^\[([^]]+)\]') { $Matches[1] } else { '' }
    $handle = if ($line -match '\bhandle=(\d+)') { $Matches[1] } else { '' }
    if ($line -match 'worst_anchor joint=(.*?) body_a=(.*?) body_b=(.*?) error=([\d.Ee+-]+)') {
        $anchors.Add([pscustomobject]@{ line=$lineNumber; time=$time; handle=$handle; joint=$Matches[1]; body_a=$Matches[2]; body_b=$Matches[3]; error=[double]::Parse($Matches[4], $culture) })
    }
    if ($line -match 'contact rank=(\d+) a=(\d+) name_a=(.*?) b=(\d+) name_b=(.*?) points=(\d+) penetration=([\d.Ee+-]+) impulse=([\d.Ee+-]+)') {
        $contacts.Add([pscustomobject]@{ line=$lineNumber; time=$time; handle=$handle; rank=$Matches[1]; a=$Matches[2]; name_a=$Matches[3]; b=$Matches[4]; name_b=$Matches[5]; points=$Matches[6]; penetration=[double]::Parse($Matches[7],$culture); impulse=[double]::Parse($Matches[8],$culture) })
    }
    if ($line -match 'worlds=(\d+).*?shapes=(\d+).*?bodies=(\d+).*?constraints=(\d+).*?motionStates=(\d+)') {
        $worlds.Add([pscustomobject]@{ line=$lineNumber; time=$time; worlds=$Matches[1]; shapes=$Matches[2]; bodies=$Matches[3]; constraints=$Matches[4]; motion_states=$Matches[5] })
    }
    if ($line.Contains('Failed to load texture:')) {
        $textureFailures.Add([pscustomobject]@{ line=$lineNumber; time=$time; context=$line.Substring($line.IndexOf('Failed to load texture:')) })
    }
}
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$anchors | Export-Csv -NoTypeInformation -Encoding utf8 -LiteralPath (Join-Path $OutputDirectory 'anchors.csv')
$contacts | Export-Csv -NoTypeInformation -Encoding utf8 -LiteralPath (Join-Path $OutputDirectory 'contacts.csv')
$worlds | Export-Csv -NoTypeInformation -Encoding utf8 -LiteralPath (Join-Path $OutputDirectory 'worlds.csv')
$textureFailures | Export-Csv -NoTypeInformation -Encoding utf8 -LiteralPath (Join-Path $OutputDirectory 'texture-failures.csv')
$summary = [ordered]@{
    source_sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $LogPath).Hash
    lines = $lines.Count
    levels = $levels
    mmd_categories = $categories
    mmd_records = ($categories.Values | Measure-Object -Sum).Sum
    texture_failures = $textureFailures.Count
    sampled_anchor_peak = ($anchors.error | Measure-Object -Maximum).Maximum
    sampled_penetration_peak = ($contacts.penetration | Measure-Object -Maximum).Maximum
    sampled_impulse_peak = ($contacts.impulse | Measure-Object -Maximum).Maximum
    note = '瞬时抽样中的最大值，不是连续窗口峰值；没有补造资源路径或帧间行为。'
}
$summary | ConvertTo-Json -Depth 4 | Set-Content -Encoding utf8 -LiteralPath (Join-Path $OutputDirectory 'log-summary.json')
$summary | ConvertTo-Json -Depth 4
