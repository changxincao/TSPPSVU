@echo off
setlocal
cd /d D:\软件\eclipse\workspace\TransportationProcurement

set "PATH=D:\软件\cplex\ILOG\CPLEX_Studio2211\cplex\bin\x64_win64;D:\软件\Mosek\11.0\tools\platform\win64x86\bin;%PATH%"
set "JAVA_EXE=C:\Program Files\Common Files\Oracle\Java\javapath\java.exe"
set "JAVA_CP=bin;D:\软件\cplex\ILOG\CPLEX_Studio2211\cplex\lib\cplex.jar;D:\软件\Mosek\11.0\tools\platform\win64x86\bin\mosek.jar"
set "DAILY_CSV=D:\软件\eclipse\workspace\TransportationProcurement\analysis\巴西数据分析\新版_purchase时间\输入\聚合需求表_日度与周度\按purchase时间_按大区OD聚合_清洗后_日度需求表_千克.csv"
set "EXP_ROOT=D:\软件\eclipse\workspace\TransportationProcurement\analysis\巴西数据分析\新版_purchase时间\输出\03_10供应商基础网格与提取\按purchase时间_raw网格实验_10供应商"
set "LOG_ROOT=%EXP_ROOT%\runlog_backfill_k1_c05_remaining4"

if not exist "%LOG_ROOT%" mkdir "%LOG_ROOT%"
echo [%date% %time%] RUN_START>>"%LOG_ROOT%\state.txt"

for %%L in (0.10 1.00 5.00 10.00) do (
  if exist "%EXP_ROOT%\RCSAA_W50_k1=1_raw_C0.50_lambda%%L" rmdir /s /q "%EXP_ROOT%\RCSAA_W50_k1=1_raw_C0.50_lambda%%L"
)

start "rcsaa_c05_l010" /b "%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 1 "%EXP_ROOT%\RCSAA_W50_k1=1_raw_C0.50_lambda0.10" 0.50 rcsaa raw 0.10 1>"%LOG_ROOT%\log_k1_C0.50_L0.10_out.txt" 2>"%LOG_ROOT%\log_k1_C0.50_L0.10_err.txt"
start "rcsaa_c05_l100" /b "%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 1 "%EXP_ROOT%\RCSAA_W50_k1=1_raw_C0.50_lambda1.00" 0.50 rcsaa raw 1.00 1>"%LOG_ROOT%\log_k1_C0.50_L1.00_out.txt" 2>"%LOG_ROOT%\log_k1_C0.50_L1.00_err.txt"
start "rcsaa_c05_l500" /b "%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 1 "%EXP_ROOT%\RCSAA_W50_k1=1_raw_C0.50_lambda5.00" 0.50 rcsaa raw 5.00 1>"%LOG_ROOT%\log_k1_C0.50_L5.00_out.txt" 2>"%LOG_ROOT%\log_k1_C0.50_L5.00_err.txt"
start "rcsaa_c05_l1000" /b "%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 1 "%EXP_ROOT%\RCSAA_W50_k1=1_raw_C0.50_lambda10.00" 0.50 rcsaa raw 10.00 1>"%LOG_ROOT%\log_k1_C0.50_L10.00_out.txt" 2>"%LOG_ROOT%\log_k1_C0.50_L10.00_err.txt"

echo [%date% %time%] STARTED_4_JOBS>>"%LOG_ROOT%\state.txt"
endlocal
