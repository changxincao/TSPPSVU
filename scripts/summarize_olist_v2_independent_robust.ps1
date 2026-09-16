param(
    [string]$OutputRoot = "analysis/TRB_reviewer_revision/297_olist_v2_independent_robust_summary_20260814"
)

$ErrorActionPreference = 'Stop'
$baselineRoots = @(
    'analysis/TRB_reviewer_revision/247_olist_v2_j23_i30_s50_rho050_diag_market0_20260813',
    'analysis/TRB_reviewer_revision/250_olist_v2_j23_i30_s50_rho050_holdout_market1_20260813',
    'analysis/TRB_reviewer_revision/251_olist_v2_j23_i30_s50_rho050_holdout_market2_20260813'
)
$robustRoot = 'analysis/TRB_reviewer_revision/295_olist_v2_independent_calibration_robust_20260814'
$methods = @('D','SAA','CSAA','DRO')

function Mean([double[]]$x) { return ($x | Measure-Object -Average).Average }
function Quantile([double[]]$x, [double]$p) {
    $s = @($x | Sort-Object)
    $position = $p * ($s.Count - 1)
    $lower = [Math]::Floor($position)
    $upper = [Math]::Ceiling($position)
    if ($lower -eq $upper) { return [double]$s[$lower] }
    $weight = $position - $lower
    return [double]$s[$lower] * (1.0 - $weight) + [double]$s[$upper] * $weight
}
function Metrics([double[]]$x) {
    $mean = Mean $x
    $sd = [Math]::Sqrt((Mean ([double[]]($x | ForEach-Object {($_-$mean)*($_-$mean)}))))
    $q95 = Quantile $x 0.95
    $tail = [double[]]($x | Where-Object {$_ -ge $q95})
    return @{mean=$mean; sd=$sd; q95=$q95; cvar95=(Mean $tail)}
}
function Improvement([double]$base, [double]$method) { return 100.0*($base-$method)/$base }

New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null
$queryRows = [System.Collections.Generic.List[object]]::new()
$drawRows = [System.Collections.Generic.List[object]]::new()
$maxGap = 0.0
$maxDecomposition = 0.0
for ($market=0; $market -lt 3; $market++) {
    $baseQueries = @(Import-Csv -LiteralPath (Join-Path $baselineRoots[$market] 'query_results.csv'))
    $droRoot = Join-Path $robustRoot ("evaluation/dro/market_{0}" -f $market)
    $droQueries = @(Import-Csv -LiteralPath (Join-Path $droRoot 'query_results.csv'))
    foreach ($method in $methods) {
        $sourceRoot = if ($method -eq 'DRO') {$droRoot} else {$baselineRoots[$market]}
        $sourceQueries = if ($method -eq 'DRO') {$droQueries} else {$baseQueries}
        for ($path=0; $path -lt 10; $path++) {
            $row = @($sourceQueries | Where-Object {$_.method -eq $method -and [int]$_.pathIndex -eq $path})
            if ($row.Count -ne 1) { throw "Expected one query: market=$market path=$path method=$method" }
            if ($row[0].certifiedOptimal -ne 'true') { throw "Uncertified query: market=$market path=$path method=$method" }
            $gap = [double]$row[0].relativeGap
            if ($gap -gt 1e-4) { throw "Gap exceeds tolerance: market=$market path=$path method=$method" }
            $maxGap = [Math]::Max($maxGap,$gap)
            $expectedSeed = 40000L + 10000L*$market + $path
            if ([long]$row[0].demandSeed -ne $expectedSeed) { throw "Demand seed mismatch" }
            $queryRows.Add([pscustomobject]@{
                market=$market; path=$path; demandSeed=$expectedSeed; method=$method
                mean=[double]$row[0].meanOosCost; sd=[double]$row[0].sdOosCost
                q95=[double]$row[0].q95OosCost; cvar95=[double]$row[0].cvar95OosCost
                transport=[double]$row[0].meanTransportCost; spot=[double]$row[0].meanSpotCost
                penalty=[double]$row[0].meanPenaltyCost; selectedCount=[int]$row[0].selectedCount
                relativeGap=$gap; optimizerTimeSec=[double]$row[0].optimizerTimeSec
            })
            $drawFile = Join-Path $sourceRoot (("path_{0:D3}/{1}/oos_costs.csv") -f $path,$method)
            $draws = @(Import-Csv -LiteralPath $drawFile)
            if ($draws.Count -ne 500) { throw "Expected 500 draws: $drawFile" }
            $ids = @($draws | ForEach-Object {[int]$_.drawId} | Sort-Object -Unique)
            if ($ids.Count -ne 500 -or $ids[0] -ne 0 -or $ids[-1] -ne 499) { throw "Draw IDs mismatch" }
            foreach ($draw in $draws) {
                $total=[double]$draw.totalCost; $transport=[double]$draw.transportCost
                $spot=[double]$draw.spotCost; $penalty=[double]$draw.penaltyCost
                $maxDecomposition=[Math]::Max($maxDecomposition,[Math]::Abs($total-$transport-$spot-$penalty))
                $drawRows.Add([pscustomobject]@{
                    market=$market; path=$path; demandSeed=$expectedSeed; method=$method
                    drawId=[int]$draw.drawId; totalCost=$total; transportCost=$transport
                    spotCost=$spot; penaltyCost=$penalty
                    mqcShortfallQuantity=[double]$draw.mqcShortfallQuantity
                })
            }
        }
    }
}
if ($maxDecomposition -gt 1e-6) { throw "Cost decomposition failed: $maxDecomposition" }
$queryRows | Export-Csv -NoTypeInformation -Encoding utf8 -LiteralPath (Join-Path $OutputRoot 'combined_query_results.csv')
$drawRows | Export-Csv -NoTypeInformation -Encoding utf8 -LiteralPath (Join-Path $OutputRoot 'combined_oos_costs.csv')

$pooled = @()
foreach ($method in $methods) {
    $g=@($drawRows|Where-Object method -eq $method)
    $metric=Metrics ([double[]]($g|ForEach-Object totalCost))
    $pooled += [pscustomobject]@{
        method=$method; queries=30; draws=$g.Count; mean=$metric.mean; sd=$metric.sd
        q95=$metric.q95; cvar95=$metric.cvar95
        transport=Mean ([double[]]($g|ForEach-Object transportCost))
        spot=Mean ([double[]]($g|ForEach-Object spotCost))
        penalty=Mean ([double[]]($g|ForEach-Object penaltyCost))
        mqcShortfall=Mean ([double[]]($g|ForEach-Object mqcShortfallQuantity))
        meanSelectedCount=Mean ([double[]]($queryRows|Where-Object method -eq $method|ForEach-Object selectedCount))
        meanOptimizerTimeSec=Mean ([double[]]($queryRows|Where-Object method -eq $method|ForEach-Object optimizerTimeSec))
    }
}
$pooled | Export-Csv -NoTypeInformation -Encoding utf8 -LiteralPath (Join-Path $OutputRoot 'pooled_method_summary.csv')
$pairs=@()
foreach ($pair in @(@('SAA','D'),@('CSAA','SAA'),@('DRO','CSAA'))) {
    $method=$pair[0]; $baseline=$pair[1]
    foreach ($metric in @('mean','sd','q95','cvar95')) {
        $improvements=@()
        for($market=0;$market -lt 3;$market++) { for($path=0;$path -lt 10;$path++) {
            $m=@($queryRows|Where-Object {$_.market -eq $market -and $_.path -eq $path -and $_.method -eq $method})[0]
            $b=@($queryRows|Where-Object {$_.market -eq $market -and $_.path -eq $path -and $_.method -eq $baseline})[0]
            $improvements += Improvement ([double]$b.$metric) ([double]$m.$metric)
        }}
        $pairs += [pscustomobject]@{
            comparison="$method-$baseline"; metric=$metric; pairedQueries=30
            meanImprovementPct=Mean ([double[]]$improvements)
            positiveQueries=@($improvements|Where-Object {$_ -gt 0.0}).Count
        }
    }
}
$pairs | Export-Csv -NoTypeInformation -Encoding utf8 -LiteralPath (Join-Path $OutputRoot 'paired_conditional_improvements.csv')
@(
    'status=PASSED',
    'methods=D,SAA,CSAA,DRO',
    'markets=3',
    'queriesPerMethod=30',
    'drawsPerQuery=500',
    'totalOosRows=60000',
    "maxRelativeGap=$maxGap",
    "maxCostDecompositionError=$maxDecomposition",
    'C_h=1.0 selected on procurement seeds 90,91,92 only',
    'lambda=1.0 selected on procurement seeds 90,91,92 only',
    'evaluation procurement seeds=0,1,2',
    'evaluation demand seed bases=40000,50000,60000'
) | Set-Content -Encoding utf8 -LiteralPath (Join-Path $OutputRoot 'validation.txt')
