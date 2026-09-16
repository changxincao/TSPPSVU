param(
    [string]$OutputRoot = "analysis/TRB_reviewer_revision/288_olist_v2_s100_and_scale_summary_20260813"
)
$ErrorActionPreference="Stop"
$configs=@(
    @{Label="S100_LOW_M0"; Paths=10; Root="analysis/TRB_reviewer_revision/282_olist_v2_j23_i30_s100_rho050_lognormal_low_market0_20260813"},
    @{Label="S100_LOW_M1"; Paths=10; Root="analysis/TRB_reviewer_revision/283_olist_v2_j23_i30_s100_rho050_lognormal_low_market1_20260813"},
    @{Label="S100_LOW_M2"; Paths=10; Root="analysis/TRB_reviewer_revision/284_olist_v2_j23_i30_s100_rho050_lognormal_low_market2_20260813"},
    @{Label="I10_HIGH_M0"; Paths=10; Root="analysis/TRB_reviewer_revision/285_olist_v2_j23_i10_s50_rho050_lognormal_high_market0_20260813"},
    @{Label="I10_HIGH_M1"; Paths=10; Root="analysis/TRB_reviewer_revision/289_olist_v2_j23_i10_s50_rho050_lognormal_high_market1_20260813"},
    @{Label="I10_HIGH_M2"; Paths=10; Root="analysis/TRB_reviewer_revision/290_olist_v2_j23_i10_s50_rho050_lognormal_high_market2_20260813"},
    @{Label="I20_HIGH_M0"; Paths=10; Root="analysis/TRB_reviewer_revision/286_olist_v2_j23_i20_s50_rho050_lognormal_high_market0_20260813"},
    @{Label="I20_HIGH_M1"; Paths=10; Root="analysis/TRB_reviewer_revision/291_olist_v2_j23_i20_s50_rho050_lognormal_high_market1_20260813"},
    @{Label="I20_HIGH_M2"; Paths=10; Root="analysis/TRB_reviewer_revision/292_olist_v2_j23_i20_s50_rho050_lognormal_high_market2_20260813"},
    @{Label="J50_S100_HIGH_M0"; Paths=3; Root="analysis/TRB_reviewer_revision/287_olist_v2_j50_i30_s100_rho050_lognormal_high_market0_20260813"}
)
function Mean([double[]]$x){($x|Measure-Object -Average).Average}
function Q([double[]]$x,[double]$p){$s=$x|Sort-Object;$z=$p*($s.Count-1);$l=[math]::Floor($z);$h=[math]::Ceiling($z);if($l-eq$h){return [double]$s[$l]};$w=$z-$l;[double]$s[$l]*(1-$w)+[double]$s[$h]*$w}
function Metrics([double[]]$x){$m=Mean $x;$sd=[math]::Sqrt((Mean ([double[]]($x|%{($_-$m)*($_-$m)}))));$q=Q $x .95;$s=$x|Sort-Object;$n=[math]::Ceiling(.05*$s.Count);@{Mean=$m;SD=$sd;Q95=$q;CVaR=Mean ([double[]]$s[($s.Count-$n)..($s.Count-1)])}}
New-Item -ItemType Directory -Force -Path $OutputRoot|Out-Null
$rows=@();$maxGap=0.0;$maxDecomp=0.0;$oos=0
foreach($c in $configs){$qr=Import-Csv (Join-Path $c.Root 'query_results.csv');if($qr.Count-ne(3*$c.Paths)){throw "bad query count"};foreach($method in 'D','SAA','CSAA'){$costs=@();$times=@();foreach($p in 0..($c.Paths-1)){$qrow=@($qr|?{[int]$_.pathIndex-eq$p-and$_.method-eq$method});if($qrow.Count-ne1-or$qrow.certifiedOptimal-ne'true'){throw "bad query"};$maxGap=[math]::Max($maxGap,[double]$qrow.relativeGap);$times+=[double]$qrow.optimizerTimeSec;$draws=Import-Csv (Join-Path $c.Root (("path_{0:D3}/{1}/oos_costs.csv")-f$p,$method));foreach($d in $draws){$costs+=[double]$d.totalCost;$maxDecomp=[math]::Max($maxDecomp,[math]::Abs([double]$d.totalCost-[double]$d.transportCost-[double]$d.spotCost-[double]$d.penaltyCost));$oos++}};$m=Metrics ([double[]]$costs);$rows+=[pscustomobject]@{label=$c.Label;method=$method;mean=$m.Mean;sd=$m.SD;q95=$m.Q95;cvar95=$m.CVaR;meanTime=Mean ([double[]]$times)}}}
$rows|Export-Csv (Join-Path $OutputRoot 'method_summary.csv') -NoTypeInformation -Encoding UTF8
$pairs=@()
foreach($g in $rows|Group-Object label){
    $d=@($g.Group|Where-Object {$_.method -eq 'D'})[0]
    $s=@($g.Group|Where-Object {$_.method -eq 'SAA'})[0]
    $cs=@($g.Group|Where-Object {$_.method -eq 'CSAA'})[0]
    foreach($comparison in 'SAA-vs-D','CSAA-vs-SAA'){
        if($comparison -eq 'SAA-vs-D'){$method=$s;$base=$d}else{$method=$cs;$base=$s}
        $baseMean=[double]$base.mean; $baseSd=[double]$base.sd
        $baseQ95=[double]$base.q95; $baseCvar=[double]$base.cvar95
        $pairs+=[pscustomobject]@{
            label=$g.Name; comparison=$comparison
            meanPct=100*($baseMean-[double]$method.mean)/$baseMean
            sdPct=100*($baseSd-[double]$method.sd)/$baseSd
            q95Pct=100*($baseQ95-[double]$method.q95)/$baseQ95
            cvarPct=100*($baseCvar-[double]$method.cvar95)/$baseCvar
        }
    }
}
$pairs|Export-Csv (Join-Path $OutputRoot 'pair_summary.csv') -NoTypeInformation -Encoding UTF8
@("status=PASSED","oosRows=$oos","maxGap=$maxGap","maxDecomposition=$maxDecomp")|Set-Content (Join-Path $OutputRoot 'validation.txt') -Encoding UTF8
