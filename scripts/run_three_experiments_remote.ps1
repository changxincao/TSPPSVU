param([Parameter(Mandatory=$true)][string]$Manifest,[switch]$CheckOnly)
$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'
$plan=Get-Content -LiteralPath $Manifest -Raw | ConvertFrom-Json
$control=Split-Path $Manifest -Parent
$lock=[IO.File]::Open((Join-Path $control 'scheduler.lock'),'OpenOrCreate','ReadWrite','None')
. (Join-Path $plan.deployment 'scripts\olist_windows_process.ps1')
$env:PATH="$($plan.native);$($plan.deployment)\lib;$env:PATH"
$env:MOSEKLM_LICENSE_FILE=$plan.license
$env:OPENBLAS_NUM_THREADS='1';$env:MKL_NUM_THREADS='1';$env:OMP_NUM_THREADS='1'
Set-Location -LiteralPath $plan.deployment
$classpath="$($plan.deployment)\bin;$($plan.cplexJar);$($plan.deployment)\lib\mosek.jar"
$running=[Collections.Generic.List[object]]::new()
$jobs=@($plan.jobs)
function AtomicJson($value,$path){$value|ConvertTo-Json -Depth 10|Set-Content -LiteralPath "$path.tmp" -Encoding UTF8;Move-Item -LiteralPath "$path.tmp" -Destination $path -Force}
function Event($job,$state,$detail){[pscustomobject]@{time=(Get-Date -Format o);id=$job.id;method=$job.method;state=$state;attempt=$job.attempt;detail=$detail}|Export-Csv -LiteralPath "$control\events.csv" -Append -NoTypeInformation -Encoding UTF8}
function HasFile($path){return [IO.File]::Exists($path)-and([IO.FileInfo]$path).Length-gt0}
function WorkingDirectory($job){
    if($job.kind-in@('w1-validation','w1-merge')){return $job.deployment}
    return $plan.deployment
}
function Complete($job){
    $marker=if($job.kind-eq'w1-validation'){'validation_only_complete.txt'}else{'complete.txt'}
    if(-not(HasFile "$($job.output)\$marker")){return $false}
    $auditDir=Join-Path $control 'completion_audit'
    [void][IO.Directory]::CreateDirectory($auditDir)
    try{
        $p=Start-Process -FilePath $plan.java -ArgumentList ((AuditArguments $job)-join' ') -WorkingDirectory (WorkingDirectory $job) -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput "$auditDir\$($job.id).stdout.log" -RedirectStandardError "$auditDir\$($job.id).stderr.log"
        $handle=$p.Handle;$p.WaitForExit();$code=$p.ExitCode;$p.Dispose()
        if($code-eq20){
            $job.state='BLOCKED';Event $job 'PROTOCOL_MISMATCH' 'Preserved existing results; no automatic recomputation'
            return $false
        }
        if($null-eq$code){throw 'Completion audit exit code unavailable'}
        if($code-ne0){Event $job 'INCOMPLETE_ARTIFACTS' "auditExit=$code"}
        return $code-eq0
    }catch{
        $job.state='BLOCKED';Event $job 'AUDIT_ERROR' $_.Exception.Message
        return $false
    }
}
function Stopped($job){return $job.method-in@('C-MM','C-PCM')-and(HasFile "$control\moment_stop\$($job.method).stop.txt")}
function MainStageBlocked($job,$queue){
    $blocking=@($queue|Where-Object{$_.rank-lt$job.rank-and$_.state-in@('QUEUED','RUNNING')})
    # A supplement waits for its own small-grid task via depends, not other markets.
    if($job.kind-eq'rcsaa-staged'-and$job.rank-eq1){
        $blocking=@($blocking|Where-Object{-not($_.kind-eq'rcsaa-staged'-and$_.rank-eq0)})
    }
    return $blocking.Count-gt0
}
function WorkerCommandMatches($command,$expectedArguments){
    return $command.TrimEnd().EndsWith(' '+($expectedArguments-join' '),[StringComparison]::Ordinal)
}
function Arguments($job){
    $jobClasspath=if($job.kind-eq'rcsaa-staged'){"$($job.tools)\bin;$classpath"}else{$classpath}
    if($job.kind-in@('w1-validation','w1-merge')){
        $jobClasspath="$($job.tools)\bin;$($job.deployment)\bin;$($plan.cplexJar);$($plan.deployment)\lib\mosek.jar"
    }
    $workerArguments=@('-Xmx2g',('"-Djava.library.path='+$plan.native+'"'),('"-Dtrb.svu.python='+$plan.python+'"'),('"-Dtrb.svu.momentStopDirectory='+$control+'\moment_stop"'),'-cp',('"'+$jobClasspath+'"'))
    if($job.kind-eq'rcsaa-staged'){
        return $workerArguments+@('Test.analysis.synthetic.TRBSVURcsaaStagedGridMain','run',('"'+$job.input+'"'),('"'+$job.baseline+'"'),('"'+$job.output+'"'),$job.rep,('"'+$job.choice+'"'),$job.oldGrid,$job.grid)
    }
    if($job.kind-in@('w1-validation','w1-merge')){
        $mode=if($job.kind-eq'w1-validation'){'validate'}else{'merge'}
        $split=@('Test.analysis.synthetic.TRBSVUSplitW1Main',$mode,('"'+$job.input+'"'),
            ('"'+$job.choice+'"'),('"'+$job.output+'"'),$job.rep)
        if($job.kind-eq'w1-merge'){$split+=@(('"'+$job.small+'"'),('"'+$job.tail+'"'))}
        return $workerArguments+$split
    }
    if($job.kind-eq'comparison'){
        return $workerArguments+@('Test.analysis.synthetic.TRBSVULambdaDecisionComparison',('"'+$job.config+'"'),('"'+$job.input+'"'),('"'+$job.weights+'"'),('"'+$job.output+'"'))
    }
    if($job.kind-eq'base'){
        return $workerArguments+@('Test.analysis.synthetic.TRBSVUExperiment1IdeMain','--worker',('"'+$job.input+'"'),('"'+$job.output+'"'),$job.rep,$job.method,'25','4','14400')
    }
    $phase=if($job.method-in@('C-MM','C-PCM')){'MOMENT'}else{'PRIMARY'}
    return $workerArguments+@('Test.analysis.synthetic.TRBSVUExperiment2IdeMain','--worker',('"'+$job.input+'"'),('"'+$job.choice+'"'),('"'+$job.output+'"'),$job.rep,'4','0',$phase,$job.method)
}
function AuditArguments($job){
    $argsList=@(Arguments $job)
    if($job.kind-in@('w1-validation','w1-merge')){
        $index=[Array]::IndexOf($argsList,'Test.analysis.synthetic.TRBSVUSplitW1Main')
        $argsList[$index+1]=if($job.kind-eq'w1-validation'){'check-validation'}else{'check-merge'}
        return $argsList
    }
    if($job.kind-in@('base','robust')){
        $index=[Array]::IndexOf($argsList,'--worker');$argsList[$index]='--check-complete'
        return $argsList
    }
    if($job.kind-eq'comparison'){
        $index=[Array]::IndexOf($argsList,'Test.analysis.synthetic.TRBSVULambdaDecisionComparison')
        return @($argsList[0..$index])+@('--check-complete')+@($argsList[($index+1)..($argsList.Length-1)])
    }
    # Audit the deployment worker, not any older Experiment 2 class in staged tools/bin.
    $cpIndex=[Array]::IndexOf($argsList,'-cp');$argsList[$cpIndex+1]='"'+$classpath+'"'
    $index=[Array]::IndexOf($argsList,'Test.analysis.synthetic.TRBSVURcsaaStagedGridMain')
    return @($argsList[0..($index-1)])+@('Test.analysis.synthetic.TRBSVUExperiment2IdeMain','--check-complete',
        ('"'+$job.input+'"'),('"'+$job.choice+'"'),('"'+$job.output+'"'),$job.rep,'4','0','PRIMARY','RCSAA',$job.grid)
}
try{
    # Refuse a script-only upgrade against workers lacking the read-only audit entry points.
    if(-not(HasFile "$($plan.deployment)\bin\Test\analysis\synthetic\TRBSVUCompletionMarker`$ProtocolMismatchException.class")){
        throw 'Completion audit classes must be compiled/deployed with this scheduler; no workers started'
    }
    if(@($jobs.id|Select-Object -Unique).Count-ne$jobs.Count){throw 'Duplicate task ids'}
    foreach($job in $jobs){
        foreach($id in @($job.depends)){
            if($id-eq$job.id-or@($jobs|Where-Object{$_.id-eq$id}).Count-ne1){throw "Invalid dependency: $($job.id) -> $id"}
        }
        if($job.kind-notin@('base','robust','comparison','rcsaa-staged','w1-validation','w1-merge')){throw "Unknown worker kind $($job.kind)"}
        if($job.kind-in@('w1-validation','w1-merge')){
            if($job.method-ne'C-W1'-or-not(HasFile "$($job.tools)\bin\Test\analysis\synthetic\TRBSVUSplitW1Main.class")-or-not(HasFile $job.choice)-or-not(Test-Path -LiteralPath "$($job.deployment)\src")){throw "Invalid split W1 worker $($job.id)"}
        }
        if($job.kind-eq'rcsaa-staged'-and($job.method-ne'RCSAA'-or-not(HasFile "$($job.tools)\bin\Test\analysis\synthetic\TRBSVURcsaaStagedGridMain.class")-or-not(HasFile $job.choice)-or-not$job.oldGrid-or-not$job.grid)){throw "Invalid staged RCSAA worker $($job.id)"}
        if(-not(HasFile $(if($job.kind-eq'comparison'){$job.input}else{"$($job.input)\queries\queries.tsv"}))){throw "Missing input $($job.id)"}
        if($job.kind-eq'comparison'-and(-not(HasFile $job.config)-or-not(HasFile $job.weights))){throw "Missing comparison configuration $($job.id)"}
    }
    if($CheckOnly){
        Write-Output "QUEUE_CHECK_PASS tasks=$($jobs.Count) parallel=4 solverThreads=4 mode=$($plan.mode)"
        foreach($job in $jobs|Select-Object -First 2){Write-Output ((Arguments $job)-join' ')}
        return
    }
    # Resume the active queue; completed baseline experiments remain external references.
    foreach($job in $jobs){
        $job|Add-Member state 'QUEUED';$job|Add-Member attempt 0;$job|Add-Member pid 0
        if(Stopped $job){$job.state='STOPPED';continue}
        if(Complete $job){$job.state='COMPLETE';continue}
        if($job.state-eq'BLOCKED'){continue}
        $expectedArguments=Arguments $job
        $live=@(Get-Process java -ErrorAction SilentlyContinue|Where-Object{
            try{WorkerCommandMatches ([OlistWindowsProcess]::CommandLine($_.Id)) $expectedArguments}catch{$false}
        })
        if($live.Count-gt1){throw "Duplicate worker for $($job.id)"}
        if($live.Count-eq1){
            if(@($running|Where-Object{$_.process.Id-eq$live[0].Id}).Count){throw "Worker already adopted for another task: $($live[0].Id)"}
            $job.attempt=[int]((Get-ChildItem -LiteralPath "$control\logs" -Filter "$($job.id)_*.stdout.log"|ForEach-Object{if($_.Name-match'_(\d+)\.stdout\.log$'){[int]$Matches[1]}}|Measure-Object -Maximum).Maximum)
            $job.state='RUNNING';$job.pid=$live[0].Id;$handle=$live[0].Handle;$running.Add([pscustomobject]@{job=$job;process=$live[0]});Event $job 'ADOPTED' $job.pid
        }
    }
    $existingWorkers=@(Get-Process java -ErrorAction SilentlyContinue|Where-Object{
        try{([OlistWindowsProcess]::CommandLine($_.Id)).Contains('"-Dtrb.svu.momentStopDirectory='+$control+'\moment_stop"')}catch{$false}
    })
    foreach($worker in $existingWorkers){
        if(@($running|Where-Object{$_.process.Id-eq$worker.Id}).Count-ne1){throw "Unadopted experiment worker: $($worker.Id); refusing additional launches"}
    }
    if($running.Count-gt4){throw 'Existing workers exceed four-slot limit'}
    while(@($jobs|Where-Object{$_.state-in@('QUEUED','RUNNING')}).Count){
        foreach($job in @($jobs|Where-Object{$_.state-eq'QUEUED'})){
            if(Stopped $job){$job.state='STOPPED';Event $job 'STOPPED' 'Method-wide moment stop receipt';continue}
            foreach($id in @($job.depends)){
                $dep=@($jobs|Where-Object{$_.id-eq$id})[0]
                if($dep.state-in@('FAILED','STOPPED','BLOCKED')){$job.state='BLOCKED';Event $job 'BLOCKED' $id;break}
            }
        }
        while($running.Count-lt4){
            $ready=@($jobs|Where-Object{
                $j=$_
                if($j.state-ne'QUEUED'){return $false}
                if($j.kind-eq'w1-merge'-and-not(HasFile $j.readyFile)){return $false}
                if(@($j.depends|Where-Object{ $id=$_;@($jobs|Where-Object{$_.id-eq$id})[0].state-ne'COMPLETE' }).Count){return $false}
                if($plan.mode-eq'MAIN'-and(MainStageBlocked $j $jobs)){return $false}
                return $true
            }|Sort-Object @{Expression={$lane=$_.lane;@($running|Where-Object{$_.job.lane-eq$lane}).Count}},rank,id)
            if(-not$ready.Count){break}
            $job=$ready[0];$job.attempt++
            $stdout="$control\logs\$($job.id)_$($job.attempt).stdout.log"
            while(Test-Path -LiteralPath $stdout){$job.attempt++;$stdout="$control\logs\$($job.id)_$($job.attempt).stdout.log"}
            $stderr="$control\logs\$($job.id)_$($job.attempt).stderr.log"
            try{
                $workerArguments=Arguments $job
                $p=Start-Process -FilePath $plan.java -ArgumentList ($workerArguments-join' ') -WorkingDirectory (WorkingDirectory $job) -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
                $handle=$p.Handle;$job.state='RUNNING';$job.pid=$p.Id
                $running.Add([pscustomobject]@{job=$job;process=$p});Event $job 'STARTED' ($workerArguments-join' ')
            }catch{$job.state='FAILED';Event $job 'START_FAILED' $_.Exception.Message}
        }
        Start-Sleep -Seconds 10
        foreach($item in @($running.ToArray())){
            if(-not$item.process.HasExited){continue}
            $item.process.WaitForExit();$job=$item.job;$code=$item.process.ExitCode
            if(Complete $job){$job.state='COMPLETE';Event $job 'COMPLETE' $code}
            elseif($job.state-eq'BLOCKED'){Event $job 'BLOCKED' 'Completion audit requires review; preserved outputs'}
            elseif(Stopped $job){$job.state='STOPPED';Event $job 'STOPPED' "exit=$code; preserved partial outputs"}
            elseif($job.attempt-lt2){$job.state='QUEUED';Event $job 'RETRY' $code}
            else{$job.state='FAILED';Event $job 'FAILED' $code}
            $job.pid=0;[void]$running.Remove($item);$item.process.Dispose()
        }
        AtomicJson ([pscustomobject]@{state='RUNNING';updated=(Get-Date -Format o);parallel=4;solverThreads=4;running=$running.Count;queued=@($jobs|Where-Object{$_.state-eq'QUEUED'}).Count;complete=@($jobs|Where-Object{$_.state-eq'COMPLETE'}).Count;failed=@($jobs|Where-Object{$_.state-eq'FAILED'}).Count;stopped=@($jobs|Where-Object{$_.state-eq'STOPPED'}).Count;jobs=$jobs}) "$control\status.json"
    }
    AtomicJson ([pscustomobject]@{state=$(if(@($jobs|Where-Object{$_.state-ne'COMPLETE'}).Count){'PARTIAL'}else{'FINISHED'});updated=(Get-Date -Format o);parallel=4;solverThreads=4;running=0;jobs=$jobs}) "$control\status.json"
}catch{$_|Out-String|Set-Content -LiteralPath "$control\fatal_error.txt" -Encoding UTF8;throw}
finally{$lock.Dispose()}
