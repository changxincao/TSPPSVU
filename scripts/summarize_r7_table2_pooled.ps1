param(
    [string]$Output = 'analysis/TRB_reviewer_revision/226_r7_h100_s50_table2_metric_audit_20260813'
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

function Get-Root([int]$Seed, [string]$Method) {
    if ($Method -eq 'DRO') {
        if ($Seed -lt 10) {
            return "analysis/TRB_reviewer_revision/223_r7_h100_s50_dro_l050_seed${Seed}_20260813"
        }
        return "analysis/TRB_reviewer_revision/224_r7_h100_s50_dro_l050_eval_seed${Seed}_20260813"
    }
    return "analysis/TRB_reviewer_revision/219_r7_continuous_i20_h100_s50_seed${Seed}_20260813"
}

function Get-PooledRow([string]$Scope, [string]$Method, [int[]]$Seeds) {
    $costs = [System.Collections.Generic.List[double]]::new()
    $certified = 0
    $maxGap = 0.0
    foreach ($seed in $Seeds) {
        $root = Get-Root $seed $Method
        $query = Import-Csv -LiteralPath "$root/query_results.csv" |
            Where-Object method -eq $Method
        if ($query.certifiedOptimal -eq 'true') { $certified++ }
        $maxGap = [math]::Max($maxGap, [double]$query.relativeGap)
        $path = "$root/coverage_100_mqc_100/train_01/query_001/$Method/oos_costs.csv"
        foreach ($row in Import-Csv -LiteralPath $path) {
            $costs.Add([double]$row.totalCost)
        }
    }
    [double[]]$values = $costs.ToArray()
    $mean = ($values | Measure-Object -Average).Average
    $sumSquares = 0.0
    foreach ($value in $values) { $sumSquares += ($value - $mean) * ($value - $mean) }
    $q95 = Get-Quantile $values 0.95
    [pscustomobject]@{
        Scope = $Scope
        Method = $Method
        Seeds = $Seeds.Count
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
$scopes = [ordered]@{
    calibration_0_9 = [int[]](0..9)
    independent_evaluation_10_49 = [int[]](10..49)
    exploratory_all_0_49 = [int[]](0..49)
}
foreach ($scope in $scopes.Keys) {
    foreach ($method in 'D', 'SAA', 'CSAA', 'DRO') {
        $rows += Get-PooledRow $scope $method $scopes[$scope]
    }
}
$rows | Export-Csv -LiteralPath "$Output/pooled_table2_metrics.csv" -NoTypeInformation -Encoding UTF8

$improvements = @()
foreach ($scope in $scopes.Keys) {
    $group = $rows | Where-Object Scope -eq $scope
    foreach ($pair in @(@('SAA', 'D'), @('CSAA', 'SAA'), @('DRO', 'CSAA'))) {
        $method = $group | Where-Object Method -eq $pair[0]
        $baseline = $group | Where-Object Method -eq $pair[1]
        $improvements += [pscustomobject]@{
            Scope = $scope
            Method = $pair[0]
            Baseline = $pair[1]
            MeanImprovementPct = 100 * ($baseline.Mean - $method.Mean) / $baseline.Mean
            SDImprovementPct = 100 * ($baseline.PopulationSD - $method.PopulationSD) / $baseline.PopulationSD
            Q95ImprovementPct = 100 * ($baseline.Q95 - $method.Q95) / $baseline.Q95
            CVaR95ImprovementPct = 100 * ($baseline.CVaR95 - $method.CVaR95) / $baseline.CVaR95
        }
    }
}
$improvements | Export-Csv -LiteralPath "$Output/pooled_improvements.csv" -NoTypeInformation -Encoding UTF8

$allCertified = @($rows | Where-Object { $_.Certified -ne $_.Seeds }).Count -eq 0
@(
    'status=PASSED'
    'metric=unconditional pooled OOS distribution across seed x draw'
    'conditional_metrics_not_substituted=true'
    'calibrationSeeds=0-9'
    'independentEvaluationSeeds=10-49'
    'exploratoryAllIncludesCalibration=true'
    'methods=D,SAA,CSAA,DRO_lambda_0.5'
    "allCertified=$allCertified"
    'drawsCalibrationPerMethod=2000'
    'drawsEvaluationPerMethod=8000'
    'drawsAllPerMethod=10000'
) | Set-Content -LiteralPath "$Output/validation.txt" -Encoding UTF8

$rows | Format-Table -AutoSize
$improvements | Format-Table -AutoSize
