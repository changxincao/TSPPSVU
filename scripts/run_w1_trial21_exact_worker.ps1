param(
    [ValidateSet('w1', 'w1_callback_multi')]
    [string]$Method
)

$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)

$classPath = 'tmp/rcsaa_upper_20260910/classes;tmp/rcsaa_upper_20260909/classes;bin;D:/软件/cplex/ILOG/CPLEX_Studio2211/cplex/lib/cplex.jar;D:/软件/Mosek/11.0/tools/platform/win64x86/bin/mosek.jar'
$native = 'D:/软件/cplex/ILOG/CPLEX_Studio2211/cplex/bin/x64_win64;D:/软件/Mosek/11.0/tools/platform/win64x86/bin'
$inputCsv = 'analysis/巴西数据分析/新版_purchase时间/输入/聚合需求表_日度与周度/按purchase时间_五大区23OD_周度宽表_10供应商实验输入.csv'
$label = if ($Method -eq 'w1') { 'w1_ccg_callback_compare' } else { 'w1_callback_multi_compare' }
$output = "analysis/rcsaa_upper_20260910/alternative_trial21/$label"

New-Item -ItemType Directory -Force $output | Out-Null
& java "-Djava.library.path=$native" -cp $classPath Model.OlistCompactLongComparison `
    $inputCsv $output 74 true 1800 $Method 3 0.5 50 30 2>&1 |
    Tee-Object -FilePath "$output/progress.log"
if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne 124) {
    throw "$Method failed with exit code $LASTEXITCODE"
}
