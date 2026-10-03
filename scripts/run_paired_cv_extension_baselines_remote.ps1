param([Parameter(Mandatory=$true)][string]$TaskRoot,[Parameter(Mandatory=$true)][string]$ExperimentRoot,[Parameter(Mandatory=$true)][ValidateSet('cv030050','cv040060','cv010030','cv050070')][string]$Cell,[long]$FirstSeed=20261025,[ValidateRange(1,100)][int]$ReplicationCount=5,[ValidateRange(1,4)][int]$MaxParallel=4,[switch]$SkipCompleted,[switch]$DryRun)
$ErrorActionPreference='Stop';$control=Join-Path $ExperimentRoot "control\baseline_$Cell";New-Item -ItemType Directory -Force $control|Out-Null
$java=Join-Path $TaskRoot 'runtime\java\bin\java.exe';$python=Join-Path $TaskRoot 'runtime\python\python.exe';$native='E:\EnglishSave\Cplex22\cplex\bin\x64_win64';$cp="$TaskRoot\bin;E:\EnglishSave\Cplex22\cplex\lib\cplex.jar;$TaskRoot\lib\mosek.jar";$env:Path="$native;$env:Path";$env:OPENBLAS_NUM_THREADS=1;$env:MKL_NUM_THREADS=1;$env:OMP_NUM_THREADS=1;Set-Location $TaskRoot
$cv=@{cv030050=@(.3,.5);cv040060=@(.4,.6);cv010030=@(.1,.3);cv050070=@(.5,.7)}[$Cell];foreach($r in 0..($ReplicationCount-1)){$m=Get-Content (Join-Path $ExperimentRoot ("$Cell\input\rep_{0:D3}\instance\manifest.txt"-f$r));if($m-notcontains"caseSeed=$([long]$FirstSeed+$r)"-or$m-notcontains"cvLower=$($cv[0])"-or$m-notcontains"cvUpper=$($cv[1])"-or$m-notcontains'queries=40'-or$m-notcontains'OOS=1000'){throw "Input mismatch rep$r"}}
if($DryRun){[pscustomobject]@{cell=$Cell;replications=$ReplicationCount;methods=5;tasks=5*$ReplicationCount;validation_origins=25;queries_per_task=40;oos_per_query=1000;max_parallel=$MaxParallel;solver_threads=4};return}
. (Join-Path $PSScriptRoot 'olist_windows_process.ps1')
$schedulerLock=[IO.File]::Open((Join-Path $control 'scheduler.lock'),'OpenOrCreate','ReadWrite','None')
try {
function Complete($d){if(-not(Test-Path (Join-Path $d 'complete.txt'))){return $false};foreach($q in 0..39){$x=Join-Path $d ('queries\query_{0:D3}'-f$q);foreach($f in @('query_metadata.txt','validation\summary.csv','validation\details.csv','solve\final_solve.csv','solve\final_weights.csv','oos\summary.csv','oos\draws.csv')){$p=Join-Path $x $f;if(-not(Test-Path $p)-or(Get-Item $p).Length-eq0){return $false}}};return $true}
$queue=[Collections.Generic.Queue[object]]::new();$running=[Collections.Generic.List[object]]::new();$failed=[Collections.Generic.List[object]]::new();$events=Join-Path $control 'events.csv';function Event($t,$s,$c){[pscustomobject]@{time=[DateTime]::Now.ToString('o');cell=$Cell;rep=$t.rep;method=$t.method;attempt=$t.attempt;state=$s;exitCode=$c}|Export-Csv $events -Append -NoTypeInformation -Encoding UTF8}
function Write-JsonStatus($value){$json=$value|ConvertTo-Json;for($attempt=1;$attempt-le40;$attempt++){try{Set-Content -LiteralPath (Join-Path $control 'status.json') -Value $json -Encoding UTF8 -ErrorAction Stop;return}catch [System.IO.IOException]{if($attempt-eq40){throw};Start-Sleep -Milliseconds 250}}}
function Worker-Arguments($Task){
    $input=Join-Path $ExperimentRoot ("$Cell\input\rep_{0:D3}"-f$Task.rep)
    $grid=if($Task.method-eq'CSAA-Tri'){'0.8,0.9,1,2'}else{'0.1,0.25,0.5,0.8'}
    return @('-Xmx2g',"-Djava.library.path=$native","-Dtrb.svu.python=$python", "-Dtrb.svu.bandwidthGrid=$grid",'-Dtrb.svu.rfLeafGrid=1,2,5','-cp',$cp,'Test.analysis.synthetic.TRBSVUExperiment1IdeMain','--worker',$input,$Task.target,[string]$Task.rep,$Task.method,'25','4','14400')
}
function Worker-CommandMatches($Command,$Task){
    $tokens=@([regex]::Matches($Command,'"[^"]*"|\S+')|ForEach-Object {$_.Value.Trim('"')})
    $expected=@(Worker-Arguments $Task)
    if($tokens.Count-ne($expected.Count+1)-or$tokens[0]-ne$java){return $false}
    for($index=0;$index-lt$expected.Count;$index++){if($tokens[$index+1]-cne$expected[$index]){return $false}}
    return $true
}
foreach($r in 0..($ReplicationCount-1)){
    foreach($m in @('CSAA-Exp','CSAA-Tri','RF-CSAA','SAA-All','D')){
        $target=Join-Path $ExperimentRoot ("$Cell\experiment1\rep_{0:D3}\{1}"-f$r,$m)
        $task=[pscustomobject]@{rep=$r;method=$m;attempt=0;target=$target}
        $active=@(Get-Process java -ErrorAction SilentlyContinue|Where-Object {
            $_.Path -eq $java -and ([OlistWindowsProcess]::CommandLine($_.Id)).Contains('"'+$target+'"')
        })
        if($active.Count -gt 1){throw "Duplicate baseline workers for $target"}
        if($active.Count -eq 1){
            $command=[OlistWindowsProcess]::CommandLine($active[0].Id)
            if(!(Worker-CommandMatches $command $task)){throw "Worker protocol mismatch for $target; existing process left untouched"}
            $task.attempt=1;$heldHandle=$active[0].Handle
            $running.Add([pscustomobject]@{task=$task;process=$active[0]});Event $task ADOPTED $active[0].Id
        }else{
            # Java checks the current protocol, hashes and full OOS rows; a valid
            # complete task exits immediately without any solver invocation.
            $queue.Enqueue($task)
        }
    }
}
while($queue.Count-or$running.Count){while($queue.Count-and$running.Count-lt$MaxParallel){$t=$queue.Dequeue();$t.attempt++;New-Item -ItemType Directory -Force $t.target|Out-Null;$input=Join-Path $ExperimentRoot ("$Cell\input\rep_{0:D3}"-f$t.rep);$grid=if($t.method-eq'CSAA-Tri'){'0.8,0.9,1,2'}else{'0.1,0.25,0.5,0.8'};$args=@('-Xmx2g',('"-Djava.library.path='+$native+'"'),('"-Dtrb.svu.python='+$python+'"'),('-Dtrb.svu.bandwidthGrid='+$grid),'-Dtrb.svu.rfLeafGrid=1,2,5','-cp',('"'+$cp+'"'),'Test.analysis.synthetic.TRBSVUExperiment1IdeMain','--worker',('"'+$input+'"'),('"'+$t.target+'"'),$t.rep,$t.method,25,4,14400);try{$p=Start-Process $java -ArgumentList($args-join' ') -WorkingDirectory $TaskRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput(Join-Path $t.target "attempt_$($t.attempt).stdout.log") -RedirectStandardError(Join-Path $t.target "attempt_$($t.attempt).stderr.log");$h=$p.Handle;$running.Add([pscustomobject]@{task=$t;process=$p});Event $t STARTED ''}catch{Event $t START_FAILED $_.Exception.Message;if($t.attempt-lt2){$queue.Enqueue($t)}else{$failed.Add($t)}}};Start-Sleep 10;foreach($x in @($running.ToArray())){if(-not$x.process.HasExited){continue};$x.process.WaitForExit();$c=$x.process.ExitCode;if($c-eq0-and(Complete $x.task.target)){Event $x.task COMPLETE $c}else{Event $x.task FAILED $c;if($x.task.attempt-lt2){$queue.Enqueue($x.task)}else{$failed.Add($x.task)}};[void]$running.Remove($x);$x.process.Dispose()};Write-JsonStatus ([pscustomobject]@{state='RUNNING';updated=[DateTime]::Now.ToString('o');cell=$Cell;queued=$queue.Count;running=$running.Count;failed=$failed.Count;parallel=$MaxParallel;solverThreads=4})}
$failed|ConvertTo-Json -Depth 4|Set-Content (Join-Path $control 'failed_tasks.json') -Encoding UTF8;$state=if($failed.Count){'PARTIAL'}else{'FINISHED'};Write-JsonStatus ([pscustomobject]@{state=$state;ended=[DateTime]::Now.ToString('o');cell=$Cell;failed=$failed.Count;running=0;queued=0});if($failed.Count){exit 2}
} finally { $schedulerLock.Dispose() }
