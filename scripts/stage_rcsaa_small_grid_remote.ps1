param([Parameter(Mandatory=$true)][string]$Manifest,[Parameter(Mandatory=$true)][string]$Tools,[switch]$CheckOnly)
$ErrorActionPreference='Stop';$ProgressPreference='SilentlyContinue'
$Manifest=[IO.Path]::GetFullPath($Manifest)
$control=Split-Path $Manifest -Parent
$plan=Get-Content -LiteralPath $Manifest -Raw|ConvertFrom-Json
if($plan.mode-ne'MAIN'){throw 'This revision is only for the main-experiment queue'}
. (Join-Path $plan.deployment 'scripts\olist_windows_process.ps1')
$original=@($plan.jobs|Where-Object{$_.method-eq'RCSAA'})
if($original.Count-ne5-or@($original|Where-Object{$_.kind-ne'robust'-or$_.rank-ne0}).Count){throw 'Expected five original, unstaged RCSAA tasks'}
if(-not(Test-Path -LiteralPath "$Tools\bin\Test\analysis\synthetic\TRBSVURcsaaStagedGridMain.class")){throw 'Missing staged driver'}
$staged=@()
foreach($job in $original){
    if(Test-Path -LiteralPath "$($job.output)\complete.txt"){throw "Original RCSAA already complete: $($job.id)"}
    $small=$job|ConvertTo-Json -Depth 6|ConvertFrom-Json
    $full=$job|ConvertTo-Json -Depth 6|ConvertFrom-Json
    $small.id=$job.id+'_le1';$small.kind='rcsaa-staged';$small.output=$job.output+'_le1'
    $small|Add-Member tools $Tools;$small|Add-Member baseline $job.output
    $small|Add-Member oldGrid '0.1,0.25,0.5,1,2,5,10';$small|Add-Member grid '0.1,0.25,0.5,1'
    $full.kind='rcsaa-staged';$full.rank=1;$full.depends=@($small.id)
    $full|Add-Member tools $Tools;$full|Add-Member baseline $small.output
    $full|Add-Member oldGrid $small.grid;$full|Add-Member grid $small.oldGrid
    $staged+=@($small,$full)
}
$proposed=@($staged)+@($plan.jobs|Where-Object{$_.method-ne'RCSAA'})
if(@($proposed|Where-Object{$_.method-ne'RCSAA'-and$_.rank-le1}).Count){throw 'Another method would run ahead of the staged RCSAA query tests'}
$state=Get-Content -LiteralPath "$control\status.json" -Raw|ConvertFrom-Json
$workers=@()
foreach($job in $state.jobs|Where-Object state -eq RUNNING){
    if($job.method-ne'RCSAA'-or$job.id-notin$original.id){throw 'Unexpected active worker; refusing to stop it'}
    $cmd=[OlistWindowsProcess]::CommandLine([int]$job.pid)
    if(-not$cmd.Contains('Test.analysis.synthetic.TRBSVUExperiment2IdeMain')-or-not$cmd.Contains('"'+$job.output+'"')){throw 'Worker identity mismatch'}
    $workers+=@([pscustomobject]@{pid=[int]$job.pid;command=$cmd})
}
$classpath="$Tools\bin;$($plan.deployment)\bin;$($plan.cplexJar);$($plan.deployment)\lib\mosek.jar"
Set-Location -LiteralPath $plan.deployment
foreach($job in $staged|Where-Object rank -eq 0){
    & $plan.java -cp $classpath Test.analysis.synthetic.TRBSVURcsaaStagedGridMain check $job.input $job.baseline $job.output $job.rep $job.choice $job.oldGrid $job.grid
    if($LASTEXITCODE-ne0){throw "Checkpoint preflight failed: $($job.id)"}
}
if($CheckOnly){Write-Output "STAGE_CHECK_PASS tasks=$($proposed.Count) workersToRestart=$($workers.Count)";return}
$receipt=Get-Content -LiteralPath "$control\controller_start.json" -Raw|ConvertFrom-Json
$cmd=[OlistWindowsProcess]::CommandLine([int]$receipt.pid)
if(-not$cmd.Contains($Manifest)-or-not$cmd.Contains('run_three_experiments_remote.ps1')){throw 'Controller identity mismatch'}
$backup=Join-Path $control ('rcsaa_stage_'+(Get-Date -Format yyyyMMdd_HHmmss))
New-Item -ItemType Directory -Path $backup|Out-Null
foreach($f in @('manifest.json','status.json','controller_start.json')){Copy-Item -LiteralPath "$control\$f" -Destination $backup}
Copy-Item -LiteralPath "$($plan.deployment)\scripts\run_three_experiments_remote.ps1" -Destination $backup
Stop-Process -Id ([int]$receipt.pid) -Force
foreach($worker in $workers){
    if([OlistWindowsProcess]::CommandLine($worker.pid)-ne$worker.command){throw 'Worker identity changed'}
    Stop-Process -Id $worker.pid -Force
}
$deadline=(Get-Date).AddSeconds(10)
do{
    try{$gate=[IO.File]::Open("$control\scheduler.lock",'OpenOrCreate','ReadWrite','None');$gate.Dispose();break}
    catch{if((Get-Date)-gt$deadline){throw};Start-Sleep -Milliseconds 200}
}while($true)
Copy-Item -LiteralPath "$Tools\run_three_experiments_remote.ps1" -Destination "$($plan.deployment)\scripts\run_three_experiments_remote.ps1" -Force
$plan.jobs=$proposed
$plan|ConvertTo-Json -Depth 10|Set-Content -LiteralPath "$Manifest.tmp" -Encoding UTF8
Move-Item -LiteralPath "$Manifest.tmp" -Destination $Manifest -Force
[pscustomobject]@{time=(Get-Date -Format o);backup=$backup;restartedWorkers=@($workers.pid);smallGrid='0.1,0.25,0.5,1';supplement='2,5,10';order='all small-grid CV and 40-query tests; full-grid supplement; W1; MM; PCM';solverThreads=4;parallel=4;frozenModelUnchanged=$true}|
    ConvertTo-Json -Depth 5|Set-Content -LiteralPath "$control\rcsaa_staging_receipt.json" -Encoding UTF8
& (Join-Path $plan.deployment 'scripts\start_three_experiments_remote.ps1') -Manifest $Manifest
Write-Output "RCSAA_STAGED tasks=$($proposed.Count) backup=$backup"
