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
function Complete($job){
    if(-not(HasFile "$($job.output)\complete.txt")){return $false}
    if($job.kind-eq'comparison'){
        if(-not(HasFile "$($job.output)\comparison.csv")-or-not(HasFile $job.config)){return $false}
        $grid=@(Get-Content -LiteralPath $job.config|Where-Object{$_-match'^lambdaGrid='})
        if($grid.Count-ne1){return $false}
        $expected=@($grid[0].Substring('lambdaGrid='.Length).Split(',')|ForEach-Object{[double]::Parse($_,[Globalization.CultureInfo]::InvariantCulture)})
        $rows=@(Import-Csv -LiteralPath "$($job.output)\comparison.csv")
        $actual=@($rows|ForEach-Object{[double]::Parse($_.lambda,[Globalization.CultureInfo]::InvariantCulture)})
        return $rows.Count-eq$expected.Count-and@($actual|Select-Object -Unique).Count-eq$expected.Count-and@($expected|Where-Object{$_-notin$actual}).Count-eq0
    }
    foreach($q in 0..39){
        $dir=Join-Path $job.output ('queries\query_{0:D3}'-f$q)
        $files=if($job.kind-eq'base'){@('query_metadata.txt','solve\final_solve.csv','oos\summary.csv','oos\draws.csv')}else{@('query_metadata.txt','solve\experiment2_final_solves.csv','oos\experiment2_summary.csv','oos\experiment2_draws.csv')}
        foreach($f in $files){if(-not(HasFile (Join-Path $dir $f))){return $false}}
    };return $true
}
function Stopped($job){return $job.method-in@('C-MM','C-PCM')-and(HasFile "$control\moment_stop\$($job.method).stop.txt")}
function Arguments($job){
    $workerArguments=@('-Xmx2g',('"-Djava.library.path='+$plan.native+'"'),('"-Dtrb.svu.python='+$plan.python+'"'),('"-Dtrb.svu.momentStopDirectory='+$control+'\moment_stop"'),'-cp',('"'+$classpath+'"'))
    if($job.kind-eq'comparison'){
        return $workerArguments+@('Test.analysis.synthetic.TRBSVULambdaDecisionComparison',('"'+$job.config+'"'),('"'+$job.input+'"'),('"'+$job.weights+'"'),('"'+$job.output+'"'))
    }
    if($job.kind-eq'base'){
        return $workerArguments+@('Test.analysis.synthetic.TRBSVUExperiment1IdeMain','--worker',('"'+$job.input+'"'),('"'+$job.output+'"'),$job.rep,$job.method,'25','4','14400')
    }
    $phase=if($job.method-in@('C-MM','C-PCM')){'MOMENT'}else{'PRIMARY'}
    return $workerArguments+@('Test.analysis.synthetic.TRBSVUExperiment2IdeMain','--worker',('"'+$job.input+'"'),('"'+$job.choice+'"'),('"'+$job.output+'"'),$job.rep,'4','0',$phase,$job.method)
}
try{
    if(@($jobs.id|Select-Object -Unique).Count-ne$jobs.Count){throw 'Duplicate task ids'}
    foreach($job in $jobs){
        foreach($id in @($job.depends)){
            if($id-eq$job.id-or@($jobs|Where-Object{$_.id-eq$id}).Count-ne1){throw "Invalid dependency: $($job.id) -> $id"}
        }
        if($job.kind-notin@('base','robust','comparison')){throw "Unknown worker kind $($job.kind)"}
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
        $live=@(Get-Process java -ErrorAction SilentlyContinue|Where-Object{
            try{([OlistWindowsProcess]::CommandLine($_.Id)).Contains('"'+$job.output+'"')}catch{$false}
        })
        if($live.Count-gt1){throw "Duplicate worker for $($job.id)"}
        if($live.Count-eq1){
            $job.attempt=[int]((Get-ChildItem -LiteralPath "$control\logs" -Filter "$($job.id)_*.stdout.log"|ForEach-Object{if($_.Name-match'_(\d+)\.stdout\.log$'){[int]$Matches[1]}}|Measure-Object -Maximum).Maximum)
            $job.state='RUNNING';$job.pid=$live[0].Id;$handle=$live[0].Handle;$running.Add([pscustomobject]@{job=$job;process=$live[0]});Event $job 'ADOPTED' $job.pid
        }
    }
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
                if(@($j.depends|Where-Object{ $id=$_;@($jobs|Where-Object{$_.id-eq$id})[0].state-ne'COMPLETE' }).Count){return $false}
                if($plan.mode-eq'MAIN'-and@($jobs|Where-Object{$_.rank-lt$j.rank-and$_.state-in@('QUEUED','RUNNING')}).Count){return $false}
                return $true
            }|Sort-Object @{Expression={$lane=$_.lane;@($running|Where-Object{$_.job.lane-eq$lane}).Count}},rank,id)
            if(-not$ready.Count){break}
            $job=$ready[0];$job.attempt++
            $stdout="$control\logs\$($job.id)_$($job.attempt).stdout.log"
            while(Test-Path -LiteralPath $stdout){$job.attempt++;$stdout="$control\logs\$($job.id)_$($job.attempt).stdout.log"}
            $stderr="$control\logs\$($job.id)_$($job.attempt).stderr.log"
            try{
                $workerArguments=Arguments $job
                $p=Start-Process -FilePath $plan.java -ArgumentList ($workerArguments-join' ') -WorkingDirectory $plan.deployment -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
                $handle=$p.Handle;$job.state='RUNNING';$job.pid=$p.Id
                $running.Add([pscustomobject]@{job=$job;process=$p});Event $job 'STARTED' ($workerArguments-join' ')
            }catch{$job.state='FAILED';Event $job 'START_FAILED' $_.Exception.Message}
        }
        Start-Sleep -Seconds 10
        foreach($item in @($running.ToArray())){
            if(-not$item.process.HasExited){continue}
            $item.process.WaitForExit();$job=$item.job;$code=$item.process.ExitCode
            if(Complete $job){$job.state='COMPLETE';Event $job 'COMPLETE' $code}
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
