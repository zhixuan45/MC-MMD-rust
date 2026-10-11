param(
    [Parameter(Mandatory)][string]$SourceDirectory,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [string]$SevenZip = 'C:\Program Files\7-Zip\7z.exe',
    [string[]]$ArchiveNames = @(),
    [int]$LegacyCodePage = 0
)
$ErrorActionPreference = 'Stop'
$sourceRoot = (Resolve-Path -LiteralPath $SourceDirectory).Path
$outputRoot = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $outputRoot) { throw '输出目录必须为新目录，以保留旧证据。' }
New-Item -ItemType Directory -Path $outputRoot | Out-Null
$archives = @(Get-ChildItem -LiteralPath $sourceRoot -File | Where-Object Extension -In '.zip','.rar' | Sort-Object Name)
if ($ArchiveNames.Count -gt 0) { $archives = @($archives | Where-Object Name -In $ArchiveNames) }
$encodingArgs = @()
if ($LegacyCodePage -gt 0) { $encodingArgs = @('-mcp='+$LegacyCodePage) }
$manifest = @()
for ($i=0; $i -lt $archives.Count; $i++) {
    $archive = $archives[$i]
    $destination = Join-Path $outputRoot ('asset-{0:d2}' -f ($i+1))
    $listing = @(& $SevenZip l -slt -ba -sccUTF-8 @encodingArgs $archive.FullName)
    if ($LASTEXITCODE -ne 0) { throw "无法列出归档：$($archive.Name)" }
    # 解压前验证所有路径和链接；只向独立证据目录写入。
    foreach ($line in $listing) {
        if ($line -match '^Path = (.*)$') {
            $relative = $Matches[1]
            if ([IO.Path]::IsPathRooted($relative) -or $relative.Contains(':')) { throw "拒绝绝对路径/数据流：$relative" }
            $resolved = [IO.Path]::GetFullPath((Join-Path $destination $relative))
            if (-not $resolved.StartsWith($destination+'\',[StringComparison]::OrdinalIgnoreCase)) { throw "路径越界：$relative" }
        }
        if ($line -match '^(Symbolic Link|Hard Link|Copy Link) = (.+)$' -or $line -eq 'Alternate Stream = +') { throw '归档含链接/数据流，未解压。' }
        if ($line -eq 'Encrypted = +') { throw '归档加密，需提供密码。' }
    }
    $hashBefore = (Get-FileHash -LiteralPath $archive.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $listing | Set-Content -LiteralPath (Join-Path $outputRoot ('archive-{0:d2}-listing.txt' -f ($i+1))) -Encoding utf8
    & $SevenZip x -y -sccUTF-8 @encodingArgs ('-o'+$destination) $archive.FullName *> (Join-Path $outputRoot ('archive-{0:d2}-extract.txt' -f ($i+1)))
    if ($LASTEXITCODE -ne 0) { throw "解压失败：$($archive.Name)" }
    $hashAfter = (Get-FileHash -LiteralPath $archive.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($hashBefore -ne $hashAfter) { throw '归档在验证期间改变。' }
    $manifest += [ordered]@{archive=$archive.FullName;sha256=$hashBefore;size=$archive.Length;extracted_to=$destination;legacy_code_page=$LegacyCodePage}
}
$manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $outputRoot 'archive-manifest.json') -Encoding utf8
$manifest | ConvertTo-Json -Depth 4
