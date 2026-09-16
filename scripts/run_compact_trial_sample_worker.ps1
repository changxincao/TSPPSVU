param([ValidateSet('repair_search','compact','switched')][string]$Method)
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$classPath='tmp/rcsaa_upper_20260909/classes;bin;D:/软件/cplex/ILOG/CPLEX_Studio2211/cplex/lib/cplex.jar;D:/软件/Mosek/11.0/tools/platform/win64x86/bin/mosek.jar'
$native='D:/软件/cplex/ILOG/CPLEX_Studio2211/cplex/bin/x64_win64;D:/软件/Mosek/11.0/tools/platform/win64x86/bin'
$inputCsv='analysis/巴西数据分析/新版_purchase时间/输入/聚合需求表_日度与周度/按purchase时间_五大区23OD_周度宽表_10供应商实验输入.csv'
# One worker per method, each solve single-threaded. Match by period, not rolling index.
foreach($n in @(25,50)) {
 foreach($case in @(@{trial=17;period=70;k=1;c=0.1},@{trial=21;period=74;k=3;c=0.5},@{trial=32;period=85;k=3;c=1.0})) {
  $out="analysis/rcsaa_upper_20260909/multitrial_samples/trial$($case.trial)_S${n}_${Method}"
  & java "-Djava.library.path=$native" -cp $classPath Model.OlistCompactLongComparison $inputCsv $out $case.period true 1800 $Method $case.k $case.c $n
  if($LASTEXITCODE -ne 0){throw "Worker failed: $out"}
 }
}
