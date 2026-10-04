param([Parameter(Mandatory=$true)][string]$Manifest)
$ErrorActionPreference='Stop';$ProgressPreference='SilentlyContinue'
$Manifest=[IO.Path]::GetFullPath($Manifest)
$control=Split-Path $Manifest -Parent
$plan=Get-Content -LiteralPath $Manifest -Raw|ConvertFrom-Json
$state=Get-Content -LiteralPath "$control\status.json" -Raw|ConvertFrom-Json
. (Join-Path $plan.deployment 'scripts\olist_windows_process.ps1')
$remove=@($plan.jobs|Where-Object{
    if($plan.mode-eq'MAIN'){return $_.method-eq'C-Chi2'}
    return $_.lane-eq'distribution'-and$_.id.StartsWith('normal_')
})
if(-not$remove.Count){throw 'No duplicate tasks found; refuse an accidental second revision'}
$keep=@($plan.jobs|Where-Object{$_.id-notin@($remove.id)})
foreach($job in $keep){foreach($dep in @($job.depends)){if($dep-notin@($keep.id)){throw "Removing a retained dependency: $dep"}}}
$live=@(Get-Process java -ErrorAction SilentlyContinue|ForEach-Object{
    $cmd=[OlistWindowsProcess]::CommandLine($_.Id)
    if($cmd.Contains($plan.deployment)){[pscustomobject]@{pid=$_.Id;command=$cmd}}
})
foreach($job in $remove){
    if(@($live|Where-Object{$_.command.Contains('"'+$job.output+'"')}).Count){throw "Duplicate task already running; preserve it and inspect: $($job.id)"}
}
$receipt=Get-Content -LiteralPath "$control\controller_start.json" -Raw|ConvertFrom-Json
$controllerCmd=[OlistWindowsProcess]::CommandLine($receipt.pid)
if(-not$controllerCmd.Contains($Manifest)-or-not$controllerCmd.Contains('run_three_experiments_remote.ps1')){throw 'Controller identity mismatch'}
$backup=Join-Path $control ('queue_revision_'+(Get-Date -Format yyyyMMdd_HHmmss))
New-Item -ItemType Directory -Path $backup|Out-Null
foreach($f in @('manifest.json','status.json','controller_start.json')){Copy-Item -LiteralPath "$control\$f" -Destination $backup}
$remove|ConvertTo-Json -Depth 9|Set-Content -LiteralPath "$backup\removed_duplicate_jobs.json" -Encoding UTF8
# Stop the controller only: retained Java processes are not terminated or restarted.
Stop-Process -Id $receipt.pid -Force
$deadline=(Get-Date).AddSeconds(10)
do{
    try{$gate=[IO.File]::Open("$control\scheduler.lock",'OpenOrCreate','ReadWrite','None');$gate.Dispose();break}
    catch{if((Get-Date)-gt$deadline){throw};Start-Sleep -Milliseconds 200}
}while($true)
$plan.jobs=$keep
$plan|ConvertTo-Json -Depth 10|Set-Content -LiteralPath "$Manifest.tmp" -Encoding UTF8
Move-Item -LiteralPath "$Manifest.tmp" -Destination $Manifest -Force
foreach($p in $live){
    $now=[OlistWindowsProcess]::CommandLine($p.pid)
    if($now-ne$p.command){throw 'Retained worker identity changed during queue revision'}
}
& (Join-Path $plan.deployment 'scripts\start_three_experiments_remote.ps1') -Manifest $Manifest
[pscustomobject]@{time=(Get-Date -Format o);removed=@($remove.id);retainedJobs=$keep.Count;
    preservedWorkers=@($live.pid);backup=$backup;reason='User clarified: reuse completed main DRO and Normal experiments; only approximation solves independently'}|
    ConvertTo-Json -Depth 5|Set-Content -LiteralPath "$control\reuse_scope_correction.json" -Encoding UTF8
Write-Output "QUEUE_REVISED removed=$($remove.Count) retained=$($keep.Count) preserved=$($live.Count)"
