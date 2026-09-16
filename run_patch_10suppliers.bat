@echo off
cd /d d:\软件\eclipse\workspace\TransportationProcurement
set PATH=D:\软件\cplex\ILOG\CPLEX_Studio2211\cplex\bin\x64_win64;D:\软件\Mosek\11.0\tools\platform\win64x86\bin;%PATH%
set JAVA_EXE=C:\Program Files\Common Files\Oracle\Java\javapath\java.exe
set JAVA_CP=bin;D:\软件\cplex\ILOG\CPLEX_Studio2211\cplex\lib\cplex.jar;D:\软件\Mosek\11.0\tools\platform\win64x86\bin\mosek.jar
set DAILY_CSV=analysis/巴西数据分析/新版_purchase时间/按purchase时间_按大区OD聚合_清洗后_日度需求表_千克.csv
set ROOT=analysis/巴西数据分析/新版_purchase时间/按purchase时间_raw网格实验_10供应商

echo Starting RCSAA patch run...
echo ROOT=%ROOT%
echo DAILY_CSV=%DAILY_CSV%

rem Run jobs one by one (to avoid parallel issues)
rem k1=1, C_h=1.00
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 1 "%ROOT%/RCSAA_W50_k1=1_raw_C1.00_lambda0.10" 1 rcsaa raw 0.1
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 1 "%ROOT%/RCSAA_W50_k1=1_raw_C1.00_lambda1.00" 1 rcsaa raw 1
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 1 "%ROOT%/RCSAA_W50_k1=1_raw_C1.00_lambda5.00" 1 rcsaa raw 5
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 1 "%ROOT%/RCSAA_W50_k1=1_raw_C1.00_lambda10.00" 1 rcsaa raw 10

rem k1=1, C_h=2.00
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 1 "%ROOT%/RCSAA_W50_k1=1_raw_C2.00_lambda0.10" 2 rcsaa raw 0.1
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 1 "%ROOT%/RCSAA_W50_k1=1_raw_C2.00_lambda1.00" 2 rcsaa raw 1
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 1 "%ROOT%/RCSAA_W50_k1=1_raw_C2.00_lambda5.00" 2 rcsaa raw 5
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 1 "%ROOT%/RCSAA_W50_k1=1_raw_C2.00_lambda10.00" 2 rcsaa raw 10

rem k1=1, C_h=3.00
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 1 "%ROOT%/RCSAA_W50_k1=1_raw_C3.00_lambda0.10" 3 rcsaa raw 0.1
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 1 "%ROOT%/RCSAA_W50_k1=1_raw_C3.00_lambda1.00" 3 rcsaa raw 1
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 1 "%ROOT%/RCSAA_W50_k1=1_raw_C3.00_lambda5.00" 3 rcsaa raw 5
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 1 "%ROOT%/RCSAA_W50_k1=1_raw_C3.00_lambda10.00" 3 rcsaa raw 10

rem k1=1, C_h=5.00
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 1 "%ROOT%/RCSAA_W50_k1=1_raw_C5.00_lambda0.10" 5 rcsaa raw 0.1
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 1 "%ROOT%/RCSAA_W50_k1=1_raw_C5.00_lambda1.00" 5 rcsaa raw 1
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 1 "%ROOT%/RCSAA_W50_k1=1_raw_C5.00_lambda5.00" 5 rcsaa raw 5
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 1 "%ROOT%/RCSAA_W50_k1=1_raw_C5.00_lambda10.00" 5 rcsaa raw 10

echo Done with k1=1

rem k1=2, C_h=2.00
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 2 "%ROOT%/RCSAA_W50_k1=2_raw_C2.00_lambda0.10" 2 rcsaa raw 0.1
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 2 "%ROOT%/RCSAA_W50_k1=2_raw_C2.00_lambda1.00" 2 rcsaa raw 1
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 2 "%ROOT%/RCSAA_W50_k1=2_raw_C2.00_lambda5.00" 2 rcsaa raw 5
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 2 "%ROOT%/RCSAA_W50_k1=2_raw_C2.00_lambda10.00" 2 rcsaa raw 10

rem k1=2, C_h=3.00
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 2 "%ROOT%/RCSAA_W50_k1=2_raw_C3.00_lambda0.10" 3 rcsaa raw 0.1
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 2 "%ROOT%/RCSAA_W50_k1=2_raw_C3.00_lambda1.00" 3 rcsaa raw 1
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 2 "%ROOT%/RCSAA_W50_k1=2_raw_C3.00_lambda5.00" 3 rcsaa raw 5
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 2 "%ROOT%/RCSAA_W50_k1=2_raw_C3.00_lambda10.00" 3 rcsaa raw 10

rem k1=2, C_h=5.00
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 2 "%ROOT%/RCSAA_W50_k1=2_raw_C5.00_lambda0.10" 5 rcsaa raw 0.1
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 2 "%ROOT%/RCSAA_W50_k1=2_raw_C5.00_lambda1.00" 5 rcsaa raw 1
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 2 "%ROOT%/RCSAA_W50_k1=2_raw_C5.00_lambda5.00" 5 rcsaa raw 5
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 2 "%ROOT%/RCSAA_W50_k1=2_raw_C5.00_lambda10.00" 5 rcsaa raw 10

echo Done with k1=2

rem k1=3, C_h=1.00
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 3 "%ROOT%/RCSAA_W50_k1=3_raw_C1.00_lambda0.10" 1 rcsaa raw 0.1
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 3 "%ROOT%/RCSAA_W50_k1=3_raw_C1.00_lambda1.00" 1 rcsaa raw 1
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 3 "%ROOT%/RCSAA_W50_k1=3_raw_C1.00_lambda5.00" 1 rcsaa raw 5
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 3 "%ROOT%/RCSAA_W50_k1=3_raw_C1.00_lambda10.00" 1 rcsaa raw 10

rem k1=3, C_h=2.00
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 3 "%ROOT%/RCSAA_W50_k1=3_raw_C2.00_lambda0.10" 2 rcsaa raw 0.1
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 3 "%ROOT%/RCSAA_W50_k1=3_raw_C2.00_lambda1.00" 2 rcsaa raw 1
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 3 "%ROOT%/RCSAA_W50_k1=3_raw_C2.00_lambda5.00" 2 rcsaa raw 5
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 3 "%ROOT%/RCSAA_W50_k1=3_raw_C2.00_lambda10.00" 2 rcsaa raw 10

rem k1=3, C_h=3.00
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 3 "%ROOT%/RCSAA_W50_k1=3_raw_C3.00_lambda0.10" 3 rcsaa raw 0.1
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 3 "%ROOT%/RCSAA_W50_k1=3_raw_C3.00_lambda1.00" 3 rcsaa raw 1
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 3 "%ROOT%/RCSAA_W50_k1=3_raw_C3.00_lambda5.00" 3 rcsaa raw 5
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 3 "%ROOT%/RCSAA_W50_k1=3_raw_C3.00_lambda10.00" 3 rcsaa raw 10

rem k1=3, C_h=5.00
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 3 "%ROOT%/RCSAA_W50_k1=3_raw_C5.00_lambda0.10" 5 rcsaa raw 0.1
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 3 "%ROOT%/RCSAA_W50_k1=3_raw_C5.00_lambda1.00" 5 rcsaa raw 1
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 3 "%ROOT%/RCSAA_W50_k1=3_raw_C5.00_lambda5.00" 5 rcsaa raw 5
"%JAVA_EXE%" -cp "%JAVA_CP%" Test.analysis.BrazilOlistThetaModeSolveRunner "%DAILY_CSV%" 10 50 3 "%ROOT%/RCSAA_W50_k1=3_raw_C5.00_lambda10.00" 5 rcsaa raw 10

echo Done with k1=3
echo All done!
