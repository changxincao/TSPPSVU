$log = Join-Path (Get-Location) 'i15_diag.log'
if (Test-Path $log) { Remove-Item $log -Force }
& java '-Djava.library.path=D:\软件\cplex\ILOG\CPLEX_Studio2211\cplex\bin\x64_win64' '-cp' 'bin;src;D:\软件\cplex\ILOG\CPLEX_Studio2211\cplex\lib\cplex.jar;D:\软件\Mosek\11.0\tools\platform\win64x86\bin\mosek.jar' 'Test.BrazilOlistTopParamScenarioComparison' '1' '5' '50' '15' 'I15' 'lbbd_only' *> $log
Write-Host "EXIT=$LASTEXITCODE"
Get-Content $log