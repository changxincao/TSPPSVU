param([Parameter(Mandatory=$true)][string]$TaskRoot,
      [Parameter(Mandatory=$true)][string]$BaselineRoot,
      [Parameter(Mandatory=$true)][string]$OutputRoot,
      [Parameter(Mandatory=$true)][string]$ToolsRoot,
      [ValidateRange(1,4)][int]$MaxParallel=4,
      [switch]$IncludeBaselines)
$ErrorActionPreference='Stop'
$control=Join-Path $OutputRoot 'control'
New-Item -ItemType Directory -Force -Path $control | Out-Null
$lock=[System.IO.File]::Open((Join-Path $control 'scheduler.lock'),'OpenOrCreate','ReadWrite','None')
$java=Join-Path $TaskRoot 'runtime\java\bin\java.exe'
$python=Join-Path $TaskRoot 'runtime\python\python.exe'
$native='E:\EnglishSave\Cplex22\cplex\bin\x64_win64'
# Isolated driver/cache-adapter runners first; native model/solver classes remain in the original bin.
$classpath="$ToolsRoot\bin;$TaskRoot\bin;E:\EnglishSave\Cplex22\cplex\lib\cplex.jar;$TaskRoot\lib\mosek.jar"
$env:Path="$native;$TaskRoot\lib;$env:Path"
$env:MOSEKLM_LICENSE_FILE=Join-Path $TaskRoot 'tmp\mosek.lic'
$env:OPENBLAS_NUM_THREADS='1'; $env:MKL_NUM_THREADS='1'; $env:OMP_NUM_THREADS='1'
Set-Location -LiteralPath $TaskRoot
$running=[System.Collections.Generic.List[object]]::new()
$failures=[System.Collections.Generic.List[object]]::new()
$events=Join-Path $control 'events.csv'
function Event($task,$state,$detail) {
    [pscustomobject]@{time=[DateTime]::Now.ToString('o');rep=$task.rep;method=$task.method;
        attempt=$task.attempt;state=$state;detail=$detail} |
        Export-Csv -LiteralPath $events -Append -NoTypeInformation -Encoding UTF8
}
function Complete($task) {
    if(-not(Test-Path -LiteralPath (Join-Path $task.target 'complete.txt'))) {return $false}
    foreach($q in 0..39) {
        $root=Join-Path $task.target ('queries\query_{0:D3}' -f $q)
        $files=if($task.method -eq 'C-Chi2') {
            @('query_metadata.txt','solve\experiment2_final_solves.csv','solve\experiment2_final_weights.csv',
              'oos\experiment2_summary.csv','oos\experiment2_draws.csv')
        } else {
            @('query_metadata.txt','validation\summary.csv','validation\details.csv',
              'solve\final_solve.csv','solve\final_weights.csv','oos\summary.csv','oos\draws.csv')
        }
        foreach($file in $files) {
            $path=Join-Path $root $file
            if(-not(Test-Path -LiteralPath $path -PathType Leaf) -or (Get-Item -LiteralPath $path).Length -eq 0) {return $false}
        }
    }
    return $true
}
try {
    foreach($phase in @('CSAA','DRO')) {
        $queue=[System.Collections.Generic.Queue[object]]::new()
        foreach($rep in 1..5) {
            $name='rep_{0:D3}' -f $rep
            $methods=if($phase -eq 'CSAA') {@('RF-CSAA','CSAA-Exp','CSAA-Tri','CSAA-Gau','CSAA-Epa')} else {@('C-Chi2')}
            if($phase -eq 'CSAA' -and $IncludeBaselines) {$methods=@('D','SAA-All')+$methods}
            foreach($method in $methods) {
                $target=if($phase -eq 'CSAA') {Join-Path $OutputRoot "experiment1\$name\$method"} else {Join-Path $OutputRoot "experiment2_rf\$name"}
                $task=[pscustomobject]@{rep=$rep;method=$method;target=$target;attempt=0}
                if($phase -eq 'DRO' -and -not(Complete ([pscustomobject]@{method='RF-CSAA';target=(Join-Path $OutputRoot "experiment1\$name\RF-CSAA")}))) {
                    Event $task 'SKIPPED' 'RF-CSAA prerequisite incomplete'; $failures.Add($task); continue
                }
                # A worker still checks its full protocol on resume, even if files appear complete.
                $queue.Enqueue($task)
            }
        }
        while($queue.Count -gt 0 -or $running.Count -gt 0) {
            while($queue.Count -gt 0 -and $running.Count -lt $MaxParallel) {
                $task=$queue.Dequeue(); $task.attempt++
                $name='rep_{0:D3}' -f $task.rep
                New-Item -ItemType Directory -Force -Path $task.target | Out-Null
                $inputDir=Join-Path $BaselineRoot "input\$name"
                $baseline=if($phase -eq 'CSAA') {Join-Path $BaselineRoot "experiment1\$name\$($task.method)"} else {Join-Path $BaselineRoot "experiment2_fixed_csaa\RF-CSAA\primary\C-Chi2\$name"}
                $operation=if($phase -eq 'CSAA') {'exp'} else {'chi'}
                $arguments=@('-Xmx2g',('"-Djava.library.path='+$native+'"'),('"-Dtrb.svu.python='+$python+'"'),
                    '-cp',('"'+$classpath+'"'),'Test.analysis.synthetic.TRBSVUGridCompletionMain',$operation,
                    ('"'+$inputDir+'"'),('"'+$baseline+'"'),('"'+$task.target+'"'),$task.rep,$task.method)
                if($phase -eq 'DRO') {
                    $arguments+=('"'+(Join-Path $BaselineRoot "experiment1\$name\RF-CSAA\queries\query_000\validation\context_candidate.csv")+'"')
                    $arguments+=('"'+(Join-Path $OutputRoot "experiment1\$name\RF-CSAA\queries\query_000\validation\context_candidate.csv")+'"')
                }
                try {
                    $process=Start-Process -FilePath $java -ArgumentList ($arguments -join ' ') -WorkingDirectory $TaskRoot -WindowStyle Hidden -PassThru `
                        -RedirectStandardOutput (Join-Path $task.target "attempt_$($task.attempt).stdout.log") `
                        -RedirectStandardError (Join-Path $task.target "attempt_$($task.attempt).stderr.log")
                    $heldHandle=$process.Handle
                    $running.Add([pscustomobject]@{task=$task;process=$process}); Event $task 'STARTED' $process.Id
                } catch {
                    Event $task 'START_FAILED' $_.Exception.Message
                    if($task.attempt -lt 2) {$queue.Enqueue($task)} else {$failures.Add($task)}
                }
            }
            Start-Sleep -Seconds 10
            foreach($item in @($running.ToArray())) {
                if(-not $item.process.HasExited) {continue}
                $item.process.WaitForExit(); $code=$item.process.ExitCode
                if($code -eq 0 -and (Complete $item.task)) {Event $item.task 'COMPLETE' $code} else {
                    Event $item.task 'FAILED' $code
                    if($item.task.attempt -lt 2) {$queue.Enqueue($item.task)} else {$failures.Add($item.task)}
                }
                [void]$running.Remove($item); $item.process.Dispose()
            }
            [pscustomobject]@{state='RUNNING';phase=$phase;updated=[DateTime]::Now.ToString('o');
                queued=$queue.Count;running=$running.Count;failed=$failures.Count;parallel=$MaxParallel;solverThreads=4} |
                ConvertTo-Json | Set-Content -LiteralPath (Join-Path $control 'status.json') -Encoding UTF8
        }
    }
    $failures | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $control 'failed_tasks.json') -Encoding UTF8
    [pscustomobject]@{state=$(if($failures.Count -eq 0){'FINISHED'}else{'PARTIAL'});running=0;queued=0;failed=$failures.Count;ended=[DateTime]::Now.ToString('o')} |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $control 'status.json') -Encoding UTF8
} finally {$lock.Dispose()}
