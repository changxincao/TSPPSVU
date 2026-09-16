param([string]$OutputRoot='analysis/TRB_reviewer_revision/300_olist_v2_kernel_independent_summary_20260814')
$ErrorActionPreference='Stop'
$expCsaa=@('analysis/TRB_reviewer_revision/247_olist_v2_j23_i30_s50_rho050_diag_market0_20260813','analysis/TRB_reviewer_revision/250_olist_v2_j23_i30_s50_rho050_holdout_market1_20260813','analysis/TRB_reviewer_revision/251_olist_v2_j23_i30_s50_rho050_holdout_market2_20260813')
$expDro='analysis/TRB_reviewer_revision/295_olist_v2_independent_calibration_robust_20260814/evaluation/dro'
$gau='analysis/TRB_reviewer_revision/299_olist_v2_gaussian_independent_20260814/evaluation'
function Mean([double[]]$x){($x|Measure-Object -Average).Average}
function Q([double[]]$x,[double]$p){$s=@($x|Sort-Object);$z=$p*($s.Count-1);$l=[math]::Floor($z);$u=[math]::Ceiling($z);if($l-eq$u){return[double]$s[$l]};$w=$z-$l;[double]$s[$l]*(1-$w)+[double]$s[$u]*$w}
function Stats([double[]]$x){$m=Mean $x;$sd=[math]::Sqrt((Mean ([double[]]($x|ForEach-Object{($_-$m)*($_-$m)}))));$q=Q $x .95;$tail=[double[]]($x|Where-Object{$_-ge$q});@{mean=$m;sd=$sd;q95=$q;cvar95=Mean $tail}}
function Pct([double]$b,[double]$m){100*($b-$m)/$b}
New-Item -ItemType Directory -Force $OutputRoot|Out-Null
$rows=@();$query=@();$maxGap=0.0;$maxDecomp=0.0;$total=0
foreach($kernel in @('EXPONENTIAL','GAUSSIAN')){foreach($method in @('CSAA','DRO')){
  $cost=[System.Collections.Generic.List[double]]::new();$tr=[System.Collections.Generic.List[double]]::new();$sp=[System.Collections.Generic.List[double]]::new();$pe=[System.Collections.Generic.List[double]]::new()
  foreach($m in 0..2){
    $root=if($kernel-eq'EXPONENTIAL'){if($method-eq'CSAA'){$expCsaa[$m]}else{Join-Path $expDro "market_$m"}}else{Join-Path $gau "market_$m"}
    $prop=Get-Content (Join-Path $root 'experiment.properties');if($kernel-eq'GAUSSIAN'-and-not($prop-match'kernelType=GAUSSIAN')){throw 'Gaussian metadata mismatch'}
    $qr=@(Import-Csv (Join-Path $root 'query_results.csv'))
    foreach($p in 0..9){$qrow=@($qr|Where-Object{$_.method-eq$method-and[int]$_.pathIndex-eq$p});if($qrow.Count-ne1-or$qrow[0].certifiedOptimal-ne'true'){throw "Bad query $kernel $method $m $p"};$gap=[double]$qrow[0].relativeGap;if($gap-gt1e-4){throw 'Gap'};$maxGap=[math]::Max($maxGap,$gap);$query+=[pscustomobject]@{kernel=$kernel;method=$method;market=$m;path=$p;mean=[double]$qrow[0].meanOosCost;sd=[double]$qrow[0].sdOosCost;q95=[double]$qrow[0].q95OosCost;cvar95=[double]$qrow[0].cvar95OosCost}
      $draw=@(Import-Csv (Join-Path $root (("path_{0:D3}/{1}/oos_costs.csv")-f$p,$method)));if($draw.Count-ne500){throw 'draw count'};foreach($d in $draw){$t=[double]$d.totalCost;$a=[double]$d.transportCost;$b=[double]$d.spotCost;$c=[double]$d.penaltyCost;$maxDecomp=[math]::Max($maxDecomp,[math]::Abs($t-$a-$b-$c));$cost.Add($t);$tr.Add($a);$sp.Add($b);$pe.Add($c);$total++}
    }
  }
  $s=Stats $cost.ToArray();$rows+=[pscustomobject]@{kernel=$kernel;method=$method;queries=30;draws=$cost.Count;mean=$s.mean;sd=$s.sd;q95=$s.q95;cvar95=$s.cvar95;transport=Mean $tr.ToArray();spot=Mean $sp.ToArray();penalty=Mean $pe.ToArray()}
}}
if($maxDecomp-gt1e-6){throw 'decomposition'}
$rows|Export-Csv -NoTypeInformation -Encoding utf8 (Join-Path $OutputRoot 'pooled_method_summary.csv')
$pairs=@();foreach($method in @('CSAA','DRO')){$g=@($rows|Where-Object{$_.kernel-eq'GAUSSIAN'-and$_.method-eq$method})[0];$e=@($rows|Where-Object{$_.kernel-eq'EXPONENTIAL'-and$_.method-eq$method})[0];$cond=@($query|Where-Object{$_.kernel-eq'GAUSSIAN'-and$_.method-eq$method});foreach($metric in @('mean','sd','q95','cvar95')){$v=@();foreach($r in $cond){$b=@($query|Where-Object{$_.kernel-eq'EXPONENTIAL'-and$_.method-eq$method-and$_.market-eq$r.market-and$_.path-eq$r.path})[0];$v+=Pct ([double]$b.$metric) ([double]$r.$metric)};$pairs+=[pscustomobject]@{method=$method;metric=$metric;pooledGaussianImprovementPct=Pct ([double]$e.$metric) ([double]$g.$metric);meanConditionalImprovementPct=Mean ([double[]]$v);gaussianPositiveQueries=@($v|Where-Object{$_-gt0}).Count}}}
$pairs|Export-Csv -NoTypeInformation -Encoding utf8 (Join-Path $OutputRoot 'paired_kernel_improvements.csv')
@('status=PASSED','kernels=EXPONENTIAL,GAUSSIAN','methods=CSAA,DRO','pairedQueriesPerMethod=30','drawsPerQuery=500',"totalOosRows=$total","maxRelativeGap=$maxGap","maxCostDecompositionError=$maxDecomp",'calibrationMarkets=90,91,92','evaluationMarkets=0,1,2')|Set-Content -Encoding utf8 (Join-Path $OutputRoot 'validation.txt')
