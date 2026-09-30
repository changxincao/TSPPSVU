param([Parameter(Mandatory=$true)][string]$TaskRoot,
      [Parameter(Mandatory=$true)][string]$ExperimentRoot,
      [ValidateRange(1,4)][int]$MaxParallel=4)
$ErrorActionPreference='Stop'
$control=Join-Path $ExperimentRoot 'control'
New-Item -ItemType Directory -Force -Path $control | Out-Null
$java=Join-Path $TaskRoot 'runtime\java\bin\java.exe'
$python=Join-Path $TaskRoot 'runtime\python\python.exe'
$native='E:\EnglishSave\Cplex22\cplex\bin\x64_win64'
$classpath="$TaskRoot\bin;E:\EnglishSave\Cplex22\cplex\lib\cplex.jar;$TaskRoot\lib\mosek.jar"
$env:Path="$native;$env:Path"
$env:OPENBLAS_NUM_THREADS='1'
$env:MKL_NUM_THREADS='1'
$env:OMP_NUM_THREADS='1'
Set-Location -LiteralPath $TaskRoot
Start-Transcript -Path (Join-Path $control 'controller.log') -Append | Out-Null
$queue=[System.Collections.Generic.Queue[object]]::new()
$running=[System.Collections.Generic.List[object]]::new()
$failed=[System.Collections.Generic.List[object]]::new()
$events=Join-Path $control 'events.csv'
function Event($task,$state,$code) {
    [pscustomobject]@{time=[DateTime]::Now.ToString('o');cell=$task.cell;rep=$task.rep;
        method=$task.method;attempt=$task.attempt;state=$state;exitCode=$code} |
        Export-Csv -LiteralPath $events -Append -NoTypeInformation -Encoding UTF8
}
# Each independent method task includes rolling validation, then all forty paired queries.
# No solver starts during generation. Resume always lets the Java worker verify its checkpoint protocol.
foreach($cell in @('cv030050','cv040060','cv010030')) {
    foreach($rep in 0..4) {
        foreach($method in @('CSAA-Exp','CSAA-Tri','RF-CSAA','SAA-All','D')) {
            $queue.Enqueue([pscustomobject]@{cell=$cell;rep=$rep;method=$method;attempt=0;
                target=(Join-Path $ExperimentRoot "$cell\experiment1\rep_$('{0:D3}' -f $rep)\$method")})
        }
    }
}
while($queue.Count -gt 0 -or $running.Count -gt 0) {
    while($queue.Count -gt 0 -and $running.Count -lt $MaxParallel) {
        $task=$queue.Dequeue()
        $task.attempt++
        New-Item -ItemType Directory -Force -Path $task.target | Out-Null
        $input=Join-Path $ExperimentRoot "$($task.cell)\input\rep_$('{0:D3}' -f $task.rep)"
        $grid=if($task.method -eq 'CSAA-Tri') {'0.8,0.9,1,2'} else {'0.1,0.25,0.5,0.8'}
        $args=@('-Xmx2g',('"-Djava.library.path='+$native+'"'),
            ('"-Dtrb.svu.python='+$python+'"'),('-Dtrb.svu.bandwidthGrid='+$grid),
            '-Dtrb.svu.rfLeafGrid=1,2,5','-cp',('"'+$classpath+'"'),
            'Test.analysis.synthetic.TRBSVUExperiment1IdeMain','--worker',
            ('"'+$input+'"'),('"'+$task.target+'"'),$task.rep,$task.method,25,4,14400)
        try {
            $process=Start-Process -FilePath $java -ArgumentList ($args -join ' ') -WorkingDirectory $TaskRoot `
                -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $task.target "attempt_$($task.attempt).stdout.log") `
                -RedirectStandardError (Join-Path $task.target "attempt_$($task.attempt).stderr.log")
            $heldHandle=$process.Handle
            $running.Add([pscustomobject]@{task=$task;process=$process})
            Event $task 'STARTED' ''
        } catch {
            Event $task 'START_FAILED' $_.Exception.Message
            if($task.attempt -lt 2) {$queue.Enqueue($task)} else {$failed.Add($task)}
        }
    }
    Start-Sleep -Seconds 10
    foreach($item in @($running.ToArray())) {
        if(-not $item.process.HasExited) {continue}
        $item.process.WaitForExit()
        $code=$item.process.ExitCode
        if($code -eq 0 -and (Test-Path -LiteralPath (Join-Path $item.task.target 'complete.txt'))) {
            Event $item.task 'COMPLETE' $code
        } else {
            Event $item.task 'FAILED' $code
            if($item.task.attempt -lt 2) {$queue.Enqueue($item.task)} else {$failed.Add($item.task)}
        }
        [void]$running.Remove($item)
        $item.process.Dispose()
    }
    [pscustomobject]@{state='RUNNING';updated=[DateTime]::Now.ToString('o');queued=$queue.Count;
        running=$running.Count;failed=$failed.Count;parallel=$MaxParallel;solverThreads=4} |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $control 'status.json') -Encoding UTF8
}
# Selection uses validation costs only; it is never based on forty-query OOS outcomes.
foreach($cell in @('cv030050','cv040060','cv010030')) {
    foreach($rep in 0..4) {
        $output=Join-Path $ExperimentRoot "$cell\experiment1"
        $repRoot=Join-Path $output ('rep_{0:D3}' -f $rep)
        $ready=$true
        foreach($method in @('CSAA-Exp','CSAA-Tri','RF-CSAA')) {
            if(-not (Test-Path -LiteralPath (Join-Path $repRoot "$method\complete.txt"))) {$ready=$false}
        }
        if(-not $ready) {continue}
        & $java -cp $classpath Test.analysis.synthetic.TRBSVUExperiment1IdeMain --aggregate `
            (Join-Path $ExperimentRoot "$cell\input") $output 'CSAA-Exp,CSAA-Tri,RF-CSAA' $rep `
            >> (Join-Path $control 'selection.log') 2>&1
        if($LASTEXITCODE -ne 0) {$failed.Add([pscustomobject]@{cell=$cell;rep=$rep;method='SELECTION'})}
    }
}
$failed | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $control 'failed_tasks.json') -Encoding UTF8
[pscustomobject]@{state=$(if($failed.Count -eq 0){'FINISHED'}else{'PARTIAL'});
    ended=[DateTime]::Now.ToString('o');failed=$failed.Count;running=0;queued=0} |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $control 'status.json') -Encoding UTF8
Stop-Transcript | Out-Null
