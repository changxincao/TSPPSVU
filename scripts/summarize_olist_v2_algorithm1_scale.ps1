param(
    [string]$OutputRoot = "analysis/TRB_reviewer_revision/303_olist_v2_algorithm1_scale_summary_20260814"
)

$ErrorActionPreference = 'Stop'
$inputs = @(
    @{ I = 10; Root = 'analysis/TRB_reviewer_revision/301_olist_v2_algorithm1_i10_gate_20260814' },
    @{ I = 10; Root = 'analysis/TRB_reviewer_revision/302_olist_v2_algorithm1_i10_paths1to2_20260814' },
    @{ I = 20; Root = 'analysis/TRB_reviewer_revision/301_olist_v2_algorithm1_i20_gate_20260814' },
    @{ I = 20; Root = 'analysis/TRB_reviewer_revision/302_olist_v2_algorithm1_i20_paths1to2_20260814' },
    @{ I = 30; Root = 'analysis/TRB_reviewer_revision/301_olist_v2_algorithm1_i30_gate_20260814' },
    @{ I = 30; Root = 'analysis/TRB_reviewer_revision/302_olist_v2_algorithm1_i30_paths1to2_20260814' }
)

function Read-Properties([string]$path) {
    $result = @{}
    foreach ($line in Get-Content -LiteralPath $path -Encoding UTF8) {
        if ($line.StartsWith('#') -or -not $line.Contains('=')) { continue }
        $parts = $line.Split('=', 2)
        $result[$parts[0]] = $parts[1]
    }
    return $result
}

if (Test-Path -LiteralPath $OutputRoot) {
    throw "Refusing to overwrite existing output: $OutputRoot"
}
New-Item -ItemType Directory -Path $OutputRoot | Out-Null

$raw = [Collections.Generic.List[object]]::new()
$maxDecompositionError = 0.0
$totalOosRows = 0
foreach ($input in $inputs) {
    $root = $input.Root
    $props = Read-Properties (Join-Path $root 'experiment.properties')
    foreach ($expected in @{ J='23'; S='50'; oosDraws='200'; distribution='LOGNORMAL'; innovationCv='0.345'; C_h='1.0'; lambda='1.0'; coverage='0.5'; capacityNormalized='true'; mqcScale='1.0'; hRule='min eligible r_ij'; laneSharePersistence='0.5'; methods='RCSAA_LBBD_SEARCH' }.GetEnumerator()) {
        if ($props[$expected.Key] -ne $expected.Value) {
            throw "Metadata mismatch at $root for $($expected.Key): $($props[$expected.Key])"
        }
    }
    if ([int]$props.I -ne [int]$input.I) { throw "I mismatch at $root" }

    $queryRows = @(Import-Csv -LiteralPath (Join-Path $root 'query_results.csv'))
    foreach ($query in $queryRows) {
        $methodRoot = Join-Path $root ("path_{0:D3}/RCSAA_LBBD_SEARCH" -f [int]$query.pathIndex)
        $solve = Import-Csv -LiteralPath (Join-Path $methodRoot 'solve_summary.csv')
        if ($solve.solverStatus -ne 'OPTIMAL' -or $solve.certifiedOptimal -ne 'true') {
            throw "Uncertified solve at $methodRoot"
        }
        if ([double]$solve.relativeGap -gt 1.0e-4) { throw "Gap exceeds tolerance at $methodRoot" }
        $oos = @(Import-Csv -LiteralPath (Join-Path $methodRoot 'oos_costs.csv'))
        if ($oos.Count -ne 200) { throw "Expected 200 OOS rows at $methodRoot" }
        $drawIds = @($oos | ForEach-Object { [int]$_.drawId } | Sort-Object -Unique)
        if ($drawIds.Count -ne 200 -or $drawIds[0] -ne 0 -or $drawIds[-1] -ne 199) {
            throw "Invalid draw set at $methodRoot"
        }
        foreach ($draw in $oos) {
            $error = [Math]::Abs([double]$draw.totalCost - ([double]$draw.transportCost + [double]$draw.spotCost + [double]$draw.penaltyCost))
            if ($error -gt $maxDecompositionError) { $maxDecompositionError = $error }
        }
        $totalOosRows += $oos.Count
        $raw.Add([pscustomobject]@{
            I = [int]$input.I
            demandSeed = [long]$query.demandSeed
            status = $solve.solverStatus
            certifiedOptimal = [bool]::Parse($solve.certifiedOptimal)
            relativeGap = [double]$solve.relativeGap
            optimizerTimeSec = [double]$solve.optimizerTimeSec
            nodeCount = [long]$solve.nodeCount
            iterations = [int]$solve.iterationCount
            cuts = [int]$solve.cutCount
            candidates = [long]$solve.candidateCount
            selectedCount = [int]$solve.selectedCount
            meanOosCost = [double]$solve.meanOosCost
            sdOosCost = [double]$solve.sdOosCost
            q95OosCost = [double]$solve.q95OosCost
            cvar95OosCost = [double]$solve.cvar95OosCost
        })
    }
}

if ($raw.Count -ne 9) { throw "Expected 9 method-query rows, found $($raw.Count)" }
foreach ($i in 10,20,30) {
    $seeds = @($raw | Where-Object I -eq $i | ForEach-Object demandSeed | Sort-Object)
    if (($seeds -join ',') -ne '70000,70001,70002') { throw "Demand seeds mismatch for I=$i" }
}

$summary = foreach ($group in $raw | Group-Object I | Sort-Object { [int]$_.Name }) {
    $rows = @($group.Group)
    [pscustomobject]@{
        I = [int]$group.Name
        queries = $rows.Count
        certified = @($rows | Where-Object certifiedOptimal).Count
        meanTimeSec = ($rows | Measure-Object optimizerTimeSec -Average).Average
        minTimeSec = ($rows | Measure-Object optimizerTimeSec -Minimum).Minimum
        maxTimeSec = ($rows | Measure-Object optimizerTimeSec -Maximum).Maximum
        meanNodes = ($rows | Measure-Object nodeCount -Average).Average
        meanIterations = ($rows | Measure-Object iterations -Average).Average
        maxIterations = ($rows | Measure-Object iterations -Maximum).Maximum
        meanCuts = ($rows | Measure-Object cuts -Average).Average
        meanCandidates = ($rows | Measure-Object candidates -Average).Average
        minCandidates = ($rows | Measure-Object candidates -Minimum).Minimum
        maxCandidates = ($rows | Measure-Object candidates -Maximum).Maximum
        maxRelativeGap = ($rows | Measure-Object relativeGap -Maximum).Maximum
    }
}

$raw | Sort-Object I,demandSeed | Export-Csv -NoTypeInformation -Encoding UTF8 -LiteralPath (Join-Path $OutputRoot 'algorithm1_query_results.csv')
$summary | Export-Csv -NoTypeInformation -Encoding UTF8 -LiteralPath (Join-Path $OutputRoot 'algorithm1_scale_summary.csv')
@(
    'status=PASSED'
    'method=RCSAA_LBBD_SEARCH'
    'kappa=2'
    'J=23'
    'S=50'
    'queriesPerI=3'
    "totalMethodQueries=$($raw.Count)"
    "totalOosRows=$totalOosRows"
    "maxRelativeGap=$(($raw | Measure-Object relativeGap -Maximum).Maximum)"
    "maxCostDecompositionError=$maxDecompositionError"
    'demandSeeds=70000,70001,70002'
    'globalTimeLimitSec=600'
) | Set-Content -LiteralPath (Join-Path $OutputRoot 'validation.txt') -Encoding UTF8
