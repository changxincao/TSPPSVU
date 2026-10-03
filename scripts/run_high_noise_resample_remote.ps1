param([Parameter(Mandatory=$true)][string]$Root,
      [Parameter(Mandatory=$true)][string]$TaskRoot,
      [Parameter(Mandatory=$true)][string]$Java,
      [Parameter(Mandatory=$true)][string]$Python,
      [Parameter(Mandatory=$true)][string]$CplexRoot,
      [Parameter(Mandatory=$true)][string]$License,
      [Parameter(Mandatory=$true)][string]$OriginalStatus)
$ErrorActionPreference='Stop';$ProgressPreference='SilentlyContinue'
. (Join-Path $TaskRoot 'scripts\olist_windows_process.ps1')
$control=Join-Path $Root 'control'
New-Item -ItemType Directory -Force -Path $control | Out-Null
$lock=[IO.File]::Open((Join-Path $control 'scheduler.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
$native="$CplexRoot\bin\x64_win64;$TaskRoot\lib"
$cp="$TaskRoot\bin;$CplexRoot\lib\cplex.jar;$TaskRoot\lib\mosek.jar"
$env:Path="$native;$env:Path";$env:MOSEKLM_LICENSE_FILE=$License
$env:OPENBLAS_NUM_THREADS='1';$env:MKL_NUM_THREADS='1';$env:OMP_NUM_THREADS='1'
Set-Location -LiteralPath $TaskRoot
function Status([string]$state,[int]$rep,[string]$method,[int]$workerPid=0){
    $value=[pscustomobject]@{state=$state;updated=(Get-Date -Format o);rep=$rep;method=$method;workerPid=$workerPid;controllerPid=$PID;solverThreads=4;newTestParallel=1;hostSolverLimit=4;lambdaGrid='0.1,0.25,0.5,1';rfLeafGrid='1,2,5';originalResultsPreserved=$true}
    $temp=Join-Path $control 'status.tmp'
    $value|ConvertTo-Json|Set-Content -LiteralPath $temp -Encoding UTF8
    Move-Item -LiteralPath $temp -Destination (Join-Path $control 'status.json') -Force
}
function Wait-Slot([int]$rep,[string]$method){
    while($true){
        # A three-slot original scheduler reserves the fourth slot for this diagnostic.
        $old=Get-Content -LiteralPath $OriginalStatus -Raw|ConvertFrom-Json
        $workers=@(Get-Process java -ErrorAction SilentlyContinue|Where-Object{
            [OlistWindowsProcess]::CommandLine($_.Id).Contains('Test.analysis.synthetic.TRBSVUExperiment')
        })
        if(([int]$old.parallel -le 3 -or [int]$old.queued -eq 0) -and $workers.Count -lt 4){return}
        Status 'WAITING_HOST_SLOT' $rep $method
        Start-Sleep -Seconds 10
    }
}
function Complete([string]$directory,[bool]$dro){
    if(-not(Test-Path -LiteralPath (Join-Path $directory 'complete.txt'))){return $false}
    foreach($q in 0..39){
        $base=Join-Path $directory ('queries\query_{0:D3}'-f$q)
        $files=if($dro){@('query_metadata.txt','solve\experiment2_final_solves.csv','solve\experiment2_final_weights.csv','oos\experiment2_summary.csv','oos\experiment2_draws.csv')}else{@('query_metadata.txt','solve\final_solve.csv','solve\final_weights.csv','oos\summary.csv','oos\draws.csv','validation\context_candidate.csv')}
        foreach($relative in $files){$file=Join-Path $base $relative;if(-not(Test-Path -LiteralPath $file)-or(Get-Item -LiteralPath $file).Length-eq0){return $false}}
    }
    return $true
}
$failed=[System.Collections.Generic.List[object]]::new()
try{
    if(-not(Test-Path "$Root\cv050070\input\generation_complete.txt")){throw 'Generation audit has not completed'}
    foreach($rep in 1..5){
        $name='rep_{0:D3}'-f$rep;$input=Join-Path $Root "cv050070\input\$name"
        $rf=Join-Path $Root "cv050070\experiment1\$name\RF-CSAA"
        $dro=Join-Path $Root "cv050070\experiment2_fixed_csaa\RF-CSAA\primary\C-Chi2\$name"
        $rfOk=$false
        foreach($method in @('RF-CSAA','C-Chi2')){
            if($method-eq'C-Chi2'-and-not$rfOk){$failed.Add([pscustomobject]@{rep=$rep;method=$method;reason='RF baseline failed'});continue}
            $target=if($method-eq'RF-CSAA'){$rf}else{$dro}
            New-Item -ItemType Directory -Force -Path $target | Out-Null
            $ok=$false
            foreach($attempt in 1..2){
                Wait-Slot $rep $method
                $args=@('-Xmx2g',('"-Djava.library.path='+$native+'"'),('"-Dtrb.svu.python='+$Python+'"'),'-Dtrb.svu.rfLeafGrid=1,2,5','-cp',('"'+$cp+'"'))
                if($method-eq'RF-CSAA'){$args+=@('Test.analysis.synthetic.TRBSVUExperiment1IdeMain','--worker',('"'+$input+'"'),('"'+$target+'"'),$rep,'RF-CSAA',25,4,14400)}else{
                    $selected=Join-Path $rf 'queries\query_000\validation\context_candidate.csv'
                    if(-not(Test-Path -LiteralPath $selected)){throw 'RF selected context missing'}
                    $args+=@('Test.analysis.synthetic.TRBSVUExperiment2IdeMain','--worker',('"'+$input+'"'),('"'+$selected+'"'),('"'+$target+'"'),$rep,4,14400,'PRIMARY','C-Chi2','0.1,0.25,0.5,1')
                }
                $prefix='attempt_{0}_{1}'-f$attempt,(Get-Date -Format yyyyMMdd_HHmmss)
                $p=Start-Process -FilePath $Java -ArgumentList ($args-join' ') -WorkingDirectory $TaskRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $target "$prefix.stdout.log") -RedirectStandardError (Join-Path $target "$prefix.stderr.log")
                $heldHandle=$p.Handle
                Status 'RUNNING' $rep $method $p.Id
                [pscustomobject]@{time=(Get-Date -Format o);rep=$rep;method=$method;attempt=$attempt;pid=$p.Id;exitCode='';state='STARTED'}|Export-Csv -LiteralPath "$control\events.csv" -Append -NoTypeInformation -Encoding UTF8
                $workerPid=$p.Id;$p.WaitForExit();$code=$p.ExitCode;$p.Dispose()
                $ok=$code-eq0-and(Complete $target ($method-eq'C-Chi2'))
                [pscustomobject]@{time=(Get-Date -Format o);rep=$rep;method=$method;attempt=$attempt;pid=$workerPid;exitCode=$code;state=$(if($ok){'COMPLETE'}else{'FAILED'})}|Export-Csv -LiteralPath "$control\events.csv" -Append -NoTypeInformation -Encoding UTF8
                if($ok){break}
            }
            if($method-eq'RF-CSAA'){$rfOk=$ok}
            if(-not$ok){$failed.Add([pscustomobject]@{rep=$rep;method=$method;reason='Worker failed or required outputs incomplete'})}
        }
    }
    @($failed.ToArray())|ConvertTo-Json -Depth 4|Set-Content "$control\failed_tasks.json" -Encoding UTF8
    Status $(if($failed.Count-eq0){'FINISHED'}else{'PARTIAL'}) 5 'ALL'
}catch{
    $_|Out-String|Set-Content "$control\fatal_error.txt" -Encoding UTF8
    Status 'FAILED' 0 'CONTROLLER'
    throw
}finally{$lock.Dispose()}
