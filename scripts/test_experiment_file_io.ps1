$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'experiment_file_io.ps1')
$directory=Join-Path ([IO.Path]::GetTempPath()) ('experiment-io-check-'+[Guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($directory)
$hold=$null
try{
    # The implementation must not depend on Get-FileHash autoloading.
    function Get-FileHash { throw 'Get-FileHash deliberately unavailable in this test' }
    $file=Join-Path $directory 'abc.txt'
    [IO.File]::WriteAllText($file,'abc',[Text.UTF8Encoding]::new($false))
    if((Get-ExperimentSha256 $file)-ne'BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD'){throw 'SHA256 mismatch'}
    $state=Join-Path $directory 'state.json'
    if(-not(Write-ExperimentJson @{revision=1} $state)){throw 'Initial state write failed'}
    $hold=[IO.File]::Open($state,'Open','Read','None')
    if(Write-ExperimentJson @{revision=2} $state 2){throw 'Expected locked snapshot to be deferred'}
    $hold.Dispose();$hold=$null
    if((Get-Content $state -Raw|ConvertFrom-Json).revision-ne1){throw 'Busy state write corrupted previous snapshot'}
    if(-not(Write-ExperimentJson @{revision=2} $state)){throw 'Unlocked state write failed'}
    if((Get-Content $state -Raw|ConvertFrom-Json).revision-ne2){throw 'New state not visible'}
    if(@(Get-ChildItem $directory -Filter '*.tmp').Count){throw 'Leaked temporary state file'}
    foreach($command in @('java --worker input','java "--worker" "input"')){
        if($command-notmatch '(?<!\S)"?--worker"?(?!\S)'){throw 'Quoted/unquoted worker token not recognized'}
    }
    foreach($command in @('java --check-complete input','java --worker-extra input')){
        if($command-match '(?<!\S)"?--worker"?(?!\S)'){throw 'Non-worker process counted as solver'}
    }
    Write-Output "IO_SELF_CHECK_PASS PS=$($PSVersionTable.PSVersion) hash without module; busy snapshot non-fatal; old state preserved; next update succeeds"
}finally{
    if($hold){$hold.Dispose()}
    foreach($file in [IO.Directory]::GetFiles($directory)){[IO.File]::Delete($file)}
    [IO.Directory]::Delete($directory)
}
