$ErrorActionPreference='Stop'
$tokens=$null;$errors=$null
$runner=Join-Path $PSScriptRoot 'run_three_experiments_remote.ps1'
$ast=[System.Management.Automation.Language.Parser]::ParseFile($runner,[ref]$tokens,[ref]$errors)
if($errors.Count){throw ($errors|Out-String)}
$functions=@($ast.FindAll({param($node) $node-is[System.Management.Automation.Language.FunctionDefinitionAst]-and$node.Name-eq'MainStageBlocked'},$true))
if($functions.Count-ne1){throw 'Expected one MainStageBlocked function'}
. ([scriptblock]::Create($functions[0].Extent.Text))
$match=@($ast.FindAll({param($node) $node-is[System.Management.Automation.Language.FunctionDefinitionAst]-and$node.Name-eq'WorkerCommandMatches'},$true))
if($match.Count-ne1){throw 'Expected one WorkerCommandMatches function'}
. ([scriptblock]::Create($match[0].Extent.Text))
# Output can occur as another task's baseline argument. Match the entire argument tail.
$smallArguments=@('-Xmx2g','Driver','run','"input"','"full-output"','"small-output"','4','"choice"','0.1,0.25,0.5,1,2,5,10','0.1,0.25,0.5,1')
$fullArguments=@('-Xmx2g','Driver','run','"input"','"small-output"','"full-output"','4','"choice"','0.1,0.25,0.5,1','0.1,0.25,0.5,1,2,5,10')
$smallCommand='"java.exe" '+($smallArguments-join' ')
if(-not(WorkerCommandMatches $smallCommand $smallArguments)){throw 'Own worker rejected'}
if(-not(WorkerCommandMatches ($smallCommand+' ') $smallArguments)){throw 'Windows Start-Process trailing space rejected'}
if(WorkerCommandMatches $smallCommand $fullArguments){throw 'Baseline path caused false task adoption'}
if(WorkerCommandMatches ('"java.exe" '+($fullArguments-join' ')) $smallArguments){throw 'Reverse baseline path caused false task adoption'}
if(WorkerCommandMatches ($smallCommand+' unexpected') $smallArguments){throw 'Unexpected arguments accepted'}
function Job($id,$rank,$kind,$state,$depends=@()){
    return [pscustomobject]@{id=$id;rank=$rank;kind=$kind;state=$state;depends=$depends}
}
function Ready($job,$queue){
    if($job.state-ne'QUEUED'){return $false}
    foreach($id in $job.depends){if(@($queue|Where-Object id -eq $id)[0].state-ne'COMPLETE'){return $false}}
    return -not(MainStageBlocked $job $queue)
}
$small1=Job 'small1' 0 'rcsaa-staged' 'COMPLETE'
$small2=Job 'small2' 0 'rcsaa-staged' 'RUNNING'
$full1=Job 'full1' 1 'rcsaa-staged' 'QUEUED' @('small1')
$full2=Job 'full2' 1 'rcsaa-staged' 'QUEUED' @('small2')
$w1=Job 'w1' 2 'robust' 'QUEUED'
$mm=Job 'mm' 3 'robust' 'QUEUED'
$pcm=Job 'pcm' 4 'robust' 'QUEUED'
$queue=@($small1,$small2,$full1,$full2,$w1,$mm,$pcm)
if(-not(Ready $full1 $queue)){throw 'Completed market cannot backfill a supplement'}
if(Ready $full2 $queue){throw 'Supplement bypassed its own unfinished dependency'}
if(Ready $w1 $queue){throw 'W1 overtook pending RCSAA tasks'}
$small2.state='COMPLETE';$full1.state='COMPLETE';$full2.state='RUNNING'
if(Ready $w1 $queue){throw 'W1 overtook a running RCSAA supplement'}
$full2.state='COMPLETE'
if(-not(Ready $w1 $queue)){throw 'W1 blocked after all RCSAA tasks completed'}
if((Ready $mm $queue)-or(Ready $pcm $queue)){throw 'Moment method overtook W1'}
$w1.state='COMPLETE'
if(-not(Ready $mm $queue)){throw 'MM blocked after W1 completed'}
if(Ready $pcm $queue){throw 'PCM overtook MM'}
$mm.state='COMPLETE'
if(-not(Ready $pcm $queue)){throw 'PCM blocked after MM completed'}
$small1.state='FAILED';$full1.state='QUEUED'
if(Ready $full1 $queue){throw 'Supplement bypassed a failed dependency'}
$other=Job 'other' 0 'robust' 'RUNNING'
$small1.state='COMPLETE'
if(Ready $full1 (@($queue)+@($other))){throw 'Backfill exception bypassed a different earlier method'}
# Current five-market snapshot: two active small-grid workers and two supplements fill four slots.
$snapshot=@()
foreach($rep in 1..5){
    $snapshot+=Job "small$rep" 0 'rcsaa-staged' $(if($rep-le3){'COMPLETE'}else{'RUNNING'})
    $snapshot+=Job "full$rep" 1 'rcsaa-staged' 'QUEUED' @("small$rep")
}
$active=@($snapshot|Where-Object state -eq RUNNING).Count
$available=@($snapshot|Where-Object {Ready $_ $snapshot}|Sort-Object rank,id)
$launch=@($available|Select-Object -First (4-$active))
if($active+$launch.Count-ne4-or($launch.id-join',')-ne'full1,full2'){throw 'Four-slot backfill simulation failed'}
Write-Output 'PASS: four-slot backfill; per-market dependencies; RCSAA/W1/MM/PCM order; failed dependencies; other earlier methods.'
