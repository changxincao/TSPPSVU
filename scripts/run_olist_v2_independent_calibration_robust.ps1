param(
    [string]$OutputRoot = "analysis/TRB_reviewer_revision/295_olist_v2_independent_calibration_robust_20260814",
    [string]$WeeklyCsv = ""
)

$ErrorActionPreference = 'Stop'
$software = ([string][char]0x8F6F) + ([string][char]0x4EF6)
$softwareRoot = "D:/$software"
$java = "$softwareRoot/Java/jdk_22/bin/java.exe"
$classPath = "bin;$softwareRoot/cplex/ILOG/CPLEX_Studio2211/cplex/lib/cplex.jar;$softwareRoot/Mosek/11.0/tools/platform/win64x86/bin/mosek.jar"
$env:PATH = "$softwareRoot/cplex/ILOG/CPLEX_Studio2211/cplex/bin/x64_win64;$softwareRoot/Mosek/11.0/tools/platform/win64x86/bin;" + $env:PATH
$weeklyCandidates = @(Get-ChildItem -LiteralPath 'analysis' -Filter '*.csv' -Recurse |
    Where-Object { $_.Name -match '23OD' -and $_.Name -match '10' } |
    Sort-Object { $_.FullName.Length })
if ([string]::IsNullOrWhiteSpace($WeeklyCsv)) {
    if ($weeklyCandidates.Count -eq 0) {
        throw 'Cannot locate the 23OD weekly input under analysis.'
    }
    $WeeklyCsv = $weeklyCandidates[0].FullName
}
$gate = 'Test.analysis.synthetic.TRBReviewerOlistDynamicDgpV2Gate'
$culture = [Globalization.CultureInfo]::InvariantCulture
$cGrid = @(0.5, 1.0, 2.0)
$lambdaGrid = @(0.1, 0.5, 1.0, 2.0)
$calibrationMarkets = @(90, 91, 92)
$evaluationMarkets = @(0, 1, 2)

function Format-Number([double]$value) {
    return $value.ToString('0.################', $culture)
}

function Invoke-Gate(
    [string]$root,
    [int]$paths,
    [int]$oosDraws,
    [int]$marketSeed,
    [long]$demandSeedBase,
    [double]$cH,
    [double]$lambda,
    [string]$methods,
    [int]$carriers = 30
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
        if ($items.Count -gt 0) {
            throw "Nonempty partial output requires manual audit: $root"
        }
    }
    & $java -cp $classPath $gate $WeeklyCsv $root 23 $carriers 50 $paths $oosDraws 0.5 $marketSeed lognormal 0.345 (Format-Number $cH) $demandSeedBase 0.5 (Format-Number $lambda) $methods
    if ($LASTEXITCODE -ne 0) {
        throw "Gate failed with exit code ${LASTEXITCODE}: $root"
    }
}

New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null

$stage1 = @()
foreach ($cH in $cGrid) {
    foreach ($market in $calibrationMarkets) {
        $root = Join-Path $OutputRoot ("calibration/stage1_ch_{0}/market_{1}" -f ((Format-Number $cH) -replace '\.', '_'), $market)
        Invoke-Gate $root 3 200 $market (900000L + 10000L * ($market - 90)) $cH 1.0 'CSAA'
        foreach ($row in Import-Csv -LiteralPath (Join-Path $root 'query_results.csv')) {
            $stage1 += [pscustomobject]@{
                C_h = $cH
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
$stage1 | Export-Csv -NoTypeInformation -Encoding utf8 -LiteralPath (Join-Path $OutputRoot 'stage1_query_results.csv')
$stage1Summary = @($stage1 | Group-Object C_h | ForEach-Object {
    [pscustomobject]@{
        C_h = [double]$_.Name
        queries = $_.Count
        meanCalibrationOosCost = ($_.Group | Measure-Object meanOosCost -Average).Average
        maxRelativeGap = ($_.Group | ForEach-Object {[double]::Parse($_.relativeGap, $culture)} | Measure-Object -Maximum).Maximum
    }
} | Sort-Object meanCalibrationOosCost, C_h)
$stage1Summary | Export-Csv -NoTypeInformation -Encoding utf8 -LiteralPath (Join-Path $OutputRoot 'stage1_c_h_candidates.csv')
$selectedCH = [double]$stage1Summary[0].C_h

$stage2 = @()
foreach ($lambda in $lambdaGrid) {
    foreach ($market in $calibrationMarkets) {
        $root = Join-Path $OutputRoot ("calibration/stage2_lambda_{0}/market_{1}" -f ((Format-Number $lambda) -replace '\.', '_'), $market)
        Invoke-Gate $root 3 200 $market (900000L + 10000L * ($market - 90)) $selectedCH $lambda 'DRO'
        foreach ($row in Import-Csv -LiteralPath (Join-Path $root 'query_results.csv')) {
            $stage2 += [pscustomobject]@{
                lambda = $lambda
                C_h = $selectedCH
                marketSeed = $market
                pathIndex = [int]$row.pathIndex
                demandSeed = [long]$row.demandSeed
                meanOosCost = [double]::Parse($row.meanOosCost, $culture)
                sdOosCost = [double]::Parse($row.sdOosCost, $culture)
                q95OosCost = [double]::Parse($row.q95OosCost, $culture)
                cvar95OosCost = [double]::Parse($row.cvar95OosCost, $culture)
                certifiedOptimal = $row.certifiedOptimal
                relativeGap = $row.relativeGap
            }
        }
    }
}
$stage2 | Export-Csv -NoTypeInformation -Encoding utf8 -LiteralPath (Join-Path $OutputRoot 'stage2_query_results.csv')
$stage2Summary = @($stage2 | Group-Object lambda | ForEach-Object {
    [pscustomobject]@{
        lambda = [double]$_.Name
        C_h = $selectedCH
        queries = $_.Count
        meanCalibrationOosCost = ($_.Group | Measure-Object meanOosCost -Average).Average
        meanConditionalSd = ($_.Group | Measure-Object sdOosCost -Average).Average
        meanConditionalQ95 = ($_.Group | Measure-Object q95OosCost -Average).Average
        meanConditionalCvar95 = ($_.Group | Measure-Object cvar95OosCost -Average).Average
        maxRelativeGap = ($_.Group | ForEach-Object {[double]::Parse($_.relativeGap, $culture)} | Measure-Object -Maximum).Maximum
    }
} | Sort-Object meanCalibrationOosCost, lambda)
$stage2Summary | Export-Csv -NoTypeInformation -Encoding utf8 -LiteralPath (Join-Path $OutputRoot 'stage2_lambda_candidates.csv')
$selectedLambda = [double]$stage2Summary[0].lambda

[pscustomobject]@{
    selectionCriterion = 'minimum mean conditional OOS cost on independent calibration markets'
    calibrationMarketSeeds = ($calibrationMarkets -join ',')
    calibrationPathsPerMarket = 3
    calibrationOosDrawsPerPath = 200
    evaluationMarketSeeds = ($evaluationMarkets -join ',')
    evaluationPathsPerMarket = 10
    evaluationOosDrawsPerPath = 500
    distribution = 'LOGNORMAL'
    innovationCv = 0.345
    J = 23
    I = 30
    S = 50
    coverage = 0.5
    capacityNormalized = $true
    hRule = 'min eligible r_ij'
    mqcScale = 1.0
    laneSharePersistence = 0.5
    selectedC_h = $selectedCH
    selectedLambda = $selectedLambda
} | Export-Csv -NoTypeInformation -Encoding utf8 -LiteralPath (Join-Path $OutputRoot 'selected_parameters_and_protocol.csv')

foreach ($market in $evaluationMarkets) {
    $demandSeedBase = 40000L + 10000L * $market
    $droRoot = Join-Path $OutputRoot ("evaluation/dro/market_{0}" -f $market)
    Invoke-Gate $droRoot 10 500 $market $demandSeedBase $selectedCH $selectedLambda 'DRO'

}

# Small-instance exactness gate: same DGP, seeds and selected parameters, but
# I=10 so exact RCSAA can be meaningfully certified and compared with DRO.
foreach ($market in $evaluationMarkets) {
    $demandSeedBase = 40000L + 10000L * $market
    $exactRoot = Join-Path $OutputRoot ("evaluation/rcsaa_exact_i10_gate/market_{0}" -f $market)
    Invoke-Gate $exactRoot 1 500 $market $demandSeedBase $selectedCH $selectedLambda 'DRO,RCSAA_LBBD' 10
}

$excluded = Join-Path $OutputRoot 'excluded_partial_i30_exact_market0'
$oldExact = Join-Path $OutputRoot 'evaluation/rcsaa_exact_gate'
if ((Test-Path -LiteralPath $oldExact) -and -not (Test-Path -LiteralPath $excluded)) {
    Move-Item -LiteralPath $oldExact -Destination $excluded
    @(
        'EXCLUDED_FROM_ALL_SUMMARIES',
        'Reason: I=30 exact LBBD gate was interrupted before any solve_summary.csv was written.',
        'Observed after six completed LBBD iterations: gap remained about 3.65%.',
        'Replacement: same DGP/seeds/selected parameters at I=10, paired DRO and exact RCSAA.'
    ) | Set-Content -LiteralPath (Join-Path $excluded 'EXCLUDED_README.txt') -Encoding utf8
}

Write-Output ("Selected C_h={0}; lambda={1}" -f (Format-Number $selectedCH), (Format-Number $selectedLambda))
