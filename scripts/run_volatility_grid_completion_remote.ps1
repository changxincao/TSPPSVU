param([Parameter(Mandatory=$true)][string]$Root)
$ErrorActionPreference='Stop';$ProgressPreference='SilentlyContinue'
$deploy=Join-Path $Root 'deployment';$tools=Join-Path $Root 'tools';$control=Join-Path $Root 'control'
New-Item -ItemType Directory -Force -Path $control | Out-Null
$lock=[IO.File]::Open("$control\scheduler.lock",'OpenOrCreate','ReadWrite','None')
$java='D:\Java\jdk-21\bin\java.exe'
$python='D:\ccx\TSPP_SVU\deployments\e0040ef\.venv-rsome\Scripts\python.exe'
$cplex='D:\software\IBM\ILOG\CPLEX_Studio2211\cplex'
$native="$cplex\bin\x64_win64;$deploy\lib"
$classpath="$tools\bin;$deploy\bin;$cplex\lib\cplex.jar;$deploy\lib\mosek.jar"
$env:PATH="$native;$env:PATH";$env:MOSEKLM_LICENSE_FILE='C:\Users\codex-runner\mosek\mosek.lic'
$env:OPENBLAS_NUM_THREADS='1';$env:MKL_NUM_THREADS='1';$env:OMP_NUM_THREADS='1'
Set-Location -LiteralPath $deploy
$running=[Collections.Generic.List[object]]::new();$failures=[Collections.Generic.List[object]]::new()
function Event($t,$state,$detail){[pscustomobject]@{time=(Get-Date -Format o);cell=$t.cell;rep=$t.rep;method=$t.method;attempt=$t.attempt;state=$state;detail=$detail}|Export-Csv "$control\events.csv" -Append -NoTypeInformation -Encoding UTF8}
function Complete($t){
    if(-not(Test-Path -LiteralPath "$($t.target)\complete.txt")){return $false}
    foreach($q in 0..39){
        $dir=Join-Path $t.target ('queries\query_{0:D3}' -f $q)
        $files=if($t.method -eq 'RF-CSAA'){@('query_metadata.txt','solve\final_solve.csv','solve\final_weights.csv','oos\summary.csv','oos\draws.csv')}else{@('query_metadata.txt','solve\experiment2_final_solves.csv','solve\experiment2_final_weights.csv','oos\experiment2_summary.csv','oos\experiment2_draws.csv')}
        foreach($f in $files){$p=Join-Path $dir $f;if(-not [IO.File]::Exists($p)-or([IO.FileInfo]$p).Length-eq0){return $false}}
    };return $true
}
try{
    foreach($cell in @('cv010030','cv040060')){foreach($phase in @('RF','ROBUST')){
        $queue=[Collections.Generic.Queue[object]]::new()
        foreach($r in 1..5){foreach($method in $(if($phase-eq'RF'){@('RF-CSAA')}else{@('C-Chi2')})){
            $rep='rep_{0:D3}'-f$r
            $target=if($phase-eq'RF'){"$Root\results\$cell\experiment1\$rep\RF-CSAA"}else{"$Root\results\$cell\experiment2_rf\$rep\$method"}
            $t=[pscustomobject]@{cell=$cell;rep=$r;method=$method;target=$target;attempt=0;runAttempts=0}
            $rf="$Root\results\$cell\experiment1\$rep\RF-CSAA"
            if($phase-eq'ROBUST'-and-not(Complete ([pscustomobject]@{method='RF-CSAA';target=$rf}))){Event $t 'SKIPPED' 'RF prerequisite incomplete';$failures.Add($t);continue}
            # Always invoke the protocol-aware worker, even when a marker already exists.
            $queue.Enqueue($t)
        }}
        while($queue.Count-gt0-or$running.Count-gt0){
            while($queue.Count-gt0-and$running.Count-lt4){
                $t=$queue.Dequeue();$t.runAttempts++;do{$t.attempt++}while(Test-Path "$($t.target)\attempt_$($t.attempt).stdout.log")
                New-Item -ItemType Directory -Force -Path $t.target|Out-Null
                $rep='rep_{0:D3}'-f$t.rep;$base="$Root\baseline\$cell"
                $oldRf="$base\experiment1\$rep\RF-CSAA"
                $newChoice="$Root\results\$cell\experiment1\$rep\RF-CSAA\queries\query_000\validation\context_candidate.csv"
                $old=if($phase-eq'RF'){$oldRf}else{"$base\experiment2_fixed_csaa\RF-CSAA\primary\C-Chi2\$rep"}
                $op=if($phase-eq'RF'){'exp'}else{'chi'}
                $args=@('-Xmx2g',('"-Djava.library.path='+$native+'"'),('"-Dtrb.svu.python='+$python+'"'),'-cp',('"'+$classpath+'"'),'Test.analysis.synthetic.TRBSVUGridCompletionMain',$op,('"'+$base+'\input\'+$rep+'"'),('"'+$old+'"'),('"'+$t.target+'"'),$t.rep,$t.method)
                if($t.method-eq'C-Chi2'){$args+=('"'+$oldRf+'\queries\query_000\validation\context_candidate.csv"');$args+=('"'+$newChoice+'"')}
                try{
                    $p=Start-Process $java -ArgumentList ($args-join' ') -WorkingDirectory $deploy -WindowStyle Hidden -PassThru -RedirectStandardOutput "$($t.target)\attempt_$($t.attempt).stdout.log" -RedirectStandardError "$($t.target)\attempt_$($t.attempt).stderr.log"
                    $handle=$p.Handle;$running.Add([pscustomobject]@{task=$t;process=$p});Event $t 'STARTED' $p.Id
                }catch{Event $t 'START_FAILED' $_.Exception.Message;if($t.runAttempts-lt2){$queue.Enqueue($t)}else{$failures.Add($t)}}
            }
            Start-Sleep -Seconds 10
            foreach($item in @($running.ToArray())){
                if(-not$item.process.HasExited){continue}
                $item.process.WaitForExit();$code=$item.process.ExitCode
                if($code-eq0-and(Complete $item.task)){Event $item.task 'COMPLETE' $code}else{Event $item.task 'FAILED' $code;if($item.task.runAttempts-lt2){$queue.Enqueue($item.task)}else{$failures.Add($item.task)}}
                [void]$running.Remove($item);$item.process.Dispose()
            }
            [pscustomobject]@{state='RUNNING';cell=$cell;phase=$phase;updated=(Get-Date -Format o);queued=$queue.Count;running=$running.Count;failed=$failures.Count;parallel=4;solverThreads=4;lambdaGrid=@(.01,.05,.1,.25,.5,1,2,5,10);rcsaaEnabled=$false}|ConvertTo-Json|Set-Content "$control\status.json" -Encoding UTF8
        }
    }}
    $failures|ConvertTo-Json -Depth 4|Set-Content "$control\failed_tasks.json" -Encoding UTF8
    [pscustomobject]@{state=$(if($failures.Count){'PARTIAL'}else{'FINISHED'});failed=$failures.Count;running=0;updated=(Get-Date -Format o)}|ConvertTo-Json|Set-Content "$control\status.json" -Encoding UTF8
}catch{$_|Out-String|Set-Content "$control\fatal_error.txt" -Encoding UTF8;throw}finally{$lock.Dispose()}
