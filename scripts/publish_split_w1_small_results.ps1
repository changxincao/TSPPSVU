param([Parameter(Mandatory=$true)][string]$LocalRoot,
      [Parameter(Mandatory=$true)][string]$RemoteRoot,
      [Parameter(Mandatory=$true)][string]$Java,
      [Parameter(Mandatory=$true)][string]$CplexRoot,
      [Parameter(Mandatory=$true)][string]$MosekRoot,
      [Parameter(Mandatory=$true)][string]$Python,
      [Parameter(Mandatory=$true)][string]$Key,
      [switch]$CheckOnly)
$ErrorActionPreference='Stop';$ProgressPreference='SilentlyContinue'
$ioScript=Join-Path $PSScriptRoot 'experiment_file_io.ps1'
. $ioScript
$ioSource='function Get-ExperimentSha256 {'+(Get-Item Function:\Get-ExperimentSha256).Definition+'}'
$LocalRoot=[IO.Path]::GetFullPath($LocalRoot)
$control=Join-Path $LocalRoot 'control\split_publisher'
[void][IO.Directory]::CreateDirectory($control)
$deployment=Join-Path $LocalRoot 'deployment'
$classpath="$LocalRoot\cleanup_patch\classes;$deployment\bin;$CplexRoot\lib\cplex.jar;$MosekRoot\mosek.jar"
$grid='0.0001,0.00025,0.0005,0.001,0.0025,0.005'
$ssh='C:\Windows\System32\OpenSSH\ssh.exe';$scp='C:\Windows\System32\OpenSSH\scp.exe'
$connection=@('-6','-o','HostKeyAlias=100.71.236.93','-o','BatchMode=yes','-o','ConnectTimeout=15','-o','ServerAliveInterval=15','-o','ServerAliveCountMax=2','-i',$Key)
$address='codex-runner@fd7a:115c:a1e0::d2b:ec5e'
$scpAddress='codex-runner@[fd7a:115c:a1e0::d2b:ec5e]'
function Remote([string]$Code){
    $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes("`$ErrorActionPreference='Stop';`$ProgressPreference='SilentlyContinue';"+$ioSource+"`n"+$Code))
    $output=& $ssh @connection $address powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand $encoded 2>&1
    if($LASTEXITCODE){throw "Remote publish failed: $output"}
    return ($output|Out-String)
}
function Audit([int]$Rep){
    $name='rep_{0:D3}'-f$Rep
    $argsList=@('-Xmx2g',"-Dtrb.svu.python=$Python","-Djava.library.path=$CplexRoot\bin\x64_win64",'-cp',$classpath,
        'Test.analysis.synthetic.TRBSVUExperiment2IdeMain','--check-complete',"$LocalRoot\inputs\main_input\$name",
        "$LocalRoot\frozen_rf\$name\validation\experiment1_selected_context.csv", "$LocalRoot\results\primary\C-W1\$name",
        "$Rep",'4','0','PRIMARY','C-W1',$grid)
    $p=Start-Process -FilePath $Java -ArgumentList (@($argsList|ForEach-Object{'"'+$_+'"'})-join' ') -WorkingDirectory $deployment -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput "$control\audit_$name.stdout.log" -RedirectStandardError "$control\audit_$name.stderr.log"
    $handle=$p.Handle;$p.WaitForExit();$code=$p.ExitCode;$p.Dispose()
    if($code){throw "Small output audit failed rep=$Rep code=$code"}
}
function Publish([int]$Rep){
    Audit $Rep
    $name='rep_{0:D3}'-f$Rep
    $archive=Join-Path $control "$name.tgz"
    & tar.exe -czf $archive -C "$LocalRoot\results\primary\C-W1" $name
    if($LASTEXITCODE){throw 'Archive failed'}
    $sha=Get-ExperimentSha256 $archive
    $markerSha=Get-ExperimentSha256 "$LocalRoot\results\primary\C-W1\$name\complete.txt"
    $destination="$RemoteRoot\control\small_${name}_${sha}.tgz"
    $unixPath=$destination.Replace('\','/')
    $upload=& $scp @connection $archive "${scpAddress}:$unixPath" 2>&1
    if($LASTEXITCODE){throw "Archive upload failed: $upload"}
    # Rename the whole directory only after extraction finishes. A complete marker
    # therefore never arrives before the remaining source files.
    $code=@"
`$archive='$destination';`$base='$RemoteRoot\w1_split';`$target=Join-Path `$base 'local_small\$name';
if((Get-ExperimentSha256 `$archive)-ne'$sha'){throw 'Transferred archive hash mismatch'};
if(Test-Path -LiteralPath `$target){
    if(-not(Test-Path -LiteralPath "`$target\complete.txt")-or(Get-ExperimentSha256 "`$target\complete.txt")-ne'$markerSha'){throw 'Conflicting published source; preserve and inspect'};
}else{
    `$incoming=Join-Path `$base 'incoming\${name}_$sha';[void][IO.Directory]::CreateDirectory(`$incoming);
    & tar.exe -xzf `$archive -C `$incoming;if(`$LASTEXITCODE){throw 'Extraction failed'};
    `$source=Join-Path `$incoming '$name';
    if((Get-ExperimentSha256 "`$source\complete.txt")-ne'$markerSha'){throw 'Extracted completion marker mismatch'};
    [void][IO.Directory]::CreateDirectory((Split-Path `$target -Parent));
    Move-Item -LiteralPath `$source -Destination `$target;
};
[pscustomobject]@{rep=$Rep;archiveSha256='$sha';markerSha256='$markerSha';published=(Get-Date -Format o);source=`$target}|ConvertTo-Json|Set-Content -LiteralPath "`$base\local_small\${name}_delivery.json" -Encoding UTF8;
Write-Output 'PUBLISHED $name';
"@
    $result=Remote $code
    [pscustomobject]@{rep=$Rep;archiveSha256=$sha;markerSha256=$markerSha;remoteSource="$RemoteRoot\w1_split\local_small\$name";published=(Get-Date -Format o)}|
        ConvertTo-Json|Set-Content -LiteralPath "$control\${name}_published.json" -Encoding UTF8
    Write-Output $result
}
foreach($path in @($Java,$Python,$Key,"$deployment\bin\Test\analysis\synthetic\TRBSVUExperiment2IdeMain.class")){
    if(-not(Test-Path -LiteralPath $path)){throw "Missing publisher dependency $path"}
}
if($RemoteRoot-ne'E:\ccx_work\TSPP_SVU_Contextual\main_grid_completion_20261004\experiment2_20261005'){throw 'Unexpected remote experiment root'}
if($CheckOnly){Write-Output 'PUBLISHER_CHECK_PASS audited complete source only; atomic directory delivery; no solve calls';return}
$env:PATH="$CplexRoot\bin\x64_win64;$MosekRoot;$env:PATH"
$env:OPENBLAS_NUM_THREADS='1';$env:MKL_NUM_THREADS='1';$env:OMP_NUM_THREADS='1'
$lock=[IO.File]::Open("$control\publisher.lock",'OpenOrCreate','ReadWrite','None')
try{
    $jobs=@(foreach($rep in 1..5){[pscustomobject]@{rep=$rep;state=$(if(Test-Path "$control\rep_$('{0:D3}'-f$rep)_published.json"){'PUBLISHED'}else{'WAITING'});lastError=''}})
    while(@($jobs|Where-Object state -ne PUBLISHED).Count){
        foreach($job in $jobs|Where-Object state -ne PUBLISHED){
            $name='rep_{0:D3}'-f$job.rep
            if(-not(Test-Path -LiteralPath "$LocalRoot\results\primary\C-W1\$name\complete.txt")){continue}
            try{Publish $job.rep;$job.state='PUBLISHED';$job.lastError=''}
            catch{$job.lastError=$_.Exception.Message;Write-Output "PUBLISH_RETRY rep=$($job.rep) $($job.lastError)"}
        }
        $state=[pscustomobject]@{updated=(Get-Date -Format o);jobs=$jobs;policy='Local completion audit then publish; remote merge re-audits and reuses only identical parameter/weights'}
        [void](Write-ExperimentJson $state "$control\status.json")
        if(@($jobs|Where-Object state -ne PUBLISHED).Count){Start-Sleep -Seconds 30}
    }
}finally{$lock.Dispose()}
