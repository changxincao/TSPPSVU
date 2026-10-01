param([string]$Root = (Split-Path $PSScriptRoot -Parent),
    [Parameter(Mandatory=$true)][string]$PreviousStage, [switch]$CheckOnly)
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path -LiteralPath $Root).Path
$PreviousStage = (Resolve-Path -LiteralPath $PreviousStage).Path
if ($Root -eq $PreviousStage -or $Root.StartsWith($PreviousStage + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Olist must use an independent directory.'
}
$control = Join-Path $Root 'control'
New-Item -ItemType Directory -Force -Path $control | Out-Null
$lock = [IO.File]::Open((Join-Path $control 'waiter.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
try {
    do {
        $markers = 0
        foreach ($rep in 0..24) {
            if (Test-Path -LiteralPath (Join-Path $PreviousStage ('primary\C-Chi2\rep_{0:D3}\complete.txt' -f $rep))) { $markers++ }
        }
        $status = @(Get-Content -LiteralPath (Join-Path $PreviousStage 'control/status.txt'))
        $ready = $markers -eq 25 -and $status -contains 'state=FINISHED' -and
            $status -contains 'queued=0' -and $status -contains 'running=0'
        $queryOutputs = 0
        if ($ready) {
            foreach ($rep in 0..24) {
                foreach ($q in 0..39) {
                    $directory = Join-Path $PreviousStage ('primary\C-Chi2\rep_{0:D3}\queries\query_{1:D3}' -f $rep,$q)
                    foreach ($file in @('query_metadata.txt','solve/experiment2_final_solves.csv',
                        'solve/experiment2_final_weights.csv','oos/experiment2_summary.csv','oos/experiment2_draws.csv')) {
                        $path = Join-Path $directory $file
                        if (!(Test-Path -LiteralPath $path) -or (Get-Item -LiteralPath $path).Length -eq 0) {
                            throw "Previous stage marked finished but output missing: $path"
                        }
                    }
                    $queryOutputs++
                }
            }
        }
        $snapshot = [pscustomobject]@{state=$(if($ready){'READY'}else{'WAITING_PREVIOUS_STAGE'});
            updated=(Get-Date -Format o);previousStage=$PreviousStage;completedMarkets=$markers;
            checkedQueryOutputs=$queryOutputs;previousStatus=($status -join ';');root=$Root}
        $snapshot | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $control 'wait_status.json') -Encoding UTF8
        if ($CheckOnly) { $snapshot; return }
        if (!$ready) { Start-Sleep -Seconds 60 }
    } while (!$ready)
    # The same verified scheduler is used for direct and deferred execution.
    & (Join-Path $PSScriptRoot 'run_olist_batch.ps1') -Root $Root
} catch {
    [pscustomobject]@{state='FAILED';updated=(Get-Date -Format o);error=$_.Exception.Message} |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $control 'wait_status.json') -Encoding UTF8
    throw
} finally { $lock.Dispose() }
