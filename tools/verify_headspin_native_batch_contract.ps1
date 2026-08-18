[CmdletBinding()]
param(
    [string]$SourcePath
)

if ([string]::IsNullOrWhiteSpace($SourcePath)) {
    $SourcePath = Join-Path $PSScriptRoot '..\app\src\main\cpp\target_native_probe.cpp'
}

$source = Get-Content -Raw -LiteralPath $SourcePath
$loopStart = $source.IndexOf('for (std::size_t index = 0; index < writes.size(); ++index)')
$loopEnd = $source.IndexOf('    ProcessIdentity final_identity;', $loopStart)
if ($loopStart -lt 0 -or $loopEnd -le $loopStart) {
    throw "U32 batch loop boundaries were not found in $SourcePath"
}

$loop = $source.Substring($loopStart, $loopEnd - $loopStart)
$completedCountPattern = 'initial_identity,\s*index,\s*static_cast<int>\(index\)'
$completedCountOccurrences = [regex]::Matches($loop, $completedCountPattern).Count
$expectedStatuses = @(
    'READ_FAILED',
    'PARTIAL_READ',
    'PROFILE_MISMATCH',
    'WRITE_FAILED',
    'PARTIAL_WRITE',
    'VERIFY_FAILED'
)
$statusOccurrences = @{}
foreach ($status in $expectedStatuses) {
    $statusOccurrences[$status] = [regex]::Matches($loop, '"' + $status + '"').Count
}

$result = [ordered]@{
    source = (Resolve-Path -LiteralPath $SourcePath).Path
    loop_completed_count_expression = 'index (equals result.completed_count before each iteration)'
    completed_count_occurrences = $completedCountOccurrences
    expected_completed_count_occurrences = 8
    error_status_occurrences = $statusOccurrences
    result = if ($completedCountOccurrences -eq 8 -and
        (($statusOccurrences.Values | Measure-Object -Sum).Sum -eq 8)) { 'PASS' } else { 'FAIL' }
}
$result | ConvertTo-Json -Depth 5
if ($result.result -ne 'PASS') { exit 1 }
