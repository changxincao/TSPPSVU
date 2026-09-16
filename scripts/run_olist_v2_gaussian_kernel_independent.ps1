param(
    [string]$OutputRoot='analysis/TRB_reviewer_revision/299_olist_v2_gaussian_independent_20260814',
    [string]$WeeklyCsv=''
)
$ErrorActionPreference='Stop'
$software=([string][char]0x8F6F)+([string][char]0x4EF6);$softwareRoot="D:/$software"
$java="$softwareRoot/Java/jdk_22/bin/java.exe";$cp="bin;$softwareRoot/cplex/ILOG/CPLEX_Studio2211/cplex/lib/cplex.jar;$softwareRoot/Mosek/11.0/tools/platform/win64x86/bin/mosek.jar"
$env:PATH="$softwareRoot/cplex/ILOG/CPLEX_Studio2211/cplex/bin/x64_win64;$softwareRoot/Mosek/11.0/tools/platform/win64x86/bin;"+$env:PATH
if([string]::IsNullOrWhiteSpace($WeeklyCsv)){$WeeklyCsv=(Get-ChildItem analysis -Filter '*.csv' -Recurse|Where-Object{$_.Name-match'23OD'-and$_.Name-match'10'}|Sort-Object{$_.FullName.Length}|Select-Object -First 1).FullName}
$gate='Test.analysis.synthetic.TRBReviewerOlistDynamicDgpV2Gate';$culture=[Globalization.CultureInfo]::InvariantCulture
function F([double]$x){$x.ToString('0.################',$culture)}
function Run([string]$root,[int]$paths,[int]$draws,[int]$market,[long]$seed,[double]$c,[double]$lambda,[string]$methods){
  $result=Join-Path $root 'query_results.csv';if(Test-Path $result){$r=@(Import-Csv $result);if($r.Count-ne$paths*$methods.Split(',').Count-or@($r|Where-Object certifiedOptimal-ne'true').Count-ne0){throw "Invalid existing result $root"};return}
  if((Test-Path $root)-and@(Get-ChildItem $root -Force).Count-gt0){throw "Partial output $root"}
  &$java -cp $cp $gate $WeeklyCsv $root 23 30 50 $paths $draws .5 $market lognormal .345 (F $c) $seed .5 (F $lambda) $methods 0 0 gaussian
  if($LASTEXITCODE-ne0){throw "Gate failure ${LASTEXITCODE}: $root"}
}
New-Item -ItemType Directory -Force $OutputRoot|Out-Null
$calMarkets=@(90,91,92);$stage1=@()
foreach($c in @(.5,1,2)){foreach($m in $calMarkets){$root=Join-Path $OutputRoot ("calibration/stage1_ch_{0}/market_{1}"-f((F $c)-replace'\.','_'),$m);Run $root 3 200 $m (900000L+10000L*($m-90)) $c 1 'CSAA';foreach($r in Import-Csv (Join-Path $root 'query_results.csv')){$stage1+=[pscustomobject]@{C_h=$c;market=$m;path=$r.pathIndex;mean=[double]$r.meanOosCost;gap=[double]$r.relativeGap}}}}
$s1=@($stage1|Group-Object C_h|ForEach-Object{[pscustomobject]@{C_h=[double]$_.Name;queries=$_.Count;meanCalibrationOosCost=($_.Group|Measure-Object mean -Average).Average;maxGap=($_.Group|Measure-Object gap -Maximum).Maximum}}|Sort-Object meanCalibrationOosCost,C_h);$s1|Export-Csv -NoTypeInformation -Encoding utf8 (Join-Path $OutputRoot 'stage1_c_h_candidates.csv');$bestC=[double]$s1[0].C_h
$stage2=@();foreach($l in @(.1,.5,1,2,5,10,50,100)){foreach($m in $calMarkets){$root=Join-Path $OutputRoot ("calibration/stage2_lambda_{0}/market_{1}"-f((F $l)-replace'\.','_'),$m);Run $root 3 200 $m (900000L+10000L*($m-90)) $bestC $l 'DRO';foreach($r in Import-Csv (Join-Path $root 'query_results.csv')){$stage2+=[pscustomobject]@{lambda=$l;market=$m;path=$r.pathIndex;mean=[double]$r.meanOosCost;sd=[double]$r.sdOosCost;q95=[double]$r.q95OosCost;cvar95=[double]$r.cvar95OosCost;gap=[double]$r.relativeGap}}}}
$s2=@($stage2|Group-Object lambda|ForEach-Object{[pscustomobject]@{lambda=[double]$_.Name;queries=$_.Count;meanCalibrationOosCost=($_.Group|Measure-Object mean -Average).Average;meanConditionalSd=($_.Group|Measure-Object sd -Average).Average;meanConditionalQ95=($_.Group|Measure-Object q95 -Average).Average;meanConditionalCvar95=($_.Group|Measure-Object cvar95 -Average).Average;maxGap=($_.Group|Measure-Object gap -Maximum).Maximum}}|Sort-Object meanCalibrationOosCost,lambda);$s2|Export-Csv -NoTypeInformation -Encoding utf8 (Join-Path $OutputRoot 'stage2_lambda_candidates.csv');$bestL=[double]$s2[0].lambda
foreach($m in 0..2){Run (Join-Path $OutputRoot "evaluation/market_$m") 10 500 $m (40000L+10000L*$m) $bestC $bestL 'CSAA,DRO'}
[pscustomobject]@{kernel='GAUSSIAN';selectionCriterion='minimum mean conditional OOS cost on independent calibration markets';calibrationMarkets='90,91,92';calibrationPathsPerMarket=3;evaluationMarkets='0,1,2';evaluationPathsPerMarket=10;J=23;I=30;S=50;distribution='LOGNORMAL';innovationCv=.345;coverage=.5;hRule='min eligible r_ij';mqcScale=1;rho=.5;selectedC_h=$bestC;selectedLambda=$bestL}|Export-Csv -NoTypeInformation -Encoding utf8 (Join-Path $OutputRoot 'selected_parameters_and_protocol.csv')
"Selected Gaussian C_h=$(F $bestC); lambda=$(F $bestL)"
