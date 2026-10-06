# Loads function definitions only; never imports a live queue or starts a solver.
$ErrorActionPreference='Stop'
$scriptPath=Join-Path $PSScriptRoot 'run_three_experiments_remote.ps1'
$tokens=$null;$parseErrors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($scriptPath,[ref]$tokens,[ref]$parseErrors)
if($parseErrors.Count){throw ($parseErrors|Out-String)}
foreach($name in @('Arguments','AuditArguments','HasFile','Complete','Event')){
    $definition=$ast.FindAll({param($node) $node-is[Management.Automation.Language.FunctionDefinitionAst]},$false)|Where-Object Name -eq $name
    Invoke-Expression $definition.Extent.Text
}
$taskTemp=Join-Path ([IO.Path]::GetTempPath()) ('trb-scheduler-audit-'+[guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($taskTemp)
try{
    $control=$taskTemp;$classpath='C:\test space\bin;C:\test space\solver.jar'
    $plan=[pscustomobject]@{native='C:\test space\native';python='C:\test space\python.exe';deployment=$taskTemp;java=(Get-Process -Id $PID).Path}
    foreach($kind in @('base','robust','comparison','rcsaa-staged')){
        $job=[pscustomobject]@{id=$kind;kind=$kind;input='C:\test space\input';choice='C:\test space\choice.csv';output='C:\test space\output';rep=1;method='RCSAA';config='C:\test space\config';weights='C:\test space\weights';tools='C:\test space\tools';baseline='C:\test space\baseline';oldGrid='0.1,0.25';grid='0.1,0.25,0.5'}
        $argsList=@(AuditArguments $job)
        if('--check-complete'-notin$argsList-or'--worker'-in$argsList){throw "Incorrect audit arguments: $kind"}
        if($kind-eq'rcsaa-staged'-and($argsList[-1]-ne$job.grid-or'Test.analysis.synthetic.TRBSVUExperiment2IdeMain'-notin$argsList)){throw 'Staged audit lost method grid'}
        if(('"'+$job.output+'"')-notin$argsList){throw 'Quoted output path lost'}
    }
    # Substitute a child shell that only returns an exit code; no Java/native solver.
    function AuditArguments($job){return @('-NoProfile','-Command',('"exit '+$script:auditExit+'"'))}
    $job=[pscustomobject]@{id='exit_gate';method='TEST';output=$taskTemp;state='QUEUED';attempt=0}
    if(Complete $job){throw 'Missing marker accepted'}
    [IO.File]::WriteAllText((Join-Path $taskTemp 'complete.txt'),'test marker')
    foreach($code in @(0,1,20)){
        $script:auditExit=$code;$job.state='QUEUED'
        $result=Complete $job
        if($result-ne($code-eq0)){throw "Wrong completion outcome for exit=$code"}
        if(($job.state-eq'BLOCKED')-ne($code-eq20)){throw "Wrong block/retry outcome for exit=$code"}
    }
    $plan.java=Join-Path $taskTemp 'missing-java.exe';$job.state='QUEUED'
    if((Complete $job)-or$job.state-ne'BLOCKED'){throw 'Audit launch failure must not trigger blind recomputation'}
    Write-Output 'SCHEDULER_COMPLETION_SELF_CHECK_PASS arguments, quoting, skip, resume, mismatch, launch failure'
}finally{
    # Exact directory created by this self-check, never an experiment directory.
    $resolvedTask=[IO.Path]::GetFullPath($taskTemp)
    if(-not$resolvedTask.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase)-or
        [IO.Path]::GetFileName($resolvedTask)-notmatch'^trb-scheduler-audit-[0-9a-f]{32}$'){throw 'Unsafe self-check cleanup path'}
    Remove-Item -LiteralPath $resolvedTask -Recurse -Force
}
