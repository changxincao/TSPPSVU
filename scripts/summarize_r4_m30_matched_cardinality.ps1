param(
    [string]$Root = 'analysis/TRB_reviewer_revision/305_r4_m30_matched_cardinality_olist_hmin_20260814/coverage_100/mqc_100',
    [string]$OriginalCsv = 'analysis/TRB_reviewer_revision/03_olist_rolling_min_h_coverage_mqc_20260811/coverage_100/mqc_100/rolling_trials.csv'
)

$ErrorActionPreference = 'Stop'
$rows = @(Import-Csv -LiteralPath (Join-Path $Root 'rolling_trials.csv'))
if ($rows.Count -ne 102) { throw "Expected 102 rows, got $($rows.Count)" }
$originalCsaa = @(Import-Csv -LiteralPath $OriginalCsv | Where-Object method -eq 'CSAA')
if ($originalCsaa.Count -ne 51) { throw "Expected 51 original CSAA rows, got $($originalCsaa.Count)" }
$summaryRows = @($rows) + @($originalCsaa)

$methods = @('RCSAA', 'CSAA_MATCHED_RCSAA_COUNT')
foreach ($method in $methods) {
    $methodRows = @($rows | Where-Object method -eq $method)
    if ($methodRows.Count -ne 51) { throw "$method rows=$($methodRows.Count)" }
    if (($methodRows.trialId | Sort-Object -Unique).Count -ne 51) {
        throw "$method has duplicate trials"
    }
    if (@($methodRows | Where-Object certified_optimal -ne 'true').Count -ne 0) {
        throw "$method has uncertified rows"
    }
}

function Sample-Sd([double[]]$Values) {
    $mean = ($Values | Measure-Object -Average).Average
    $sum = 0.0
    foreach ($value in $Values) { $sum += ($value - $mean) * ($value - $mean) }
    return [math]::Sqrt($sum / ($Values.Count - 1))
}

function Quantile([double[]]$Values, [double]$Probability) {
    $sorted = @($Values | Sort-Object)
    $position = $Probability * ($sorted.Count - 1)
    $lower = [math]::Floor($position)
    $upper = [math]::Ceiling($position)
    if ($lower -eq $upper) { return $sorted[$lower] }
    $fraction = $position - $lower
    return $sorted[$lower] * (1.0 - $fraction) + $sorted[$upper] * $fraction
}

$summary = @()
foreach ($method in @('CSAA', 'CSAA_MATCHED_RCSAA_COUNT', 'RCSAA')) {
    $methodRows = @($summaryRows | Where-Object method -eq $method)
    [double[]]$costs = @($methodRows | ForEach-Object { [double]$_.realized_obj })
    $q95 = Quantile $costs 0.95
    $summary += [pscustomobject]@{
        method = $method
        n = $costs.Count
        mean = ($costs | Measure-Object -Average).Average
        sd = Sample-Sd $costs
        q95 = $q95
        cvar95 = (@($costs | Where-Object { $_ + 1e-9 -ge $q95 }) |
            Measure-Object -Average).Average
        meanSelected = (@($methodRows | ForEach-Object { [double]$_.selected_count }) |
            Measure-Object -Average).Average
        meanTransport = (@($methodRows | ForEach-Object { [double]$_.transport_cost }) |
            Measure-Object -Average).Average
        meanSpot = (@($methodRows | ForEach-Object { [double]$_.spot_cost }) |
            Measure-Object -Average).Average
        meanPenalty = (@($methodRows | ForEach-Object { [double]$_.penalty_cost }) |
            Measure-Object -Average).Average
    }
}

$rcsaa = @($rows | Where-Object method -eq 'RCSAA' | Sort-Object { [int]$_.trialId })
$matched = @($rows | Where-Object method -eq 'CSAA_MATCHED_RCSAA_COUNT' |
    Sort-Object { [int]$_.trialId })
$detail = @()
$same = 0
$wins = 0
$ties = 0
$losses = 0
$jaccardSum = 0.0
$hammingSum = 0.0
$maxDecompositionError = 0.0

for ($index = 0; $index -lt 51; $index++) {
    if ($rcsaa[$index].trialId -ne $matched[$index].trialId) { throw 'Trial-pair mismatch' }
    if ($rcsaa[$index].selected_count -ne $matched[$index].selected_count) {
        throw "Selected-count mismatch at trial $index"
    }
    foreach ($row in @($rcsaa[$index], $matched[$index])) {
        $error = [math]::Abs([double]$row.realized_obj - [double]$row.transport_cost -
            [double]$row.spot_cost - [double]$row.penalty_cost)
        if ($error -gt $maxDecompositionError) { $maxDecompositionError = $error }
    }

    $left = $rcsaa[$index].yBinary.ToCharArray()
    $right = $matched[$index].yBinary.ToCharArray()
    $hamming = 0
    $intersection = 0
    $union = 0
    for ($carrier = 0; $carrier -lt $left.Count; $carrier++) {
        if ($left[$carrier] -ne $right[$carrier]) { $hamming++ }
        if ($left[$carrier] -eq '1' -and $right[$carrier] -eq '1') { $intersection++ }
        if ($left[$carrier] -eq '1' -or $right[$carrier] -eq '1') { $union++ }
    }
    $jaccard = if ($union -eq 0) { 1.0 } else { $intersection / $union }
    if ($hamming -eq 0) { $same++ }
    $jaccardSum += $jaccard
    $hammingSum += $hamming

    $improvement = [double]$matched[$index].realized_obj - [double]$rcsaa[$index].realized_obj
    if ($improvement -gt 1e-6) { $wins++ }
    elseif ($improvement -lt -1e-6) { $losses++ }
    else { $ties++ }

    $detail += [pscustomobject]@{
        trialId = [int]$rcsaa[$index].trialId
        testPeriodIdx = [int]$rcsaa[$index].testPeriodIdx
        selectedCount = [int]$rcsaa[$index].selected_count
        rcsaaY = $rcsaa[$index].yBinary
        matchedCsaaY = $matched[$index].yBinary
        hamming = $hamming
        jaccard = $jaccard
        rcsaaCost = [double]$rcsaa[$index].realized_obj
        matchedCsaaCost = [double]$matched[$index].realized_obj
        rcsaaImprovement = $improvement
    }
}

$baseline = $summary | Where-Object method -eq 'CSAA_MATCHED_RCSAA_COUNT'
$method = $summary | Where-Object method -eq 'RCSAA'
$paired = [pscustomobject]@{
    comparison = 'RCSAA_vs_CSAA_MATCHED_RCSAA_COUNT'
    n = 51
    sameSelectedCount = 51
    sameDecision = $same
    decisionAgreementRate = $same / 51.0
    meanHamming = $hammingSum / 51.0
    meanJaccard = $jaccardSum / 51.0
    rcsaaWins = $wins
    ties = $ties
    rcsaaLosses = $losses
    meanImprovementPct = 100.0 * ($baseline.mean - $method.mean) / $baseline.mean
    sdImprovementPct = 100.0 * ($baseline.sd - $method.sd) / $baseline.sd
    q95ImprovementPct = 100.0 * ($baseline.q95 - $method.q95) / $baseline.q95
    cvar95ImprovementPct = 100.0 * ($baseline.cvar95 - $method.cvar95) / $baseline.cvar95
}

$comparisons = @()
foreach ($definition in @(
    @('CSAA_MATCHED_RCSAA_COUNT', 'CSAA'),
    @('RCSAA', 'CSAA_MATCHED_RCSAA_COUNT'),
    @('RCSAA', 'CSAA')
)) {
    $left = $summary | Where-Object method -eq $definition[0]
    $right = $summary | Where-Object method -eq $definition[1]
    $comparisons += [pscustomobject]@{
        method = $definition[0]
        baseline = $definition[1]
        selectedCountChange = $left.meanSelected - $right.meanSelected
        meanImprovementPct = 100.0 * ($right.mean - $left.mean) / $right.mean
        sdImprovementPct = 100.0 * ($right.sd - $left.sd) / $right.sd
        q95ImprovementPct = 100.0 * ($right.q95 - $left.q95) / $right.q95
        cvar95ImprovementPct = 100.0 * ($right.cvar95 - $left.cvar95) / $right.cvar95
    }
}

$summary | Export-Csv -NoTypeInformation -Encoding utf8 (Join-Path $Root 'matched_method_summary.csv')
$paired | Export-Csv -NoTypeInformation -Encoding utf8 (Join-Path $Root 'matched_paired_summary.csv')
$detail | Export-Csv -NoTypeInformation -Encoding utf8 (Join-Path $Root 'matched_paired_detail.csv')
$comparisons | Export-Csv -NoTypeInformation -Encoding utf8 (Join-Path $Root 'mechanism_decomposition.csv')
@(
    'status=PASSED'
    'coverage=1.0'
    'mqcScale=1.0'
    'hRule=min eligible r_ij'
    'methods=RCSAA,CSAA_MATCHED_RCSAA_COUNT'
    'rows=102'
    'pairedTrials=51'
    'allCertified=true'
    'sameSelectedCount=51/51'
    "maxCostDecompositionError=$maxDecompositionError"
    'selectionSource=legacy per-trial k/C_h/lambda; mechanism diagnostic only'
) | Set-Content -Encoding utf8 (Join-Path $Root 'matched_validation.txt')

$summary | Format-Table -AutoSize
$comparisons | Format-Table -AutoSize
$paired | Format-List
