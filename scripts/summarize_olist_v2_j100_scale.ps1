param(
    [string]$Root = 'analysis/TRB_reviewer_revision/294_olist_v2_j100_i30_s100_rho050_lognormal_high_market0_20260814'
)
$ErrorActionPreference='Stop'
function Mean([double[]]$x){($x|Measure-Object -Average).Average}
function Quantile([double[]]$x,[double]$p){$s=@($x|Sort-Object);$z=$p*($s.Count-1);$l=[math]::Floor($z);$u=[math]::Ceiling($z);if($l-eq$u){return [double]$s[$l]};$w=$z-$l;return [double]$s[$l]*(1-$w)+[double]$s[$u]*$w}
$queries=@(Import-Csv (Join-Path $Root 'query_results.csv'))
if($queries.Count-ne 9){throw 'Expected 9 query rows'}
$maxGap=0.0;$maxDecomp=0.0;$rows=@()
foreach($method in @('D','SAA','CSAA')){
  $cost=[System.Collections.Generic.List[double]]::new();$tr=[System.Collections.Generic.List[double]]::new();$sp=[System.Collections.Generic.List[double]]::new();$pe=[System.Collections.Generic.List[double]]::new()
  foreach($path in 0..2){
    $q=@($queries|Where-Object{$_.method-eq$method-and[int]$_.pathIndex-eq$path})
    if($q.Count-ne1-or$q[0].certifiedOptimal-ne'true'){throw "Bad query $method $path"}
    $gap=[double]$q[0].relativeGap;if($gap-gt1e-4){throw 'Gap exceeds tolerance'};$maxGap=[math]::Max($maxGap,$gap)
    $draws=@(Import-Csv (Join-Path $Root (("path_{0:D3}/{1}/oos_costs.csv")-f$path,$method)))
    if($draws.Count-ne200){throw 'Expected 200 draws'}
    foreach($d in $draws){$t=[double]$d.totalCost;$a=[double]$d.transportCost;$b=[double]$d.spotCost;$c=[double]$d.penaltyCost;$maxDecomp=[math]::Max($maxDecomp,[math]::Abs($t-$a-$b-$c));$cost.Add($t);$tr.Add($a);$sp.Add($b);$pe.Add($c)}
  }
  $mean=Mean $cost.ToArray();$sd=[math]::Sqrt((Mean ([double[]]($cost|ForEach-Object{($_-$mean)*($_-$mean)}))));$q95=Quantile $cost.ToArray() .95;$tail=[double[]]($cost|Where-Object{$_-ge$q95})
  $rows+=[pscustomobject]@{method=$method;queries=3;draws=600;mean=$mean;sd=$sd;q95=$q95;cvar95=Mean $tail;transport=Mean $tr.ToArray();spot=Mean $sp.ToArray();penalty=Mean $pe.ToArray();meanOptimizerTimeSec=Mean ([double[]]($queries|Where-Object {$_.method-eq$method}|ForEach-Object optimizerTimeSec))}
}
if($maxDecomp-gt1e-6){throw 'Cost decomposition failed'}
$rows|Export-Csv -NoTypeInformation -Encoding utf8 (Join-Path $Root 'pooled_method_summary.csv')
@('status=PASSED','J=100','I=30','S=100','paths=3','drawsPerPath=200',"maxRelativeGap=$maxGap","maxCostDecompositionError=$maxDecomp")|Set-Content -Encoding utf8 (Join-Path $Root 'validation.txt')
