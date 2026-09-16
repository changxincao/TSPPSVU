param(
    [string]$Output = 'analysis/TRB_reviewer_revision/228_r7_h100_s50_fixed_market_audit_20260813',
    [string]$RootTemplate = 'analysis/TRB_reviewer_revision/227_r7_h100_s50_market{0}_demand{1}_20260813',
    [string]$Cell = 'coverage_100_mqc_100',
    [string]$Protocol = 'I20, 100% coverage, natural capacity',
    [int[]]$Markets = @(0, 1, 2),
    [int]$DemandPaths = 10
)

$ErrorActionPreference = 'Stop'

function Get-Quantile([double[]]$Values, [double]$Probability) {
    [array]::Sort($Values)
    $position = ($Values.Length - 1) * $Probability
    $lower = [math]::Floor($position)
    $upper = [math]::Ceiling($position)
    if ($lower -eq $upper) { return $Values[$lower] }
    return $Values[$lower] + ($position - $lower) * ($Values[$upper] - $Values[$lower])
}

function Get-MethodRow([int]$Market, [string]$Method) {
    $costs = [System.Collections.Generic.List[double]]::new()
    $certified = 0
    $maxGap = 0.0
    for ($demand = 0; $demand -lt $DemandPaths; $demand++) {
        $root = $RootTemplate.Replace('{0}', [string]$Market).Replace('{1}', [string]$demand)
        $query = @(Import-Csv -LiteralPath "$root/query_results.csv" |
            Where-Object method -eq $Method)
        if ($query.Count -ne 1) {
            throw "Expected one $Method row for market=$Market demand=$demand; found $($query.Count)."
        }
        if ($query[0].certifiedOptimal -eq 'true') { $certified++ }
        $maxGap = [math]::Max($maxGap, [double]$query[0].relativeGap)
        $oos = Import-Csv -LiteralPath "$root/$Cell/train_01/query_001/$Method/oos_costs.csv"
        if (@($oos).Count -ne 200) {
            throw "Expected 200 OOS draws for market=$Market demand=$demand method=$Method."
        }
        foreach ($row in $oos) { $costs.Add([double]$row.totalCost) }
    }
    [double[]]$values = $costs.ToArray()
    $mean = ($values | Measure-Object -Average).Average
    $sumSquares = 0.0
    foreach ($value in $values) { $sumSquares += ($value - $mean) * ($value - $mean) }
    $q95 = Get-Quantile $values 0.95
    [pscustomobject]@{
        Market = $Market
        Method = $Method
        DemandPaths = $DemandPaths
        Draws = $values.Length
        Mean = $mean
        PopulationSD = [math]::Sqrt($sumSquares / $values.Length)
        Q95 = $q95
        CVaR95 = ($values | Where-Object { $_ -ge $q95 } | Measure-Object -Average).Average
        Certified = $certified
        MaxGap = $maxGap
    }
}

New-Item -ItemType Directory -Path $Output -ErrorAction Stop | Out-Null
$rows = @()
foreach ($market in $Markets) {
    foreach ($method in 'D', 'SAA', 'CSAA') {
        $rows += Get-MethodRow $market $method
    }
}
$rows | Export-Csv -LiteralPath "$Output/fixed_market_metrics.csv" -NoTypeInformation -Encoding UTF8

$improvements = @()
foreach ($market in $Markets) {
    $group = $rows | Where-Object Market -eq $market
    foreach ($pair in @(@('SAA', 'D'), @('CSAA', 'SAA'))) {
        $method = $group | Where-Object Method -eq $pair[0]
        $baseline = $group | Where-Object Method -eq $pair[1]
        $improvements += [pscustomobject]@{
            Market = $market
            Method = $pair[0]
            Baseline = $pair[1]
            MeanImprovementPct = 100 * ($baseline.Mean - $method.Mean) / $baseline.Mean
            SDImprovementPct = 100 * ($baseline.PopulationSD - $method.PopulationSD) / $baseline.PopulationSD
            Q95ImprovementPct = 100 * ($baseline.Q95 - $method.Q95) / $baseline.Q95
            CVaR95ImprovementPct = 100 * ($baseline.CVaR95 - $method.CVaR95) / $baseline.CVaR95
        }
    }
}
$improvements | Export-Csv -LiteralPath "$Output/fixed_market_improvements.csv" -NoTypeInformation -Encoding UTF8

$strictRows = @($improvements | Where-Object {
    $_.MeanImprovementPct -gt 0 -and $_.SDImprovementPct -gt 0 -and
    $_.Q95ImprovementPct -gt 0 -and $_.CVaR95ImprovementPct -gt 0
})
$allCertified = @($rows | Where-Object { $_.Certified -ne $_.DemandPaths }).Count -eq 0
@(
    $(if ($strictRows.Count -eq 2 * $Markets.Count -and $allCertified) { 'status=PASSED' } else { 'status=FAILED' })
    "protocol=$($Markets.Count) fixed procurement markets x $DemandPaths unseen demand paths x 200 paired OOS draws"
    "configuration=$Protocol"
    "procurementSeeds=$($Markets -join ',')"
    "demandSeedOffsets=800-$([int](799 + $DemandPaths))"
    'methods=D,SAA,CSAA'
    'metric=unconditional OOS distribution within each fixed procurement market'
    "strictImprovementRows=$($strictRows.Count)/$([int](2 * $Markets.Count))"
    "allCertified=$allCertified"
) | Set-Content -LiteralPath "$Output/validation.txt" -Encoding UTF8

$rows | Format-Table -AutoSize
$improvements | Format-Table -AutoSize
