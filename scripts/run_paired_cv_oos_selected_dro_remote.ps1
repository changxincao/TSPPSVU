param([Parameter(Mandatory=$true)][string]$TaskRoot,
      [Parameter(Mandatory=$true)][string]$ExperimentRoot,
      [Parameter(Mandatory=$true)]
      [ValidateSet('cv030050','cv040060','cv010030','cv050070')][string]$Cell,
      [ValidateRange(1,4)][int]$MaxParallel=4,
      [ValidateRange(1,100)][int]$ReplicationCount=5,
      [int]$SolverThreads=4,
      [int]$LimitSeconds=14400,
      [string]$LambdaGrid='0.1,0.25,0.5,1',
      [switch]$SelectionOnly)
$ErrorActionPreference='Stop'
$java=Join-Path $TaskRoot 'runtime\java\bin\java.exe'
$python=Join-Path $TaskRoot 'runtime\python\python.exe'
$cplex='E:\EnglishSave\Cplex22\cplex\bin\x64_win64'
$native="$cplex;$TaskRoot\lib"
$classpath="$TaskRoot\bin;E:\EnglishSave\Cplex22\cplex\lib\cplex.jar;$TaskRoot\lib\mosek.jar"
$cellRoot=Join-Path $ExperimentRoot $Cell
$exp1=Join-Path $cellRoot 'experiment1'
$stage=Join-Path $cellRoot 'experiment2_oos_selected'
$control=Join-Path $stage 'control'
New-Item -ItemType Directory -Force -Path $control | Out-Null
$env:Path="$native;$env:Path"
$env:MOSEKLM_LICENSE_FILE=Join-Path $TaskRoot 'tmp\mosek.lic'
$env:OPENBLAS_NUM_THREADS='1';$env:MKL_NUM_THREADS='1';$env:OMP_NUM_THREADS='1'
Set-Location -LiteralPath $TaskRoot
$methods=@('CSAA-Exp','CSAA-Tri','RF-CSAA')
$queue=[System.Collections.Generic.Queue[object]]::new()
$running=[System.Collections.Generic.List[object]]::new()
$failed=[System.Collections.Generic.List[object]]::new()
$selectionRows=[System.Collections.Generic.List[object]]::new()
$events=Join-Path $control 'events.csv'

function Read-One([string]$path){
    if(-not(Test-Path -LiteralPath $path -PathType Leaf) -or (Get-Item -LiteralPath $path).Length -eq 0){throw "Missing or empty CSV: $path"}
    $rows=@(Import-Csv -LiteralPath $path)
    if($rows.Count -ne 1){throw "Expected one row in $path; found $($rows.Count)"}
    return $rows[0]
}
function Read-Number($row,[string]$column,[string]$path){
    $value=0.0
    if(-not [double]::TryParse([string]$row.$column,[Globalization.NumberStyles]::Float,[Globalization.CultureInfo]::InvariantCulture,[ref]$value) -or [double]::IsNaN($value) -or [double]::IsInfinity($value)){throw "Invalid $column in $path"}
    return $value
}
function Read-QueryHash([string]$path){
    $line=Get-Content -LiteralPath $path -Encoding UTF8 | Where-Object {$_ -like 'querySha256=*'} | Select-Object -First 1
    if(-not $line){throw "Missing querySha256 in $path"}
    return $line.Substring('querySha256='.Length)
}
function Require-MethodComplete([string]$directory,[string]$method){
    if(-not(Test-Path -LiteralPath (Join-Path $directory 'complete.txt'))){throw "Base method is incomplete: $directory"}
    foreach($q in 0..39){
        $query=Join-Path $directory ('queries\query_{0:D3}' -f $q)
        $summary=Join-Path $query 'oos\summary.csv';$meta=Join-Path $query 'query_metadata.txt'
        $row=Read-One $summary;[void](Read-Number $row 'mean' $summary);[void](Read-QueryHash $meta)
        if([string]$row.method -ne $method){throw "Method mismatch in $summary"}
    }
}
function Write-Event($task,[string]$state,$code){
    [pscustomobject]@{time=[DateTime]::Now.ToString('o');cell=$Cell;rep=$task.rep;method='C-Chi2';attempt=$task.attempt;state=$state;exitCode=$code} | Export-Csv -LiteralPath $events -Append -NoTypeInformation -Encoding UTF8
}
function Write-Status([string]$state){
    $json=[pscustomobject]@{state=$state;cell=$Cell;phase='OOS_SELECTED_C_CHI2_DIAGNOSTIC';selection='BEST_40_QUERY_OOS_MEAN';formalTrainingOnly=$false;updated=[DateTime]::Now.ToString('o');queued=$queue.Count;running=$running.Count;failed=$failed.Count;parallel=$MaxParallel;solverThreads=$SolverThreads;lambdaGrid=$LambdaGrid} | ConvertTo-Json
    for($attempt=1;$attempt-le40;$attempt++){try{Set-Content -LiteralPath (Join-Path $control 'status.json') -Value $json -Encoding UTF8 -ErrorAction Stop;return}catch [System.IO.IOException]{if($attempt-eq40){throw};Start-Sleep -Milliseconds 250}}
}

foreach($rep in 0..($ReplicationCount-1)){
    $repName='rep_{0:D3}' -f $rep;$repRoot=Join-Path $exp1 $repName
    $scores=[ordered]@{};$choices=[ordered]@{};$validationScores=[ordered]@{};$hashes=@{}
    foreach($method in $methods){
        $methodRoot=Join-Path $repRoot $method;Require-MethodComplete $methodRoot $method
        $values=[System.Collections.Generic.List[double]]::new()
        foreach($q in 0..39){
            $summary=Join-Path $methodRoot ('queries\query_{0:D3}\oos\summary.csv' -f $q)
            $row=Read-One $summary;$values.Add((Read-Number $row 'mean' $summary))
            $meta=Join-Path $methodRoot ('queries\query_{0:D3}\query_metadata.txt' -f $q);$hash=Read-QueryHash $meta
            if(-not $hashes.ContainsKey($q)){$hashes[$q]=$hash}elseif($hashes[$q] -ne $hash){throw "Query hash mismatch rep=$rep q=$q method=$method"}
        }
        $scores[$method]=($values | Measure-Object -Average).Average
        $candidate=Join-Path $methodRoot 'queries\query_000\validation\context_candidate.csv'
        $candidateRow=Read-One $candidate;$choices[$method]=$candidate
        $validationScores[$method]=Read-Number $candidateRow 'validation_cost' $candidate
    }
    $oosWinner=$methods | Sort-Object @{Expression={$scores[$_]};Ascending=$true},@{Expression={$_};Ascending=$true} | Select-Object -First 1
    $validationWinner=$methods | Sort-Object @{Expression={$validationScores[$_]};Ascending=$true},@{Expression={$_};Ascending=$true} | Select-Object -First 1
    $selectionDir=Join-Path $repRoot 'oos_selection';New-Item -ItemType Directory -Force -Path $selectionDir | Out-Null
    $selectedFile=Join-Path $selectionDir 'experiment1_selected_context_oos.csv';Copy-Item -LiteralPath $choices[$oosWinner] -Destination $selectedFile -Force
    @('selectionProtocol=BEST_40_QUERY_OOS_MEAN','formalTrainingOnly=false','purpose=DIAGNOSTIC_REQUESTED_BY_USER',"oosWinner=$oosWinner","validationWinner=$validationWinner",('winnersAgree='+($oosWinner -eq $validationWinner)),'queryCount=40',"lambdaGrid=$LambdaGrid") | Set-Content -LiteralPath (Join-Path $selectionDir 'selection_protocol.txt') -Encoding UTF8
    $selectionRows.Add([pscustomobject]@{cell=$Cell;replication=$rep;oos_winner=$oosWinner;validation_winner=$validationWinner;winners_agree=($oosWinner -eq $validationWinner);oos_mean_exp=$scores['CSAA-Exp'];oos_mean_tri=$scores['CSAA-Tri'];oos_mean_rf=$scores['RF-CSAA'];validation_cost_exp=$validationScores['CSAA-Exp'];validation_cost_tri=$validationScores['CSAA-Tri'];validation_cost_rf=$validationScores['RF-CSAA'];selected_context_file=$selectedFile})
    $target=Join-Path $stage ('primary\C-Chi2\{0}' -f $repName)
    $queue.Enqueue([pscustomobject]@{rep=$rep;attempt=0;target=$target;selected=$selectedFile;input=(Join-Path $cellRoot "input\$repName")})
}
$selectionRows | Export-Csv -LiteralPath (Join-Path $control 'oos_vs_validation_selection.csv') -NoTypeInformation -Encoding UTF8
if($SelectionOnly){
    Write-Status 'SELECTION_COMPLETE'
    exit 0
}

while($queue.Count -gt 0 -or $running.Count -gt 0){
    while($queue.Count -gt 0 -and $running.Count -lt $MaxParallel){
        $task=$queue.Dequeue();$task.attempt++;New-Item -ItemType Directory -Force -Path $task.target | Out-Null
        $args=@('-Xmx2g',('"-Djava.library.path='+$native+'"'),('"-Dtrb.svu.python='+$python+'"'),'-cp',('"'+$classpath+'"'),'Test.analysis.synthetic.TRBSVUExperiment2IdeMain','--worker',('"'+$task.input+'"'),('"'+$task.selected+'"'),('"'+$task.target+'"'),$task.rep,$SolverThreads,$LimitSeconds,'PRIMARY','C-Chi2',$LambdaGrid)
        try{$process=Start-Process -FilePath $java -ArgumentList ($args -join ' ') -WorkingDirectory $TaskRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $task.target "attempt_$($task.attempt).stdout.log") -RedirectStandardError (Join-Path $task.target "attempt_$($task.attempt).stderr.log");$heldHandle=$process.Handle;$running.Add([pscustomobject]@{task=$task;process=$process});Write-Event $task 'STARTED' $process.Id}
        catch{Write-Event $task 'START_FAILED' $_.Exception.Message;if($task.attempt -lt 2){$queue.Enqueue($task)}else{$failed.Add($task)}}
    }
    Start-Sleep -Seconds 10
    foreach($item in @($running.ToArray())){
        if(-not $item.process.HasExited){continue}
        $item.process.WaitForExit();$code=$item.process.ExitCode;$complete=Join-Path $item.task.target 'complete.txt'
        if($code -eq 0 -and (Test-Path -LiteralPath $complete)){Write-Event $item.task 'COMPLETE' $code}else{Write-Event $item.task 'FAILED' $code;if($item.task.attempt -lt 2){$queue.Enqueue($item.task)}else{$failed.Add($item.task)}}
        [void]$running.Remove($item);$item.process.Dispose()
    }
    Write-Status 'RUNNING'
}
$failed | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $control 'failed_tasks.json') -Encoding UTF8
Write-Status $(if($failed.Count -eq 0){'FINISHED'}else{'PARTIAL'})
if($failed.Count -gt 0){exit 2}
