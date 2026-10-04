param([Parameter(Mandatory=$true)][string]$TaskRoot,
      [Parameter(Mandatory=$true)][string]$MainRoot,
      [Parameter(Mandatory=$true)][string]$DistributionRoot)
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
New-Item -ItemType Directory -Force -Path (Join-Path $DistributionRoot 'control') | Out-Null
try {
    # This controller is detached but does not launch any solver while main work is active.
    while($true) {
        $mainStatus=Join-Path $MainRoot 'control\status.json'
        if(Test-Path -LiteralPath $mainStatus) {
            $main=Get-Content -LiteralPath $mainStatus -Raw | ConvertFrom-Json
            if($main.state -in @('FINISHED','PARTIAL') -and $main.running -eq 0) {break}
        }
        if(Test-Path -LiteralPath (Join-Path $MainRoot 'control\fatal_error.txt')) {throw 'Main controller failed before finishing its queue; review its fatal log.'}
        [pscustomobject]@{state='WAITING_FOR_MAIN_QUEUE';updated=[DateTime]::Now.ToString('o');running=0} |
            ConvertTo-Json | Set-Content -LiteralPath (Join-Path $DistributionRoot 'control\status.json') -Encoding UTF8
        Start-Sleep -Seconds 30
    }
    $failedCells=[System.Collections.Generic.List[string]]::new()
    foreach($cell in @('lognormal_cv010030','lognormal_cv040060')) {
        $root=Join-Path $DistributionRoot $cell
        [pscustomobject]@{state='RUNNING';cell=$cell;updated=[DateTime]::Now.ToString('o')} |
            ConvertTo-Json | Set-Content -LiteralPath (Join-Path $DistributionRoot 'control\status.json') -Encoding UTF8
        & (Join-Path $DistributionRoot 'control\run_main_grid_completion_remote.ps1') -TaskRoot $TaskRoot `
            -BaselineRoot $root -OutputRoot $root -ToolsRoot (Join-Path $DistributionRoot 'tools') -MaxParallel 4 -IncludeBaselines
        $state=Get-Content -LiteralPath (Join-Path $root 'control\status.json') -Raw | ConvertFrom-Json
        if($state.state -ne 'FINISHED') {$failedCells.Add($cell)}
    }
    [pscustomobject]@{state=$(if($failedCells.Count -eq 0){'FINISHED'}else{'PARTIAL'});failedCells=@($failedCells.ToArray());updated=[DateTime]::Now.ToString('o');running=0} |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $DistributionRoot 'control\status.json') -Encoding UTF8
} catch {
    $_ | Out-String | Set-Content -LiteralPath (Join-Path $DistributionRoot 'control\fatal_error.txt') -Encoding UTF8
    throw
}
