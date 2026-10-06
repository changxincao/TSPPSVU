param([Parameter(Mandatory=$true)][string]$Root,
      [Parameter(Mandatory=$true)][string]$Java,
      [Parameter(Mandatory=$true)][string]$CplexRoot,
      [Parameter(Mandatory=$true)][string]$MosekRoot,
      [Parameter(Mandatory=$true)][string]$Python,
      [switch]$CheckOnly)
$ErrorActionPreference='Stop';$ProgressPreference='SilentlyContinue'
. (Join-Path $PSScriptRoot 'experiment_file_io.ps1')
$Root=[IO.Path]::GetFullPath($Root)
$deployment=Join-Path $Root 'deployment';$control=Join-Path $Root 'control'
$classpath="$Root\cleanup_patch\classes;$deployment\bin;$CplexRoot\lib\cplex.jar;$MosekRoot\mosek.jar"
$grid='0.0001,0.00025,0.0005,0.001,0.0025,0.005'
function BatchWorkers {
    @(Get-CimInstance Win32_Process -Filter "name='java.exe'"|Where-Object {
        $_.CommandLine-and$_.CommandLine.Contains($Root)-and$_.CommandLine-match '(?<!\S)"?--worker"?(?!\S)'
    })
}
function WorkerArgs([int]$Rep,[string]$Mode) {
    $name='rep_{0:D3}'-f$Rep
    @('-Xmx3g',"-Dtrb.svu.python=$Python","-Djava.library.path=$CplexRoot\bin\x64_win64",'-cp',$classpath,
      'Test.analysis.synthetic.TRBSVUExperiment2IdeMain',$Mode,"$Root\inputs\main_input\$name",
      "$Root\frozen_rf\$name\validation\experiment1_selected_context.csv",
      "$Root\results\primary\C-W1\$name","$Rep",'4','0','PRIMARY','C-W1',$grid)
}
function QuotedArgs($Values) { @($Values|ForEach-Object{'"'+$_+'"'})-join' ' }
function Event([string]$Type,[int]$Rep,[string]$Message) {
    try {
        [pscustomobject]@{time=(Get-Date -Format o);type=$Type;rep=$Rep;message=$Message}|
            Export-Csv "$control\recovery_events.csv" -Append -NoTypeInformation -Encoding UTF8
    }catch{Write-Warning "Event write unavailable: $($_.Exception.Message)"}
    Write-Host "$Type rep=$Rep $Message"
}
function Audit([int]$Rep) {
    $name='rep_{0:D3}'-f$Rep
    if(-not(Test-Path "$Root\results\primary\C-W1\$name\complete.txt")){return 1}
    $p=Start-Process -FilePath $Java -ArgumentList (QuotedArgs (WorkerArgs $Rep '--check-complete')) `
        -WorkingDirectory $deployment -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput "$control\audit_$name.stdout.log" -RedirectStandardError "$control\audit_$name.stderr.log"
    $handle=$p.Handle;$p.WaitForExit();$code=$p.ExitCode;$p.Dispose();return $code
}
foreach($file in @($Java,$Python,"$Root\cleanup_patch\classes\Test\analysis\synthetic\TRBSVUForestWeights.class")){
    if(-not(Test-Path -LiteralPath $file)){throw "Missing runtime $file"}
}
foreach($rep in 1..5){
    $name='rep_{0:D3}'-f$rep
    foreach($file in @("$Root\inputs\main_input\$name\queries\queries.tsv","$Root\frozen_rf\$name\validation\experiment1_selected_context.csv")){
        if(-not(Test-Path -LiteralPath $file)){throw "Missing frozen input $file"}
    }
}
if($CheckOnly){Write-Output 'LOCAL_QUEUE_CHECK_PASS max2 threads4 unchanged grid/inputs; audited completion skip';return}
$lock=[IO.File]::Open("$control\recovery.lock",'OpenOrCreate','ReadWrite','None')
try {
    $env:PATH="$CplexRoot\bin\x64_win64;$MosekRoot;$env:PATH"
    $env:OMP_NUM_THREADS='1';$env:MKL_NUM_THREADS='1';$env:OPENBLAS_NUM_THREADS='1'
    $jobs=@(foreach($rep in 1..5){
        $name='rep_{0:D3}'-f$rep
        $previousAttempts=@(Get-ChildItem "$Root\results\primary\C-W1\$name" -Filter 'task.recovery_*.log' -ErrorAction SilentlyContinue|
            ForEach-Object{if($_.Name-match'recovery_(\d+)\.log$'){[int]$Matches[1]}}|Measure-Object -Maximum).Maximum
        $job=[pscustomobject]@{rep=$rep;state='QUEUED';pid=0;created=$null;attempt=[int]$previousAttempts;failures=0}
        $expected=QuotedArgs (WorkerArgs $rep '--worker')
        $live=@(BatchWorkers|Where-Object{$_.CommandLine.TrimEnd().EndsWith(' '+$expected,[StringComparison]::Ordinal)})
        if($live.Count-gt1){throw "Duplicate workers for $name"}
        if($live.Count){$job.state='RUNNING';$job.pid=$live[0].ProcessId;$job.created=$live[0].CreationDate;Event 'ADOPTED' $rep "pid=$($job.pid)"}
        else {
            $code=Audit $rep
            if($code-eq0){$job.state='COMPLETE';Event 'COMPLETE' $rep 'Matching protocol and all 40 query/OOS outputs audited; no solve'}
            elseif($code-eq20){$job.state='BLOCKED';Event 'BLOCKED' $rep 'Protocol mismatch; preserved outputs'}
        }
        $job
    })
    $adopted=@($jobs|Where-Object state -eq RUNNING)
    if(@(BatchWorkers).Count-ne$adopted.Count-or$adopted.Count-gt2){throw 'Unadopted workers or more than two slots; refusing launch'}
    while(@($jobs|Where-Object {$_.state-in@('RUNNING','QUEUED')}).Count){
        $live=@(BatchWorkers)
        foreach($job in $jobs|Where-Object state -eq RUNNING){
            if(@($live|Where-Object{$_.ProcessId-eq$job.pid-and$_.CreationDate-eq$job.created}).Count){continue}
            $code=Audit $job.rep
            if($code-eq0){$job.state='COMPLETE';Event 'COMPLETE' $job.rep 'All query outputs audited'}
            elseif($code-eq20){$job.state='BLOCKED';Event 'BLOCKED' $job.rep 'Protocol mismatch; no overwrite'}
            else{$job.failures++;$job.state=if($job.failures-lt3){'QUEUED'}else{'FAILED'};Event $job.state $job.rep "Incomplete/failed; preserved checkpoints; failures=$($job.failures)"}
            $job.pid=0;$job.created=$null
        }
        foreach($job in $jobs|Where-Object state -eq QUEUED){
            if(@(BatchWorkers).Count-ge2){break}
            $name='rep_{0:D3}'-f$job.rep;$dir="$Root\results\primary\C-W1\$name"
            [void][IO.Directory]::CreateDirectory($dir)
            do{$job.attempt++}while(Test-Path "$dir\task.recovery_$($job.attempt).log")
            try{
                $p=Start-Process -FilePath $Java -ArgumentList (QuotedArgs (WorkerArgs $job.rep '--worker')) `
                    -WorkingDirectory $deployment -WindowStyle Hidden -PassThru `
                    -RedirectStandardOutput "$dir\task.recovery_$($job.attempt).log" -RedirectStandardError "$dir\task.recovery_$($job.attempt).stderr.log"
                $handle=$p.Handle;$job.pid=$p.Id;$identity=Get-CimInstance Win32_Process -Filter "ProcessId=$($p.Id)"
                $job.created=if($identity){$identity.CreationDate}else{$null};$job.state='RUNNING';$p.Dispose()
                Event 'START' $job.rep "pid=$($job.pid); resume checkpoints, max2 threads4"
            }catch{$job.state='FAILED';Event 'START_FAILED' $job.rep $_.Exception.Message}
        }
        [void](Write-ExperimentJson ([pscustomobject]@{updated=(Get-Date -Format o);maxParallel=2;solverThreads=4;jobs=$jobs}) "$control\recovery_status.json")
        if(@($jobs|Where-Object {$_.state-in@('RUNNING','QUEUED')}).Count){Start-Sleep -Seconds 10}
    }
    Event 'FINISHED' 0 'All tasks terminal; no completed tasks re-solved'
}finally{$lock.Dispose()}
