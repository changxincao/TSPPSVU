param([ValidateSet('repair_search','switched')][string]$Method)
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$classPath='tmp/rcsaa_upper_20260910/classes;tmp/rcsaa_upper_20260909/classes;bin;D:/软件/cplex/ILOG/CPLEX_Studio2211/cplex/lib/cplex.jar;D:/软件/Mosek/11.0/tools/platform/win64x86/bin/mosek.jar'
$native='D:/软件/cplex/ILOG/CPLEX_Studio2211/cplex/bin/x64_win64;D:/软件/Mosek/11.0/tools/platform/win64x86/bin'
$inputCsv='analysis/巴西数据分析/新版_purchase时间/输入/聚合需求表_日度与周度/按purchase时间_五大区23OD_周度宽表_10供应商实验输入.csv'
# I=30; original capacity/MQC generation ratios without rescaling. S=50, lambda=5.
# Each method queue is single-threaded and matches the same period/k/C/seed.
foreach($case in @(@{trial=17;period=70;k=1;c=0.1},@{trial=21;period=74;k=3;c=0.5},@{trial=32;period=85;k=3;c=1.0})) {
 $out="analysis/rcsaa_upper_20260910/I30/trial$($case.trial)_S50_${Method}"
 & java "-Djava.library.path=$native" -cp $classPath Model.OlistCompactLongComparison $inputCsv $out $case.period true 1800 $Method $case.k $case.c 50 30
 if($LASTEXITCODE -ne 0){throw "Worker failed: $out"}
 if(Select-String -LiteralPath "$out/results.csv" -SimpleMatch ',ERROR,'){throw "Solver error: $out"}
}
