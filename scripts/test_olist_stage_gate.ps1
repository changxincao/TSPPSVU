param([string]$OutputRoot=(Join-Path (Split-Path $PSScriptRoot -Parent) 'analysis_runs'))
$ErrorActionPreference='Stop'
$root=Join-Path $OutputRoot ('olist_stage_gate_selfcheck_'+[guid]::NewGuid().ToString('N'))
$stage=Join-Path $root 'previous';$olist=Join-Path $root 'new_olist'
New-Item -ItemType Directory -Force -Path $olist,(Join-Path $stage 'control')|Out-Null
$runner=Join-Path $PSScriptRoot 'run_olist_after_stage.ps1'
'state=RUNNING','queued=0','running=1'|Set-Content (Join-Path $stage 'control/status.txt')
$a=& $runner -Root $olist -PreviousStage $stage -CheckOnly
if($a.state-ne'WAITING_PREVIOUS_STAGE'){throw 'Running predecessor was not blocked'}
foreach($rep in 0..24){
    $d=Join-Path $stage ('primary\C-Chi2\rep_{0:D3}'-f$rep)
    New-Item -ItemType Directory -Force -Path $d|Out-Null
    'fixture'|Set-Content (Join-Path $d 'complete.txt')
    foreach($q in 0..39){foreach($f in @('query_metadata.txt','solve/experiment2_final_solves.csv','solve/experiment2_final_weights.csv','oos/experiment2_summary.csv','oos/experiment2_draws.csv')){
        $p=Join-Path $d (('queries\query_{0:D3}\'-f$q)+$f)
        [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($p))
        [IO.File]::WriteAllText($p,'fixture')
    }}
}
$a=& $runner -Root $olist -PreviousStage $stage -CheckOnly
if($a.state-ne'WAITING_PREVIOUS_STAGE'){throw 'Markers alone bypassed predecessor state'}
'state=FINISHED','queued=0','running=0'|Set-Content (Join-Path $stage 'control/status.txt')
$a=& $runner -Root $olist -PreviousStage $stage -CheckOnly
if($a.state-ne'READY' -or $a.checkedQueryOutputs-ne1000){throw 'Complete predecessor was not accepted'}
$p=Join-Path $stage 'primary/C-Chi2/rep_024/queries/query_039/oos/experiment2_draws.csv'
[IO.File]::WriteAllText($p,'');$rejected=$false
try {& $runner -Root $olist -PreviousStage $stage -CheckOnly|Out-Null}catch{$rejected=$_.Exception.Message-like'Previous stage marked finished but output missing:*'}
if(!$rejected){throw 'Empty output was accepted'}
Write-Output "PASS: running/markers-only blocked; 25x40 artifacts accepted; empty output rejected. $root"
