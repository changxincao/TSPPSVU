@echo off
setlocal
cd /d "%~dp0.."
set "JAVA_EXE=D:\??\Java\jdk_22\bin\java.exe"
set "CPLEX_BIN=D:\??\cplex\ILOG\CPLEX_Studio2211\cplex\bin\x64_win64"
set "CP=bin;D:\??\cplex\ILOG\CPLEX_Studio2211\cplex\lib\cplex.jar;D:\??\Mosek\11.0\tools\platform\win64x86\bin\mosek.jar"
set "IN_CSV=analysis\??????\??\????????OD???_??.csv"
set "OUT_DIR=analysis\purchase_raw_cv_10sup_full"
if not exist "%OUT_DIR%" mkdir "%OUT_DIR%"
echo [%date% %time%] launch> "%OUT_DIR%\run_stdout.log"
echo.> "%OUT_DIR%\run_stderr.log"
"%JAVA_EXE%" -Djava.library.path="%CPLEX_BIN%" -cp "%CP%" Test.BrazilOlistAdaptiveCVSolveComparison "%IN_CSV%" 10 50 "%OUT_DIR%" >> "%OUT_DIR%\run_stdout.log" 2>> "%OUT_DIR%\run_stderr.log"
echo [%date% %time%] exit_code=%errorlevel%>> "%OUT_DIR%\run_stdout.log"
endlocal
