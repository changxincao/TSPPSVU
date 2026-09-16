param(
    [string]$OutputRoot = "analysis/TRB_reviewer_revision/296_olist_v2_trend_season_stress_20260814",
    [string]$WeeklyCsv = ""
)

$ErrorActionPreference = 'Stop'
$software = ([string][char]0x8F6F) + ([string][char]0x4EF6)
$softwareRoot = "D:/$software"
$java = "$softwareRoot/Java/jdk_22/bin/java.exe"
$classPath = "bin;$softwareRoot/cplex/ILOG/CPLEX_Studio2211/cplex/lib/cplex.jar;$softwareRoot/Mosek/11.0/tools/platform/win64x86/bin/mosek.jar"
$env:PATH = "$softwareRoot/cplex/ILOG/CPLEX_Studio2211/cplex/bin/x64_win64;$softwareRoot/Mosek/11.0/tools/platform/win64x86/bin;" + $env:PATH
$gate = 'Test.analysis.synthetic.TRBReviewerOlistDynamicDgpV2Gate'
if ([string]::IsNullOrWhiteSpace($WeeklyCsv)) {
    $WeeklyCsv = (Get-ChildItem -LiteralPath 'analysis' -Filter '*.csv' -Recurse |
        Where-Object { $_.Name -match '23OD' -and $_.Name -match '10' } |
        Sort-Object { $_.FullName.Length } | Select-Object -First 1).FullName
}

# Ex-ante stress cases. 0.5% weekly log trend gives about 30% growth over 53
# retained periods; 10% seasonality is a moderate, fixed 52-week amplitude.
$cases = @(
    [pscustomobject]@{name='trend'; logTrend=0.005; seasonalAmplitude=0.0},
    [pscustomobject]@{name='season'; logTrend=0.0; seasonalAmplitude=0.10},
    [pscustomobject]@{name='trend_season'; logTrend=0.005; seasonalAmplitude=0.10}
)
$markets = @(0, 1, 2)
$rows = @()
New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null
foreach ($case in $cases) {
    foreach ($market in $markets) {
        $root = Join-Path $OutputRoot ("{0}/market_{1}" -f $case.name, $market)
        $result = Join-Path $root 'query_results.csv'
        if (-not (Test-Path -LiteralPath $result)) {
            if ((Test-Path -LiteralPath $root) -and
                    @(Get-ChildItem -LiteralPath $root -Force).Count -gt 0) {
                throw "Nonempty partial output requires manual audit: $root"
            }
            $demandSeedBase = 100000L + 10000L * $market
            & $java -cp $classPath $gate $WeeklyCsv $root 23 30 50 10 500 0.5 $market lognormal 0.345 1.0 $demandSeedBase 0.5 1.0 'D,SAA,CSAA' $case.logTrend $case.seasonalAmplitude
            if ($LASTEXITCODE -ne 0) {
                throw "Stress gate failed with exit code ${LASTEXITCODE}: $root"
            }
        }
        $loaded = @(Import-Csv -LiteralPath $result)
        if ($loaded.Count -ne 30 -or @($loaded | Where-Object certifiedOptimal -ne 'true').Count -ne 0) {
            throw "Invalid completed stress result: $root"
        }
        foreach ($row in $loaded) {
            $row | Add-Member -NotePropertyName stressCase -NotePropertyValue $case.name
            $row | Add-Member -NotePropertyName marketSeed -NotePropertyValue $market
            $row | Add-Member -NotePropertyName logTrendPerPeriod -NotePropertyValue $case.logTrend
            $row | Add-Member -NotePropertyName seasonalAmplitude -NotePropertyValue $case.seasonalAmplitude
            $rows += $row
        }
    }
}
$rows | Export-Csv -NoTypeInformation -Encoding utf8 -LiteralPath (Join-Path $OutputRoot 'all_query_results.csv')

$pairs = @(@('SAA','D'), @('CSAA','SAA'))
$summary = @()
foreach ($case in $cases.name) {
    foreach ($pair in $pairs) {
        $method = $pair[0]
        $baseline = $pair[1]
        $methodRows = @($rows | Where-Object { $_.stressCase -eq $case -and $_.method -eq $method })
        $baselineRows = @($rows | Where-Object { $_.stressCase -eq $case -and $_.method -eq $baseline })
        $baselineByKey = @{}
        foreach ($row in $baselineRows) { $baselineByKey["$($row.marketSeed)|$($row.pathIndex)"] = $row }
        foreach ($metric in @('meanOosCost','sdOosCost','q95OosCost','cvar95OosCost')) {
            $improvements = @()
            foreach ($row in $methodRows) {
                $base = $baselineByKey["$($row.marketSeed)|$($row.pathIndex)"]
                $b = [double]$base.$metric
                $m = [double]$row.$metric
                $improvements += 100.0 * ($b - $m) / $b
            }
            $summary += [pscustomobject]@{
                stressCase = $case
                comparison = "$method-$baseline"
                metric = $metric
                pairedQueries = $improvements.Count
                meanImprovementPct = ($improvements | Measure-Object -Average).Average
                positiveQueries = @($improvements | Where-Object { $_ -gt 0.0 }).Count
            }
        }
    }
}
$summary | Export-Csv -NoTypeInformation -Encoding utf8 -LiteralPath (Join-Path $OutputRoot 'paired_summary.csv')
