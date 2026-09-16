param(
    [string]$OutputRoot = "analysis/TRB_reviewer_revision/332_olist_v2_knn_saa_baseline_20260815",
    [string]$WeeklyCsv = ""
)

$ErrorActionPreference = 'Stop'
$software = ([string][char]0x8F6F) + ([string][char]0x4EF6)
$softwareRoot = "D:/$software"
$java = "$softwareRoot/Java/jdk_22/bin/java.exe"
$classPath = "bin;$softwareRoot/cplex/ILOG/CPLEX_Studio2211/cplex/lib/cplex.jar;$softwareRoot/Mosek/11.0/tools/platform/win64x86/bin/mosek.jar"
$env:PATH = "$softwareRoot/cplex/ILOG/CPLEX_Studio2211/cplex/bin/x64_win64;$softwareRoot/Mosek/11.0/tools/platform/win64x86/bin;" + $env:PATH
$gate = 'Test.analysis.synthetic.TRBReviewerOlistDynamicDgpV2Gate'
$culture = [Globalization.CultureInfo]::InvariantCulture
$neighborGrid = @(5, 10, 20, 30, 50)
$calibrationMarkets = @(90, 91, 92)
$evaluationMarkets = @(0, 1, 2)

if ([string]::IsNullOrWhiteSpace($WeeklyCsv)) {
    $candidates = @(Get-ChildItem -LiteralPath '.' -Filter '*.csv' -Recurse |
        Where-Object { $_.Name -eq '巴西五大区23OD_周度宽表.csv' } |
        Sort-Object { $_.FullName.Length })
    if ($candidates.Count -eq 0) { throw 'Cannot locate the 23OD weekly input under the workspace.' }
    $WeeklyCsv = $candidates[0].FullName
}

function Invoke-Gate(
    [string]$root,
    [int]$paths,
    [int]$oosDraws,
    [int]$marketSeed,
    [long]$demandSeedBase,
    [string]$methods,
    [int]$knnNeighbors
) {
    $result = Join-Path $root 'query_results.csv'
    if (Test-Path -LiteralPath $result) {
        $rows = @(Import-Csv -LiteralPath $result)
        $expected = $paths * ($methods.Split(',').Count)
        if ($rows.Count -ne $expected -or @($rows | Where-Object certifiedOptimal -ne 'true').Count -ne 0) {
            throw "Existing result is incomplete or uncertified: $root"
        }
        return
    }
    if (Test-Path -LiteralPath $root) {
        $items = @(Get-ChildItem -LiteralPath $root -Force)
        if ($items.Count -gt 0) { throw "Nonempty partial output requires manual audit: $root" }
    }
    & $java -cp $classPath $gate $WeeklyCsv $root 23 30 50 $paths $oosDraws 0.5 `
        $marketSeed lognormal 0.345 1.0 $demandSeedBase 0.5 1.0 $methods 0.0 0.0 exponential $knnNeighbors
    if ($LASTEXITCODE -ne 0) { throw "Gate failed with exit code ${LASTEXITCODE}: $root" }
}

function Get-Mean([double[]]$values) {
    if ($values.Count -eq 0) { throw 'Cannot summarize an empty sample.' }
    return ($values | Measure-Object -Average).Average
}

function Get-PopulationSd([double[]]$values, [double]$mean) {
    $sumSquares = 0.0
    foreach ($value in $values) { $sumSquares += ($value - $mean) * ($value - $mean) }
    return [Math]::Sqrt($sumSquares / $values.Count)
}

function Get-Quantile([double[]]$sortedValues, [double]$probability) {
    if ($sortedValues.Count -eq 1) { return $sortedValues[0] }
    $position = $probability * ($sortedValues.Count - 1)
    $lower = [int][Math]::Floor($position)
    $upper = [int][Math]::Ceiling($position)
    if ($lower -eq $upper) { return $sortedValues[$lower] }
    $fraction = $position - $lower
    return $sortedValues[$lower] * (1.0 - $fraction) + $sortedValues[$upper] * $fraction
}

function Get-TailMean([double[]]$sortedValues, [double]$threshold) {
    return (@($sortedValues | Where-Object { $_ -ge $threshold }) |
        Measure-Object -Average).Average
}

New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null

$calibrationRows = @()
foreach ($neighbors in $neighborGrid) {
    foreach ($market in $calibrationMarkets) {
        $root = Join-Path $OutputRoot "calibration/K_$neighbors/market_$market"
        Invoke-Gate $root 3 200 $market (900000L + 10000L * ($market - 90)) 'KNN_SAA' $neighbors
        foreach ($row in Import-Csv -LiteralPath (Join-Path $root 'query_results.csv')) {
            $calibrationRows += [pscustomobject]@{
                knnNeighbors = $neighbors
                marketSeed = $market
                pathIndex = [int]$row.pathIndex
                demandSeed = [long]$row.demandSeed
                meanOosCost = [double]::Parse($row.meanOosCost, $culture)
                certifiedOptimal = $row.certifiedOptimal
                relativeGap = $row.relativeGap
            }
        }
    }
}
$calibrationRows | Export-Csv -NoTypeInformation -Encoding utf8 `
    -LiteralPath (Join-Path $OutputRoot 'calibration_query_results.csv')
$candidateSummary = @($calibrationRows | Group-Object knnNeighbors | ForEach-Object {
    [pscustomobject]@{
        knnNeighbors = [int]$_.Name
        queries = $_.Count
        meanCalibrationOosCost = ($_.Group | Measure-Object meanOosCost -Average).Average
        maxRelativeGap = ($_.Group | ForEach-Object {
            [double]::Parse($_.relativeGap, $culture)
        } | Measure-Object -Maximum).Maximum
    }
} | Sort-Object meanCalibrationOosCost, knnNeighbors)
$candidateSummary | Export-Csv -NoTypeInformation -Encoding utf8 `
    -LiteralPath (Join-Path $OutputRoot 'knn_candidate_summary.csv')
$selectedNeighbors = [int]$candidateSummary[0].knnNeighbors

[pscustomobject]@{
    selectionCriterion = 'minimum mean conditional OOS cost on independent calibration markets'
    neighborGrid = ($neighborGrid -join ',')
    selectedKnnNeighbors = $selectedNeighbors
    calibrationMarketSeeds = ($calibrationMarkets -join ',')
    calibrationPathsPerMarket = 3
    calibrationOosDrawsPerPath = 200
    evaluationMarketSeeds = ($evaluationMarkets -join ',')
    evaluationPathsPerMarket = 10
    evaluationOosDrawsPerPath = 500
    J = 23
    I = 30
    S = 50
    observedDemandLags = 3
    standardization = 'training-only z-score'
    distance = 'Euclidean'
    tieBreak = 'training index'
    coverage = 0.5
    capacityNormalized = $true
    hRule = 'min eligible r_ij'
    mqcScale = 1.0
} | Export-Csv -NoTypeInformation -Encoding utf8 `
    -LiteralPath (Join-Path $OutputRoot 'selected_parameters_and_protocol.csv')

$evaluationRows = @()
$pooledCosts = @{
    SAA = [Collections.Generic.List[double]]::new()
    CSAA = [Collections.Generic.List[double]]::new()
    KNN_SAA = [Collections.Generic.List[double]]::new()
}
foreach ($market in $evaluationMarkets) {
    $root = Join-Path $OutputRoot "evaluation/market_$market"
    Invoke-Gate $root 10 500 $market (40000L + 10000L * $market) `
        'SAA,CSAA,KNN_SAA' $selectedNeighbors
    foreach ($row in Import-Csv -LiteralPath (Join-Path $root 'query_results.csv')) {
        $row | Add-Member -NotePropertyName marketSeed -NotePropertyValue $market
        $evaluationRows += $row
        $drawFile = Join-Path $root ("path_{0:D3}/{1}/oos_costs.csv" -f `
            [int]$row.pathIndex, $row.method)
        foreach ($draw in Import-Csv -LiteralPath $drawFile) {
            [void]$pooledCosts[$row.method].Add(
                [double]::Parse($draw.totalCost, $culture))
        }
    }
}
$evaluationRows | Export-Csv -NoTypeInformation -Encoding utf8 `
    -LiteralPath (Join-Path $OutputRoot 'evaluation_query_results.csv')

$methodSummary = @($evaluationRows | Group-Object method | ForEach-Object {
    $method = $_.Name
    [double[]]$costs = @($pooledCosts[$method] | Sort-Object)
    $pooledMean = Get-Mean $costs
    $pooledQ95 = Get-Quantile $costs 0.95
    [pscustomobject]@{
        method = $method
        queries = $_.Count
        pooledDraws = $costs.Count
        pooledMean = $pooledMean
        pooledSd = Get-PopulationSd $costs $pooledMean
        pooledQ95 = $pooledQ95
        pooledCVaR95 = Get-TailMean $costs $pooledQ95
        meanConditionalSd = ($_.Group | ForEach-Object {
            [double]::Parse($_.sdOosCost, $culture)
        } | Measure-Object -Average).Average
        meanConditionalQ95 = ($_.Group | ForEach-Object {
            [double]::Parse($_.q95OosCost, $culture)
        } | Measure-Object -Average).Average
        meanConditionalCVaR95 = ($_.Group | ForEach-Object {
            [double]::Parse($_.cvar95OosCost, $culture)
        } | Measure-Object -Average).Average
        certifiedQueries = @($_.Group | Where-Object certifiedOptimal -eq 'true').Count
    }
})
$methodSummary | Sort-Object method | Export-Csv -NoTypeInformation -Encoding utf8 `
    -LiteralPath (Join-Path $OutputRoot 'evaluation_method_summary.csv')

$pairedRows = @()
foreach ($group in $evaluationRows | Group-Object marketSeed, pathIndex) {
    $byMethod = @{}
    foreach ($row in $group.Group) { $byMethod[$row.method] = $row }
    foreach ($baseline in @('SAA', 'CSAA')) {
        $method = $byMethod['KNN_SAA']
        $base = $byMethod[$baseline]
        if ($null -eq $method -or $null -eq $base) {
            throw "Missing paired method in query $($group.Name)."
        }
        $pairedRows += [pscustomobject]@{
            marketSeed = $method.marketSeed
            pathIndex = $method.pathIndex
            method = 'KNN_SAA'
            baseline = $baseline
            meanImprovementPct = 100.0 * `
                ([double]$base.meanOosCost - [double]$method.meanOosCost) / [double]$base.meanOosCost
            sdImprovementPct = 100.0 * `
                ([double]$base.sdOosCost - [double]$method.sdOosCost) / [double]$base.sdOosCost
            q95ImprovementPct = 100.0 * `
                ([double]$base.q95OosCost - [double]$method.q95OosCost) / [double]$base.q95OosCost
            cvar95ImprovementPct = 100.0 * `
                ([double]$base.cvar95OosCost - [double]$method.cvar95OosCost) / [double]$base.cvar95OosCost
            sameDecision = ($base.yBinary -eq $method.yBinary)
        }
    }
}
$pairedRows | Export-Csv -NoTypeInformation -Encoding utf8 `
    -LiteralPath (Join-Path $OutputRoot 'evaluation_paired_improvements.csv')

Write-Output "Selected K_NN=$selectedNeighbors"
