param(
    [string]$OutputRoot = "analysis/TRB_reviewer_revision/281_olist_v2_remaining_input_summary_20260813"
)

$ErrorActionPreference = "Stop"
$configs = @(
    @{ Cell="lognormal_low"; Rho=0.50; Market=0; Root="analysis/TRB_reviewer_revision/266_olist_v2_j23_i30_s50_rho050_lognormal_low_market0_20260813" },
    @{ Cell="lognormal_low"; Rho=0.50; Market=1; Root="analysis/TRB_reviewer_revision/267_olist_v2_j23_i30_s50_rho050_lognormal_low_market1_20260813" },
    @{ Cell="lognormal_low"; Rho=0.50; Market=2; Root="analysis/TRB_reviewer_revision/268_olist_v2_j23_i30_s50_rho050_lognormal_low_market2_20260813" },
    @{ Cell="lognormal_high"; Rho=0.50; Market=0; Root="analysis/TRB_reviewer_revision/247_olist_v2_j23_i30_s50_rho050_diag_market0_20260813" },
    @{ Cell="lognormal_high"; Rho=0.50; Market=1; Root="analysis/TRB_reviewer_revision/250_olist_v2_j23_i30_s50_rho050_holdout_market1_20260813" },
    @{ Cell="lognormal_high"; Rho=0.50; Market=2; Root="analysis/TRB_reviewer_revision/251_olist_v2_j23_i30_s50_rho050_holdout_market2_20260813" },
    @{ Cell="uniform_low"; Rho=0.50; Market=0; Root="analysis/TRB_reviewer_revision/272_olist_v2_j23_i30_s50_rho050_uniform_low_market0_20260813" },
    @{ Cell="uniform_low"; Rho=0.50; Market=1; Root="analysis/TRB_reviewer_revision/273_olist_v2_j23_i30_s50_rho050_uniform_low_market1_20260813" },
    @{ Cell="uniform_low"; Rho=0.50; Market=2; Root="analysis/TRB_reviewer_revision/274_olist_v2_j23_i30_s50_rho050_uniform_low_market2_20260813" },
    @{ Cell="uniform_high"; Rho=0.50; Market=0; Root="analysis/TRB_reviewer_revision/278_olist_v2_j23_i30_s50_rho050_uniform_high_market0_20260813" },
    @{ Cell="uniform_high"; Rho=0.50; Market=1; Root="analysis/TRB_reviewer_revision/279_olist_v2_j23_i30_s50_rho050_uniform_high_market1_20260813" },
    @{ Cell="uniform_high"; Rho=0.50; Market=2; Root="analysis/TRB_reviewer_revision/280_olist_v2_j23_i30_s50_rho050_uniform_high_market2_20260813" },
    @{ Cell="lognormal_high_rho0"; Rho=0.00; Market=0; Root="analysis/TRB_reviewer_revision/275_olist_v2_j23_i30_s50_rho000_lognormal_high_market0_20260813" },
    @{ Cell="lognormal_high_rho0"; Rho=0.00; Market=1; Root="analysis/TRB_reviewer_revision/276_olist_v2_j23_i30_s50_rho000_lognormal_high_market1_20260813" },
    @{ Cell="lognormal_high_rho0"; Rho=0.00; Market=2; Root="analysis/TRB_reviewer_revision/277_olist_v2_j23_i30_s50_rho000_lognormal_high_market2_20260813" }
)

function Mean([double[]]$x) { return ($x | Measure-Object -Average).Average }
function Quantile([double[]]$x, [double]$p) {
    $s = $x | Sort-Object
    $pos = $p * ($s.Count - 1)
    $lo = [Math]::Floor($pos); $hi = [Math]::Ceiling($pos)
    if ($lo -eq $hi) { return [double]$s[$lo] }
    $w = $pos - $lo
    return [double]$s[$lo] * (1.0 - $w) + [double]$s[$hi] * $w
}
function Summary([double[]]$x) {
    $mean = Mean $x
    $variance = Mean ([double[]]($x | ForEach-Object { ($_ - $mean) * ($_ - $mean) }))
    $q95 = Quantile $x 0.95
    $sorted = $x | Sort-Object
    $tailCount = [Math]::Max(1, [Math]::Ceiling(0.05 * $sorted.Count))
    $cvar = Mean ([double[]]$sorted[($sorted.Count - $tailCount)..($sorted.Count - 1)])
    return @{ Mean=$mean; SD=[Math]::Sqrt($variance); Q95=$q95; CVaR95=$cvar }
}
function Improvement([double]$base, [double]$method) { return 100.0 * ($base - $method) / $base }

New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null
$methodRows = [System.Collections.Generic.List[object]]::new()
$pairRows = [System.Collections.Generic.List[object]]::new()
$maxGap = 0.0; $maxDecomposition = 0.0; $methodQueries = 0; $oosRows = 0

foreach ($config in $configs) {
    $queries = Import-Csv -LiteralPath (Join-Path $config.Root "query_results.csv")
    if ($queries.Count -ne 30) { throw "Expected 30 query rows: $($config.Root)" }
    $methodQueries += $queries.Count
    $byMethod = @{}
    foreach ($method in @("D","SAA","CSAA")) {
        $costs = [System.Collections.Generic.List[double]]::new()
        $transport = [System.Collections.Generic.List[double]]::new()
        $spot = [System.Collections.Generic.List[double]]::new()
        $penalty = [System.Collections.Generic.List[double]]::new()
        foreach ($path in 0..9) {
            $query = @($queries | Where-Object { [int]$_.pathIndex -eq $path -and $_.method -eq $method })
            if ($query.Count -ne 1 -or $query[0].certifiedOptimal -ne "true") { throw "Bad query row $($config.Root) $path $method" }
            $gap = [double]$query[0].relativeGap
            if ($gap -gt 1e-4) { throw "Gap exceeds tolerance" }
            $maxGap = [Math]::Max($maxGap, $gap)
            $draws = Import-Csv -LiteralPath (Join-Path $config.Root (("path_{0:D3}/{1}/oos_costs.csv") -f $path,$method))
            if ($draws.Count -lt 200) { throw "Insufficient draws" }
            foreach ($draw in $draws) {
                $total = [double]$draw.totalCost; $tr = [double]$draw.transportCost
                $sp = [double]$draw.spotCost; $pe = [double]$draw.penaltyCost
                $maxDecomposition = [Math]::Max($maxDecomposition, [Math]::Abs($total-$tr-$sp-$pe))
                $costs.Add($total); $transport.Add($tr); $spot.Add($sp); $penalty.Add($pe); $oosRows++
            }
        }
        $metric = Summary $costs.ToArray()
        $row = [pscustomobject]@{
            cell=$config.Cell; rho=$config.Rho; market=$config.Market; method=$method; draws=$costs.Count
            mean=$metric.Mean; sd=$metric.SD; q95=$metric.Q95; cvar95=$metric.CVaR95
            transport=Mean $transport.ToArray(); spot=Mean $spot.ToArray(); penalty=Mean $penalty.ToArray()
        }
        $methodRows.Add($row); $byMethod[$method]=$row
    }
    foreach ($pair in @(@("SAA","D"),@("CSAA","SAA"))) {
        $method=$byMethod[$pair[0]]; $base=$byMethod[$pair[1]]
        $pairRows.Add([pscustomobject]@{
            cell=$config.Cell; rho=$config.Rho; market=$config.Market; comparison="$($pair[0])-vs-$($pair[1])"
            meanPct=Improvement $base.mean $method.mean; sdPct=Improvement $base.sd $method.sd
            q95Pct=Improvement $base.q95 $method.q95; cvar95Pct=Improvement $base.cvar95 $method.cvar95
            fullChain=($base.mean -gt $method.mean -and $base.sd -gt $method.sd -and $base.q95 -gt $method.q95 -and $base.cvar95 -gt $method.cvar95)
        })
    }
}

if ($maxDecomposition -gt 1e-6) { throw "Cost decomposition failed" }
$methodRows | Export-Csv -LiteralPath (Join-Path $OutputRoot "market_method_summary.csv") -NoTypeInformation -Encoding UTF8
$pairRows | Export-Csv -LiteralPath (Join-Path $OutputRoot "market_pair_improvements.csv") -NoTypeInformation -Encoding UTF8
$cellRows = foreach ($group in ($pairRows | Group-Object cell,comparison)) {
    $g=@($group.Group)
    [pscustomobject]@{
        cell=$g[0].cell; comparison=$g[0].comparison; markets=$g.Count
        meanPct=Mean ([double[]]($g|ForEach-Object{$_.meanPct})); sdPct=Mean ([double[]]($g|ForEach-Object{$_.sdPct}))
        q95Pct=Mean ([double[]]($g|ForEach-Object{$_.q95Pct})); cvar95Pct=Mean ([double[]]($g|ForEach-Object{$_.cvar95Pct}))
        fullChainMarkets=@($g|Where-Object fullChain -eq "True").Count
    }
}
$cellRows | Export-Csv -LiteralPath (Join-Path $OutputRoot "cell_pair_summary.csv") -NoTypeInformation -Encoding UTF8
@(
    "status=PASSED", "configs=$($configs.Count)", "methodQueries=$methodQueries", "oosRows=$oosRows",
    "maxRelativeGap=$maxGap", "maxCostDecompositionError=$maxDecomposition"
) | Set-Content -LiteralPath (Join-Path $OutputRoot "validation.txt") -Encoding UTF8
