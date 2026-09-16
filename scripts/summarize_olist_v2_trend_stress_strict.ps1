param([string]$Root='analysis/TRB_reviewer_revision/296_olist_v2_trend_season_stress_20260814')
$ErrorActionPreference='Stop'
function Mean([double[]]$x){($x|Measure-Object -Average).Average}
function Quantile([double[]]$x,[double]$p){$s=@($x|Sort-Object);$z=$p*($s.Count-1);$l=[math]::Floor($z);$u=[math]::Ceiling($z);if($l-eq$u){return [double]$s[$l]};$w=$z-$l;return [double]$s[$l]*(1-$w)+[double]$s[$u]*$w}
function Stats([double[]]$x){$m=Mean $x;$sd=[math]::Sqrt((Mean ([double[]]($x|ForEach-Object{($_-$m)*($_-$m)}))));$q=Quantile $x .95;$tail=[double[]]($x|Where-Object{$_-ge$q});@{mean=$m;sd=$sd;q95=$q;cvar95=Mean $tail}}
function Pct([double]$b,[double]$m){100*($b-$m)/$b}
$caseSpec=@{trend=@(0.005,0.0);season=@(0.0,0.10);trend_season=@(0.005,0.10)}
$rows=@();$maxGap=0.0;$maxDecomp=0.0;$totalDraws=0
foreach($case in @('trend','season','trend_season')){
  foreach($method in @('D','SAA','CSAA')){
    $cost=[System.Collections.Generic.List[double]]::new();$tr=[System.Collections.Generic.List[double]]::new();$sp=[System.Collections.Generic.List[double]]::new();$pe=[System.Collections.Generic.List[double]]::new()
    foreach($market in 0..2){
      $marketRoot=Join-Path $Root ("{0}/market_{1}"-f$case,$market)
      $prop=Get-Content (Join-Path $marketRoot 'experiment.properties')
      if(-not($prop-match("logTrendPerPeriod="+[regex]::Escape([string]$caseSpec[$case][0])))){throw "Trend metadata mismatch: $marketRoot"}
      if(-not($prop-match("seasonalAmplitude="+[regex]::Escape([string]$caseSpec[$case][1])))){throw "Season metadata mismatch: $marketRoot"}
      $query=@(Import-Csv (Join-Path $marketRoot 'query_results.csv'))
      foreach($path in 0..9){
        $q=@($query|Where-Object{$_.method-eq$method-and[int]$_.pathIndex-eq$path})
        if($q.Count-ne1-or$q[0].certifiedOptimal-ne'true'){throw "Bad query $case $market $path $method"}
        $gap=[double]$q[0].relativeGap;if($gap-gt1e-4){throw 'Gap exceeds tolerance'};$maxGap=[math]::Max($maxGap,$gap)
        $draw=@(Import-Csv (Join-Path $marketRoot (("path_{0:D3}/{1}/oos_costs.csv")-f$path,$method)))
        if($draw.Count-ne500){throw 'Expected 500 draws'}
        foreach($d in $draw){$t=[double]$d.totalCost;$a=[double]$d.transportCost;$b=[double]$d.spotCost;$c=[double]$d.penaltyCost;$maxDecomp=[math]::Max($maxDecomp,[math]::Abs($t-$a-$b-$c));$cost.Add($t);$tr.Add($a);$sp.Add($b);$pe.Add($c);$totalDraws++}
      }
    }
    $s=Stats $cost.ToArray();$rows+=[pscustomobject]@{stressCase=$case;method=$method;queries=30;draws=$cost.Count;mean=$s.mean;sd=$s.sd;q95=$s.q95;cvar95=$s.cvar95;transport=Mean $tr.ToArray();spot=Mean $sp.ToArray();penalty=Mean $pe.ToArray()}
  }
}
if($maxDecomp-gt1e-6){throw 'Cost decomposition failed'}
$rows|Export-Csv -NoTypeInformation -Encoding utf8 (Join-Path $Root 'strict_pooled_method_summary.csv')
$pairs=@();foreach($case in @('trend','season','trend_season')){foreach($pair in @(@('SAA','D'),@('CSAA','SAA'))){$m=@($rows|Where-Object{$_.stressCase-eq$case-and$_.method-eq$pair[0]})[0];$b=@($rows|Where-Object{$_.stressCase-eq$case-and$_.method-eq$pair[1]})[0];$pairs+=[pscustomobject]@{stressCase=$case;comparison="$($pair[0])-$($pair[1])";meanPct=Pct $b.mean $m.mean;sdPct=Pct $b.sd $m.sd;q95Pct=Pct $b.q95 $m.q95;cvar95Pct=Pct $b.cvar95 $m.cvar95}}}
$pairs|Export-Csv -NoTypeInformation -Encoding utf8 (Join-Path $Root 'strict_pooled_pair_improvements.csv')
@('status=PASSED','cases=3','methods=3','queriesPerCaseMethod=30','drawsPerQuery=500',"totalOosRows=$totalDraws","maxRelativeGap=$maxGap","maxCostDecompositionError=$maxDecomp")|Set-Content -Encoding utf8 (Join-Path $Root 'strict_validation.txt')
