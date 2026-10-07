param([switch]$CheckOnly)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path $PSScriptRoot -Parent
$sourceRoot = Join-Path $workspace 'analysis_runs\main_w1_full_cv_local_20261006'
$taskRoot = Join-Path $workspace 'analysis_runs\w1_linf_monotone_probe_20261007'
$cvRoot = Join-Path $taskRoot 'cv'
$taskClasses = Join-Path $cvRoot 'classes'
$taskSoftware = Split-Path (Split-Path (Split-Path $workspace -Parent) -Parent) -Parent
$taskCplex = Join-Path $taskSoftware 'cplex\ILOG\CPLEX_Studio2211\cplex'
$javaPath = Join-Path $taskSoftware 'Java\jdk_22\bin\java.exe'
$javacPath = Join-Path $taskSoftware 'Java\jdk_22\bin\javac.exe'
$pythonPath = Join-Path $workspace '.venv-rsome\Scripts\python.exe'
$classpath = "$taskClasses;$(Join-Path $taskRoot 'classes');$(Join-Path $sourceRoot 'deployment\bin');$taskCplex\lib\cplex.jar;$(Join-Path $taskSoftware 'Mosek\11.0\tools\platform\win64x86\bin\mosek.jar')"
$active = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {
    $_.CommandLine -like '*w1_linf_monotone_probe_20261007*' -and
    ($_.CommandLine -like '*TRBSVUWassersteinInfinityProbe*' -or $_.CommandLine -like '*TRBSVUWassersteinInfinityCvMain*')
})
if ($active.Count -gt 0) { throw 'Existing local Linf tasks are active; refusing duplicate launch or class overwrite' }
foreach ($required in @($javaPath,$javacPath,$pythonPath,"$taskCplex\bin\x64_win64\cplex2211.dll")) {
    if (!(Test-Path -LiteralPath $required)) { throw "Required runtime missing: $required" }
}
New-Item -ItemType Directory -Force -Path $taskClasses | Out-Null
$sources = @('TRBSVUWassersteinInfinityCvMain.java','TRBSVUWassersteinInfinityCvSelfCheck.java',
    'TRBSVUForestWeights.java') | ForEach-Object {
    Join-Path $workspace "src\Test\analysis\synthetic\$_"
}
function Invoke-JavaCheck([string[]]$JavaArguments,[string]$LogPath) {
    # Windows PowerShell treats benign native stderr as errors under Stop; use the exit code instead.
    $ErrorActionPreference = 'Continue'
    & $javaPath @JavaArguments > $LogPath 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Java check failed; see $LogPath" }
}
Push-Location $workspace
try {
    & $javacPath --release 21 -encoding UTF-8 -cp $classpath -d $taskClasses @sources
    if ($LASTEXITCODE -ne 0) { throw 'CV driver compilation failed' }
    Invoke-JavaCheck @('-cp',$classpath,'Test.analysis.synthetic.TRBSVUWassersteinInfinityCvSelfCheck') "$cvRoot\selfcheck.log"
    Invoke-JavaCheck @("-Djava.library.path=$taskCplex\bin\x64_win64",'-cp',$classpath,'Model.WassersteinInfinitySelfCheck') "$cvRoot\native_selfcheck.log"
    Invoke-JavaCheck @("-Djava.library.path=$taskCplex\bin\x64_win64","-Dtrb.svu.python=$pythonPath",'-cp',$classpath,
        'Test.analysis.synthetic.TRBSVUWassersteinInfinityCvMain','preflight',$sourceRoot,$taskRoot,'0') "$cvRoot\preflight.log"
    if ($CheckOnly) { Get-Content -LiteralPath "$cvRoot\preflight.log"; return }
    $env:OMP_NUM_THREADS='1'; $env:MKL_NUM_THREADS='1'; $env:OPENBLAS_NUM_THREADS='1'
    $stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
    $workers = foreach ($shard in 0,1) {
        $stdout = Join-Path $cvRoot "worker_$shard.$stamp.stdout.log"
        $stderr = Join-Path $cvRoot "worker_$shard.$stamp.stderr.log"
        $arguments = @('-Xmx3g',"`"-Djava.library.path=$taskCplex\bin\x64_win64`"",
            "`"-Dtrb.svu.python=$pythonPath`"",'-cp',"`"$classpath`"",
            'Test.analysis.synthetic.TRBSVUWassersteinInfinityCvMain','worker',
            "`"$sourceRoot`"","`"$taskRoot`"","$shard")
        $p = Start-Process -FilePath $javaPath -ArgumentList $arguments -WorkingDirectory $workspace -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput $stdout -RedirectStandardError $stderr
        [pscustomobject]@{shard=$shard;pid=$p.Id;started=(Get-Date -Format o);threads=4;heap='3g';
            marketAssignment=if($shard -eq 0){'001,003,005'}else{'002,004'};stdout=$stdout;stderr=$stderr}
    }
    $workers | Export-Csv -NoTypeInformation -Encoding UTF8 -LiteralPath "$cvRoot\workers.csv"
    $workers | Format-Table -AutoSize
} finally { Pop-Location }
