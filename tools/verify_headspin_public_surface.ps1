$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$relativeFiles = @(
    'app/src/main/java/me/dartcv/minix/root/RootModels.kt',
    'app/src/main/aidl/me/dartcv/minix/root/IRootFeatureBridge.aidl',
    'app/src/main/java/me/dartcv/minix/root/RootInjection.kt',
    'app/src/main/java/me/dartcv/minix/root/RootFeatureBridgeClient.kt',
    'app/src/main/java/me/dartcv/minix/ui/MinixApp.kt'
)
$pattern = 'head[_-]?spin|headSpin|HEAD_SPIN'
$checks = foreach ($relative in $relativeFiles) {
    $path = Join-Path $root $relative
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Missing public-surface file: $relative"
    }
    $hits = @(Select-String -LiteralPath $path -Pattern $pattern -CaseSensitive:$false)
    [ordered]@{
        file = $relative.Replace('\', '/')
        hit_count = $hits.Count
        matching_lines = @($hits | ForEach-Object { $_.LineNumber })
    }
}
$result = [ordered]@{
    schema_version = 1
    checked_pattern = $pattern
    files = @($checks)
    result = if (@($checks | Where-Object { $_.hit_count -ne 0 }).Count -eq 0) { 'PASS' } else { 'FAIL' }
}
$out = Join-Path $root 'work/headspeed-recovery-20260817/headspin_public_surface_check.json'
$result | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $out -Encoding UTF8
$result | ConvertTo-Json -Depth 6
if ($result.result -ne 'PASS') { exit 1 }
