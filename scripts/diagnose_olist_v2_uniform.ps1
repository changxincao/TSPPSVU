param(
    [string]$OutputRoot = "analysis/TRB_reviewer_revision/265_olist_v2_uniform_mechanism_diagnostic_20260813"
)

$ErrorActionPreference = "Stop"
$Invariant = [System.Globalization.CultureInfo]::InvariantCulture

$configs = @(
    [pscustomobject]@{ Distribution = "LOGNORMAL"; Bandwidth = 1.0; Market = 0; Root = "analysis/TRB_reviewer_revision/248_olist_v2_j23_i30_s50_rho080_diag_market0_20260813" },
    [pscustomobject]@{ Distribution = "LOGNORMAL"; Bandwidth = 1.0; Market = 1; Root = "analysis/TRB_reviewer_revision/249_olist_v2_j23_i30_s50_rho080_holdout_market1_20260813" },
    [pscustomobject]@{ Distribution = "LOGNORMAL"; Bandwidth = 1.0; Market = 2; Root = "analysis/TRB_reviewer_revision/252_olist_v2_j23_i30_s50_rho080_holdout_market2_20260813" },
    [pscustomobject]@{ Distribution = "UNIFORM";   Bandwidth = 1.0; Market = 0; Root = "analysis/TRB_reviewer_revision/259_olist_v2_j23_i30_s50_rho080_uniform_market0_20260813" },
    [pscustomobject]@{ Distribution = "UNIFORM";   Bandwidth = 1.0; Market = 1; Root = "analysis/TRB_reviewer_revision/260_olist_v2_j23_i30_s50_rho080_uniform_market1_20260813" },
    [pscustomobject]@{ Distribution = "UNIFORM";   Bandwidth = 1.0; Market = 2; Root = "analysis/TRB_reviewer_revision/261_olist_v2_j23_i30_s50_rho080_uniform_market2_20260813" },
    [pscustomobject]@{ Distribution = "UNIFORM";   Bandwidth = 2.0; Market = 0; Root = "analysis/TRB_reviewer_revision/262_olist_v2_j23_i30_s50_rho080_uniform_ch200_market0_diag_20260813" },
    [pscustomobject]@{ Distribution = "UNIFORM";   Bandwidth = 2.0; Market = 1; Root = "analysis/TRB_reviewer_revision/263_olist_v2_j23_i30_s50_rho080_uniform_ch200_market1_diag_20260813" },
    [pscustomobject]@{ Distribution = "UNIFORM";   Bandwidth = 2.0; Market = 2; Root = "analysis/TRB_reviewer_revision/264_olist_v2_j23_i30_s50_rho080_uniform_ch200_market2_diag_20260813" }
)

function Get-Mean([double[]]$Values) {
    return ($Values | Measure-Object -Average).Average
}

function Get-PopulationVariance([double[]]$Values) {
    $mean = Get-Mean $Values
    $sum = 0.0
    foreach ($value in $Values) { $sum += ($value - $mean) * ($value - $mean) }
    return $sum / $Values.Count
}

function Get-Quantile([double[]]$Values, [double]$Probability) {
    $sorted = $Values | Sort-Object
    $position = $Probability * ($sorted.Count - 1)
    $lower = [Math]::Floor($position)
    $upper = [Math]::Ceiling($position)
    if ($lower -eq $upper) { return [double]$sorted[$lower] }
    $weight = $position - $lower
    return [double]$sorted[$lower] * (1.0 - $weight) + [double]$sorted[$upper] * $weight
}

function Get-Cvar95([double[]]$Values) {
    $sorted = $Values | Sort-Object
    $tailCount = [Math]::Max(1, [Math]::Ceiling(0.05 * $sorted.Count))
    return Get-Mean ([double[]]$sorted[($sorted.Count - $tailCount)..($sorted.Count - 1)])
}

function Get-Hamming([string]$Left, [string]$Right) {
    $a = $Left.Trim('[', ']').Split(',')
    $b = $Right.Trim('[', ']').Split(',')
    if ($a.Count -ne $b.Count) { throw "Decision vectors have different lengths." }
    $distance = 0
    for ($i = 0; $i -lt $a.Count; $i++) { if ($a[$i] -ne $b[$i]) { $distance++ } }
    return $distance
}

function Get-Improvement([double]$Baseline, [double]$Method) {
    return 100.0 * ($Baseline - $Method) / $Baseline
}

New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null

$queryRows = [System.Collections.Generic.List[object]]::new()
$pairRows = [System.Collections.Generic.List[object]]::new()
$drawCache = @{}
$maxQueryMetricError = 0.0
$maxCostDecompositionError = 0.0
$maxRelativeGap = 0.0

foreach ($config in $configs) {
    $summaryPath = Join-Path $config.Root "query_results.csv"
    $summary = Import-Csv -LiteralPath $summaryPath
    if ($summary.Count -ne 30) { throw "Expected 30 query-result rows in $summaryPath, found $($summary.Count)." }

    for ($pathIndex = 0; $pathIndex -lt 10; $pathIndex++) {
        $rowsByMethod = @{}
        foreach ($method in @("D", "SAA", "CSAA")) {
            $row = $summary | Where-Object { [int]$_.pathIndex -eq $pathIndex -and $_.method -eq $method }
            if (@($row).Count -ne 1) { throw "Expected one $method row for path $pathIndex in $summaryPath." }
            if ($row.certifiedOptimal -ne "true") { throw "Uncertified solve for $method path $pathIndex in $summaryPath." }
            $maxRelativeGap = [Math]::Max($maxRelativeGap, [double]$row.relativeGap)
            if ([double]$row.relativeGap -gt 1.0e-4) { throw "Relative gap exceeds 1e-4 for $method path $pathIndex in $summaryPath." }
            $rowsByMethod[$method] = $row

            $costPath = Join-Path $config.Root (("path_{0:D3}/{1}/oos_costs.csv" -f $pathIndex, $method))
            $draws = Import-Csv -LiteralPath $costPath
            if ($draws.Count -ne 500) { throw "Expected 500 OOS draws in $costPath, found $($draws.Count)." }
            $drawCache["$($config.Distribution)|$($config.Bandwidth)|$($config.Market)|$pathIndex|$method"] = $draws

            $totals = [double[]]($draws | ForEach-Object { [double]$_.totalCost })
            $mean = Get-Mean $totals
            $sd = [Math]::Sqrt((Get-PopulationVariance $totals))
            $q95 = Get-Quantile $totals 0.95
            $cvar = Get-Cvar95 $totals
            foreach ($comparison in @(
                [Math]::Abs($mean - [double]$row.meanOosCost),
                [Math]::Abs($sd - [double]$row.sdOosCost),
                [Math]::Abs($q95 - [double]$row.q95OosCost),
                [Math]::Abs($cvar - [double]$row.cvar95OosCost)
            )) { $maxQueryMetricError = [Math]::Max($maxQueryMetricError, $comparison) }

            foreach ($draw in $draws) {
                $decompositionError = [Math]::Abs([double]$draw.totalCost - ([double]$draw.transportCost + [double]$draw.spotCost + [double]$draw.penaltyCost))
                $maxCostDecompositionError = [Math]::Max($maxCostDecompositionError, $decompositionError)
            }

            $queryRows.Add([pscustomobject]@{
                distribution = $config.Distribution; bandwidth = $config.Bandwidth; market = $config.Market; pathIndex = $pathIndex; method = $method
                selectedCount = [int]$row.selectedCount; yBinary = $row.yBinary
                mean = $mean; sd = $sd; q95 = $q95; cvar95 = $cvar
                meanTransport = Get-Mean ([double[]]($draws | ForEach-Object { [double]$_.transportCost }))
                meanSpot = Get-Mean ([double[]]($draws | ForEach-Object { [double]$_.spotCost }))
                meanPenalty = Get-Mean ([double[]]($draws | ForEach-Object { [double]$_.penaltyCost }))
                withinVariance = Get-PopulationVariance $totals
            })
        }

        $saaDraws = $drawCache["$($config.Distribution)|$($config.Bandwidth)|$($config.Market)|$pathIndex|SAA"]
        $csaaDraws = $drawCache["$($config.Distribution)|$($config.Bandwidth)|$($config.Market)|$pathIndex|CSAA"]
        $paired = for ($drawId = 0; $drawId -lt 500; $drawId++) {
            $saa = $saaDraws[$drawId]
            $csaa = $csaaDraws[$drawId]
            if ([int]$saa.drawId -ne $drawId -or [int]$csaa.drawId -ne $drawId) { throw "Unpaired drawId in market $($config.Market), path $pathIndex." }
            [pscustomobject]@{
                drawId = $drawId
                saaTotal = [double]$saa.totalCost
                totalSaving = [double]$saa.totalCost - [double]$csaa.totalCost
                transportSaving = [double]$saa.transportCost - [double]$csaa.transportCost
                spotSaving = [double]$saa.spotCost - [double]$csaa.spotCost
                penaltySaving = [double]$saa.penaltyCost - [double]$csaa.penaltyCost
            }
        }
        $tail = @($paired | Sort-Object saaTotal -Descending | Select-Object -First 25)
        $saaRow = $rowsByMethod["SAA"]
        $csaaRow = $rowsByMethod["CSAA"]
        $pairRows.Add([pscustomobject]@{
            distribution = $config.Distribution; bandwidth = $config.Bandwidth; market = $config.Market; pathIndex = $pathIndex
            hammingDistance = Get-Hamming $saaRow.yBinary $csaaRow.yBinary
            sameDecision = [int]($saaRow.yBinary -eq $csaaRow.yBinary)
            selectedCountSaa = [int]$saaRow.selectedCount; selectedCountCsaa = [int]$csaaRow.selectedCount
            meanSaving = Get-Mean ([double[]]($paired | ForEach-Object { $_.totalSaving }))
            winRate = (@($paired | Where-Object { $_.totalSaving -gt 0 }).Count / 500.0)
            meanTransportSaving = Get-Mean ([double[]]($paired | ForEach-Object { $_.transportSaving }))
            meanSpotSaving = Get-Mean ([double[]]($paired | ForEach-Object { $_.spotSaving }))
            meanPenaltySaving = Get-Mean ([double[]]($paired | ForEach-Object { $_.penaltySaving }))
            saaTop5MeanSaving = Get-Mean ([double[]]($tail | ForEach-Object { $_.totalSaving }))
            saaTop5TransportSaving = Get-Mean ([double[]]($tail | ForEach-Object { $_.transportSaving }))
            saaTop5SpotSaving = Get-Mean ([double[]]($tail | ForEach-Object { $_.spotSaving }))
            saaTop5PenaltySaving = Get-Mean ([double[]]($tail | ForEach-Object { $_.penaltySaving }))
        })
    }
}

$marketMethodRows = [System.Collections.Generic.List[object]]::new()
$marketPairRows = [System.Collections.Generic.List[object]]::new()
$tailCompositionRows = [System.Collections.Generic.List[object]]::new()
foreach ($config in $configs) {
    $slice = @($queryRows | Where-Object { $_.distribution -eq $config.Distribution -and $_.bandwidth -eq $config.Bandwidth -and $_.market -eq $config.Market })
    foreach ($method in @("D", "SAA", "CSAA")) {
        $methodRows = @($slice | Where-Object { $_.method -eq $method })
        $allTotals = [System.Collections.Generic.List[double]]::new()
        $pooledDraws = [System.Collections.Generic.List[object]]::new()
        for ($pathIndex = 0; $pathIndex -lt 10; $pathIndex++) {
            foreach ($draw in $drawCache["$($config.Distribution)|$($config.Bandwidth)|$($config.Market)|$pathIndex|$method"]) {
                $allTotals.Add([double]$draw.totalCost)
                $pooledDraws.Add([pscustomobject]@{ pathIndex = $pathIndex; totalCost = [double]$draw.totalCost })
            }
        }
        $totals = $allTotals.ToArray()
        $pooledQ95 = Get-Quantile $totals 0.95
        $tailDraws = @($pooledDraws | Where-Object { $_.totalCost -ge $pooledQ95 })
        foreach ($pathIndex in 0..9) {
            $pathTail = @($tailDraws | Where-Object { $_.pathIndex -eq $pathIndex })
            $pathMean = ($methodRows | Where-Object { $_.pathIndex -eq $pathIndex }).mean
            $tailCompositionRows.Add([pscustomobject]@{
                distribution = $config.Distribution; bandwidth = $config.Bandwidth; market = $config.Market; method = $method
                pathIndex = $pathIndex; pathMeanCost = [double]$pathMean; pooledQ95 = $pooledQ95
                tailDrawCount = $pathTail.Count; tailShare = $pathTail.Count / [double]$tailDraws.Count
                tailMeanCost = if ($pathTail.Count -eq 0) { [double]::NaN } else { Get-Mean ([double[]]($pathTail | ForEach-Object { $_.totalCost })) }
            })
        }
        $withinVariance = Get-Mean ([double[]]($methodRows | ForEach-Object { [double]$_.withinVariance }))
        $betweenVariance = Get-PopulationVariance ([double[]]($methodRows | ForEach-Object { [double]$_.mean }))
        $totalVariance = Get-PopulationVariance $totals
        $marketMethodRows.Add([pscustomobject]@{
            distribution = $config.Distribution; bandwidth = $config.Bandwidth; market = $config.Market; method = $method; draws = $totals.Count
            mean = Get-Mean $totals; sd = [Math]::Sqrt($totalVariance); q95 = Get-Quantile $totals 0.95; cvar95 = Get-Cvar95 $totals
            meanSelectedCount = Get-Mean ([double[]]($methodRows | ForEach-Object { [double]$_.selectedCount }))
            meanTransport = Get-Mean ([double[]]($methodRows | ForEach-Object { [double]$_.meanTransport }))
            meanSpot = Get-Mean ([double[]]($methodRows | ForEach-Object { [double]$_.meanSpot }))
            meanPenalty = Get-Mean ([double[]]($methodRows | ForEach-Object { [double]$_.meanPenalty }))
            withinVariance = $withinVariance; betweenVariance = $betweenVariance; totalVariance = $totalVariance
            varianceIdentityError = [Math]::Abs($totalVariance - $withinVariance - $betweenVariance)
        })
    }

    $d = $marketMethodRows | Where-Object { $_.distribution -eq $config.Distribution -and $_.bandwidth -eq $config.Bandwidth -and $_.market -eq $config.Market -and $_.method -eq "D" }
    $saa = $marketMethodRows | Where-Object { $_.distribution -eq $config.Distribution -and $_.bandwidth -eq $config.Bandwidth -and $_.market -eq $config.Market -and $_.method -eq "SAA" }
    $csaa = $marketMethodRows | Where-Object { $_.distribution -eq $config.Distribution -and $_.bandwidth -eq $config.Bandwidth -and $_.market -eq $config.Market -and $_.method -eq "CSAA" }
    $pairs = @($pairRows | Where-Object { $_.distribution -eq $config.Distribution -and $_.bandwidth -eq $config.Bandwidth -and $_.market -eq $config.Market })
    $marketPairRows.Add([pscustomobject]@{
        distribution = $config.Distribution; bandwidth = $config.Bandwidth; market = $config.Market; queries = $pairs.Count; pairedDraws = 500 * $pairs.Count
        saaVsDMeanPct = Get-Improvement $d.mean $saa.mean; saaVsDSdPct = Get-Improvement $d.sd $saa.sd
        saaVsDQ95Pct = Get-Improvement $d.q95 $saa.q95; saaVsDCvar95Pct = Get-Improvement $d.cvar95 $saa.cvar95
        csaaVsSaaMeanPct = Get-Improvement $saa.mean $csaa.mean; csaaVsSaaSdPct = Get-Improvement $saa.sd $csaa.sd
        csaaVsSaaQ95Pct = Get-Improvement $saa.q95 $csaa.q95; csaaVsSaaCvar95Pct = Get-Improvement $saa.cvar95 $csaa.cvar95
        meanHammingDistance = Get-Mean ([double[]]($pairs | ForEach-Object { [double]$_.hammingDistance }))
        sameDecisionQueries = ($pairs | Measure-Object sameDecision -Sum).Sum
        meanSelectedCountDelta = Get-Mean ([double[]]($pairs | ForEach-Object { [double]$_.selectedCountCsaa - [double]$_.selectedCountSaa }))
        csaaWinRate = Get-Mean ([double[]]($pairs | ForEach-Object { [double]$_.winRate }))
        meanSaving = Get-Mean ([double[]]($pairs | ForEach-Object { [double]$_.meanSaving }))
        meanTransportSaving = Get-Mean ([double[]]($pairs | ForEach-Object { [double]$_.meanTransportSaving }))
        meanSpotSaving = Get-Mean ([double[]]($pairs | ForEach-Object { [double]$_.meanSpotSaving }))
        meanPenaltySaving = Get-Mean ([double[]]($pairs | ForEach-Object { [double]$_.meanPenaltySaving }))
        saaTop5MeanSaving = Get-Mean ([double[]]($pairs | ForEach-Object { [double]$_.saaTop5MeanSaving }))
        saaTop5TransportSaving = Get-Mean ([double[]]($pairs | ForEach-Object { [double]$_.saaTop5TransportSaving }))
        saaTop5SpotSaving = Get-Mean ([double[]]($pairs | ForEach-Object { [double]$_.saaTop5SpotSaving }))
        saaTop5PenaltySaving = Get-Mean ([double[]]($pairs | ForEach-Object { [double]$_.saaTop5PenaltySaving }))
        saaWithinVariance = $saa.withinVariance; csaaWithinVariance = $csaa.withinVariance
        saaBetweenVariance = $saa.betweenVariance; csaaBetweenVariance = $csaa.betweenVariance
    })
}

$queryRows | Export-Csv -LiteralPath (Join-Path $OutputRoot "query_method_diagnostics.csv") -NoTypeInformation -Encoding utf8
$pairRows | Export-Csv -LiteralPath (Join-Path $OutputRoot "query_pair_diagnostics.csv") -NoTypeInformation -Encoding utf8
$marketMethodRows | Export-Csv -LiteralPath (Join-Path $OutputRoot "market_method_summary.csv") -NoTypeInformation -Encoding utf8
$marketPairRows | Export-Csv -LiteralPath (Join-Path $OutputRoot "market_pair_summary.csv") -NoTypeInformation -Encoding utf8
$tailCompositionRows | Export-Csv -LiteralPath (Join-Path $OutputRoot "pooled_tail_composition.csv") -NoTypeInformation -Encoding utf8

$validation = @(
    "status=PASSED",
    "configs=$($configs.Count)",
    "query_method_rows=$($queryRows.Count)",
    "query_pair_rows=$($pairRows.Count)",
    "market_method_rows=$($marketMethodRows.Count)",
    "market_pair_rows=$($marketPairRows.Count)",
    "tail_composition_rows=$($tailCompositionRows.Count)",
    "oos_draws_reviewed=$($configs.Count * 10 * 3 * 500)",
    "max_query_metric_error=$($maxQueryMetricError.ToString('G17', $Invariant))",
    "max_cost_decomposition_error=$($maxCostDecompositionError.ToString('G17', $Invariant))",
    "max_relative_gap=$($maxRelativeGap.ToString('G17', $Invariant))",
    "variance_identity_max_error=$(($marketMethodRows | Measure-Object varianceIdentityError -Maximum).Maximum)"
)
$validation | Set-Content -LiteralPath (Join-Path $OutputRoot "validation.txt") -Encoding utf8

$marketPairRows | Format-Table distribution, bandwidth, market, saaVsDMeanPct, saaVsDSdPct, csaaVsSaaMeanPct, csaaVsSaaSdPct, meanHammingDistance, csaaWinRate, meanSaving, saaTop5MeanSaving -AutoSize
Get-Content -LiteralPath (Join-Path $OutputRoot "validation.txt")
