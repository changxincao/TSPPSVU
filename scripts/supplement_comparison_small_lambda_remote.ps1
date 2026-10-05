param([Parameter(Mandatory=$true)][string]$Manifest,
      [Parameter(Mandatory=$true)][string]$SchedulerUpdate,
      [switch]$CheckOnly)
$ErrorActionPreference='Stop';$ProgressPreference='SilentlyContinue'
$Manifest=[IO.Path]::GetFullPath($Manifest)
$control=Split-Path $Manifest -Parent;$root=Split-Path $control -Parent
$plan=Get-Content -LiteralPath $Manifest -Raw|ConvertFrom-Json
if($plan.mode-ne'SECONDARY'){throw 'Small-lambda supplement belongs to the secondary comparison queue'}
$original=@($plan.jobs|Where-Object{$_.kind-eq'comparison'-and$_.id.StartsWith('compare_rep_')})
if($original.Count-ne200){throw 'Expected the original five markets with forty queries each'}
if(@($plan.jobs|Where-Object{$_.id.StartsWith('compare_small_')}).Count){throw 'Supplement already queued; do not add it twice'}
$tokens=$null;$errors=$null
[System.Management.Automation.Language.Parser]::ParseFile($SchedulerUpdate,[ref]$tokens,[ref]$errors)|Out-Null
if($errors.Count){throw ($errors|Out-String)}
. (Join-Path $plan.deployment 'scripts\olist_windows_process.ps1')
$start=Join-Path $plan.deployment 'scripts\start_three_experiments_remote.ps1'
if(-not(Test-Path -LiteralPath $start)){throw 'Missing detached launcher'}
$newJobs=[Collections.Generic.List[object]]::new()
$hashes=[Collections.Generic.List[object]]::new()
foreach($job in $original){
    $rep='rep_{0:D3}'-f[int]$job.rep
    $query=Split-Path $job.output -Leaf
    $folder=Join-Path $root "comparison_small_lambda\input\$rep\$query"
    $config=Join-Path $folder 'config.properties'
    $text=[IO.File]::ReadAllText($job.config)
    if(@([regex]::Matches($text,'(?m)^lambdaGrid=.*$')).Count-ne1){throw "Invalid original grid: $($job.id)"}
    $text=[regex]::Replace($text,'(?m)^lambdaGrid=[^\r\n]*','lambdaGrid=0.001,0.005')
    if($text-notmatch'(?m)^solverThreads=4\r?$'-or$text-notmatch'(?m)^timeLimit=UNLIMITED\r?$'){
        throw "Unexpected original solve settings: $($job.id)"
    }
    foreach($path in @($job.input,$job.weights)){
        if(-not[IO.File]::Exists($path)-or([IO.FileInfo]$path).Length-eq0){throw "Missing frozen source: $path"}
    }
    New-Item -ItemType Directory -Force -Path $folder|Out-Null
    if(Test-Path -LiteralPath $config){
        if([IO.File]::ReadAllText($config)-cne$text){throw "Supplement configuration differs: $config"}
    }else{[IO.File]::WriteAllText($config,$text,[Text.UTF8Encoding]::new($false))}
    $output=Join-Path $root "comparison_small_lambda\results\$rep\$query"
    $newJobs.Add([pscustomobject]@{id="compare_small_${rep}_$query";lane='comparison';rank=-1;
        kind='comparison';method=$job.method;rep=$job.rep;input=$job.input;choice='';output=$output;
        depends=@();config=$config;weights=$job.weights})
    foreach($path in @($job.input,$job.weights,$job.config,$config)){
        $hashes.Add([pscustomobject]@{task="compare_small_${rep}_$query";path=$path;
            sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash})
    }
}
$allJobs=@($plan.jobs)+@($newJobs.ToArray())
if(@($allJobs.id|Select-Object -Unique).Count-ne$allJobs.Count){throw 'Duplicate supplement task ids'}
$hashes|Export-Csv -LiteralPath "$control\small_lambda_input_hashes.csv" -NoTypeInformation -Encoding UTF8
Write-Output "SUPPLEMENT_CHECK_PASS original=$($plan.jobs.Count) added=$($newJobs.Count) total=$($allJobs.Count) lambdas=0.001,0.005 threads=4 parallel=4"
if($CheckOnly){return}
$receipt=Get-Content -LiteralPath "$control\controller_start.json" -Raw|ConvertFrom-Json
$scheduler=Join-Path $plan.deployment 'scripts\run_three_experiments_remote.ps1'
$command=[OlistWindowsProcess]::CommandLine($receipt.pid)
if(-not$command.Contains($Manifest)-or-not$command.Contains($scheduler)){throw 'Controller identity mismatch; no processes stopped'}
$live=@(Get-Process java -ErrorAction SilentlyContinue|ForEach-Object{
    $cmd=[OlistWindowsProcess]::CommandLine($_.Id)
    if($cmd.Contains($plan.deployment)){[pscustomobject]@{pid=$_.Id;command=$cmd}}
})
foreach($worker in $live){
    if(@($plan.jobs|Where-Object{$worker.command.Contains('"'+$_.output+'"')}).Count-ne1){
        throw 'Live worker is not uniquely owned by this manifest'
    }
}
$backup=Join-Path $control ('small_lambda_revision_'+(Get-Date -Format yyyyMMdd_HHmmss))
New-Item -ItemType Directory -Path $backup|Out-Null
foreach($file in @('manifest.json','status.json','controller_start.json')){
    Copy-Item -LiteralPath "$control\$file" -Destination $backup
}
Copy-Item -LiteralPath $scheduler -Destination "$backup\run_three_experiments_remote.ps1"
# Replace only the controller. Existing Java model/OOS processes remain alive.
Stop-Process -Id $receipt.pid -Force
$deadline=(Get-Date).AddSeconds(10)
do{
    try{$gate=[IO.File]::Open("$control\scheduler.lock",'OpenOrCreate','ReadWrite','None');$gate.Dispose();break}
    catch{if((Get-Date)-gt$deadline){throw};Start-Sleep -Milliseconds 200}
}while($true)
Copy-Item -LiteralPath $SchedulerUpdate -Destination $scheduler -Force
$plan.jobs=$allJobs
$plan|ConvertTo-Json -Depth 10|Set-Content -LiteralPath "$Manifest.tmp" -Encoding UTF8
Move-Item -LiteralPath "$Manifest.tmp" -Destination $Manifest -Force
& $start -Manifest $Manifest
$preserved=@($live|Where-Object{
    try{[OlistWindowsProcess]::CommandLine($_.pid)-eq$_.command}catch{$false}
})
[pscustomobject]@{time=(Get-Date -Format o);addedTasks=$newJobs.Count;lambdas=@(0.001,0.005);
    originalTasks=@($original.id);preservedWorkers=@($preserved.pid);workersBefore=@($live.pid);
    backup=$backup;outputRoot=(Join-Path $root 'comparison_small_lambda');
    runtimeUnchanged=$true;cvUnchanged=$true;existingResultsUnchanged=$true;parallel=4;solverThreads=4}|
    ConvertTo-Json -Depth 6|Set-Content -LiteralPath "$control\small_lambda_supplement.json" -Encoding UTF8
Write-Output "SUPPLEMENT_QUEUED added=$($newJobs.Count) preserved=$($preserved.Count)/$($live.Count)"
