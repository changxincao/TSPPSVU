param(
    [string]$Instance = 'tiny',
    [string]$Output = 'analysis/rcsaa_upper_20260909/replay',
    [double]$Lambda = 3,
    [int]$Seconds = 90,
    [ValidateSet('all','old_exact','repair_exact','old_search','repair_search','compact','compact_anchors','compact_switched','compact_switched_anchors','compact_family')]
    [string]$Method = 'all'
)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$compileOut = 'tmp/rcsaa_upper_20260909/classes'
$solverLibs = 'D:/软件/cplex/ILOG/CPLEX_Studio2211/cplex/lib/cplex.jar;D:/软件/Mosek/11.0/tools/platform/win64x86/bin/mosek.jar'
$nativeLibs = 'D:/软件/cplex/ILOG/CPLEX_Studio2211/cplex/bin/x64_win64;D:/软件/Mosek/11.0/tools/platform/win64x86/bin'
$classPath = "$compileOut;bin;$solverLibs"
$sourceFiles = @(
    'src/Helper/basicHelper/Config.java',
    'src/Model/SecondStageEvaluator.java',
    'src/Model/RCSAAUpperReformulation.java',
    'src/Model/RCSAALBBDPrimalExactSolver.java',
    'src/Model/RCSAALBBDPrimalSearchSolver.java',
    'src/Model/DROModel.java',
    'src/Model/RCSAAUpperReformulationTest.java'
)
& javac -encoding UTF-8 -cp $classPath -sourcepath src -d $compileOut @sourceFiles
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
& java "-Djava.library.path=$nativeLibs" -cp $classPath Model.RCSAAUpperReformulationTest $Instance $Output $Lambda $Seconds $Method
if ($LASTEXITCODE -ne 0) { throw 'Validation failed; inspect the result CSV and console log' }
