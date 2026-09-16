param(
    [int]$Replications = 5000,
    [int]$BlockLength = 4,
    [string]$OutputRoot = "analysis/TRB_reviewer_revision/293_olist_rolling_block_bootstrap_20260813"
)
$ErrorActionPreference="Stop"
$source="analysis/TRB_reviewer_revision/03_olist_rolling_min_h_coverage_mqc_20260811/coverage_100/mqc_100/rolling_trials.csv"
$methods='D','SAA','CSAA','RCSAA','DRO'
$rows=Import-Csv -LiteralPath $source
foreach($m in $methods){$x=@($rows|Where-Object method -eq $m);if($x.Count-ne51){throw "Expected 51 rows for $m"};if(@($x|Where-Object certified_optimal -ne 'true').Count-ne0){throw "uncertified"}}
function Mean([double[]]$x){($x|Measure-Object -Average).Average}
function Q([double[]]$x,[double]$p){$s=$x|Sort-Object;$z=$p*($s.Count-1);$l=[math]::Floor($z);$h=[math]::Ceiling($z);if($l-eq$h){return [double]$s[$l]};$w=$z-$l;[double]$s[$l]*(1-$w)+[double]$s[$h]*$w}
function Metric([double[]]$x,[string]$name){if($name-eq'Mean'){return Mean $x};$m=Mean $x;if($name-eq'SD'){return [math]::Sqrt((Mean ([double[]]($x|ForEach-Object{($_-$m)*($_-$m)}))))};if($name-eq'Q95'){return Q $x .95};$s=$x|Sort-Object;$n=[math]::Ceiling(.05*$s.Count);return Mean ([double[]]$s[($s.Count-$n)..($s.Count-1)])}
$cost=@{};foreach($m in $methods){$cost[$m]=[double[]](($rows|Where-Object method -eq $m|Sort-Object {[int]$_.trialId})|ForEach-Object{[double]$_.realized_obj})}
$rng=[Random]::new(20260813);$pairs=@(@('SAA','D'),@('CSAA','SAA'),@('RCSAA','CSAA'),@('DRO','CSAA'))
$boot=@{};foreach($p in $pairs){foreach($metric in 'Mean','SD','Q95','CVaR95'){$boot["$($p[0])|$($p[1])|$metric"]=[System.Collections.Generic.List[double]]::new()}}
for($b=0;$b-lt$Replications;$b++){$idx=[System.Collections.Generic.List[int]]::new();while($idx.Count-lt51){$start=$rng.Next(51);for($k=0;$k-lt$BlockLength-and$idx.Count-lt51;$k++){$idx.Add(($start+$k)%51)}};foreach($p in $pairs){$a=[double[]]($idx|ForEach-Object{$cost[$p[0]][$_]});$base=[double[]]($idx|ForEach-Object{$cost[$p[1]][$_]});foreach($metric in 'Mean','SD','Q95','CVaR95'){$bv=Metric $base $metric;$av=Metric $a $metric;$boot["$($p[0])|$($p[1])|$metric"].Add(100*($bv-$av)/$bv)}}}
New-Item -ItemType Directory -Force -Path $OutputRoot|Out-Null
$out=@();foreach($p in $pairs){foreach($metric in 'Mean','SD','Q95','CVaR95'){$v=$boot["$($p[0])|$($p[1])|$metric"].ToArray();$out+=[pscustomobject]@{method=$p[0];baseline=$p[1];metric=$metric;point=100*((Metric $cost[$p[1]] $metric)-(Metric $cost[$p[0]] $metric))/(Metric $cost[$p[1]] $metric);ci025=Q $v .025;ci975=Q $v .975;positiveShare=@($v|Where-Object{$_-gt0}).Count/$v.Count}}}
$out|Export-Csv (Join-Path $OutputRoot 'bootstrap_ci.csv') -NoTypeInformation -Encoding UTF8
$loo=@();foreach($drop in 0..50){foreach($p in $pairs){foreach($metric in 'Mean','SD','Q95','CVaR95'){$a=[double[]](0..50|Where-Object{$_-ne$drop}|ForEach-Object{$cost[$p[0]][$_]});$base=[double[]](0..50|Where-Object{$_-ne$drop}|ForEach-Object{$cost[$p[1]][$_]});$loo+=[pscustomobject]@{dropTrial=$drop;method=$p[0];baseline=$p[1];metric=$metric;improvement=100*((Metric $base $metric)-(Metric $a $metric))/(Metric $base $metric)}}}}
$loo|Export-Csv (Join-Path $OutputRoot 'leave_one_week_out.csv') -NoTypeInformation -Encoding UTF8
$looSummary=foreach($g in $loo|Group-Object method,baseline,metric){$v=[double[]]($g.Group|ForEach-Object{$_.improvement});[pscustomobject]@{method=$g.Group[0].method;baseline=$g.Group[0].baseline;metric=$g.Group[0].metric;min=($v|Measure-Object -Minimum).Minimum;max=($v|Measure-Object -Maximum).Maximum;negativeDrops=@($v|Where-Object{$_-le0}).Count}}
$looSummary|Export-Csv (Join-Path $OutputRoot 'leave_one_week_out_summary.csv') -NoTypeInformation -Encoding UTF8
@("status=PASSED","replications=$Replications","blockLength=$BlockLength","weeks=51","source=$source")|Set-Content (Join-Path $OutputRoot 'validation.txt') -Encoding UTF8
