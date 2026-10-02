param([string]$OutputRoot = (Join-Path (Split-Path $PSScriptRoot -Parent) 'analysis_runs'))
$ErrorActionPreference = 'Stop'
$testRoot = Join-Path $OutputRoot ('global_csaa_selection_selfcheck_' + [guid]::NewGuid().ToString('N'))
$originalLocation = Get-Location
$runner = Join-Path $PSScriptRoot 'run_paired_cv_oos_selected_dro_remote.ps1'
$methods = @('CSAA-Exp', 'CSAA-Tri', 'RF-CSAA')
New-Item -ItemType Directory -Path $testRoot | Out-Null
try {
    # Replication winners differ, but there must be one common winner per cell.
    foreach ($case in @(
        @{name='exp'; scores=@(@(11,12,10), @(10,12,20)); winner='CSAA-Exp'},
        @{name='rf'; scores=@(@(10,12,11), @(20,12,10)); winner='RF-CSAA'}
    )) {
        $root = Join-Path $testRoot $case.name
        foreach ($rep in 0..1) {
            foreach ($index in 0..2) {
                $method = $methods[$index]
                $methodRoot = Join-Path $root ('cv030050\experiment1\rep_{0:D3}\{1}' -f $rep,$method)
                foreach ($q in 0..39) {
                    $query = Join-Path $methodRoot ('queries\query_{0:D3}' -f $q)
                    New-Item -ItemType Directory -Force -Path (Join-Path $query 'oos') | Out-Null
                    [pscustomobject]@{method=$method; mean=$case.scores[$rep][$index]} |
                        Export-Csv -LiteralPath (Join-Path $query 'oos\summary.csv') -NoTypeInformation
                    "querySha256=paired-$rep-$q" | Set-Content -LiteralPath (Join-Path $query 'query_metadata.txt')
                }
                $validation = Join-Path $methodRoot 'queries\query_000\validation'
                New-Item -ItemType Directory -Path $validation | Out-Null
                [pscustomobject]@{method=$method; validation_cost=100+$index; parameter=0.25+$rep} |
                    Export-Csv -LiteralPath (Join-Path $validation 'context_candidate.csv') -NoTypeInformation
                'fixture-complete' | Set-Content -LiteralPath (Join-Path $methodRoot 'complete.txt')
            }
        }
        & $runner -TaskRoot $testRoot -ExperimentRoot $root -Cell cv030050 -ReplicationCount 2 -SelectionOnly
        $control = Join-Path $root 'cv030050\experiment2_oos_selected\control'
        $selected = @(Import-Csv -LiteralPath (Join-Path $control 'oos_vs_validation_selection.csv'))
        if ($selected.Count -ne 2 -or @($selected | Where-Object oos_winner -ne $case.winner).Count) {
            throw "Global winner incorrect in $($case.name)"
        }
        foreach ($row in $selected) {
            $source = Join-Path $root ('cv030050\experiment1\rep_{0:D3}\{1}\queries\query_000\validation\context_candidate.csv' -f [int]$row.replication,$case.winner)
            if ((Get-FileHash -LiteralPath $source).Hash -ne (Get-FileHash -LiteralPath $row.selected_context_file).Hash) {
                throw 'Replication-specific hyperparameters were changed'
            }
        }
        $status = Get-Content -LiteralPath (Join-Path $control 'status.json') -Raw | ConvertFrom-Json
        if ($status.state -ne 'SELECTION_COMPLETE' -or $status.contextMethod -ne $case.winner) {
            throw 'Selection-only status incorrect'
        }
        # No solver outputs may be created in preparation mode.
        if (Test-Path -LiteralPath (Join-Path $root 'cv030050\experiment2_oos_selected\primary')) {
            throw 'SelectionOnly unexpectedly launched a solver'
        }
    }
    # Explicit low-CV branches must stay fixed even if OOS favors another family.
    $root = Join-Path $testRoot 'exp'
    foreach ($fixedMethod in @('RF-CSAA','CSAA-Tri')) {
        & $runner -TaskRoot $testRoot -ExperimentRoot $root -Cell cv030050 `
            -ReplicationCount 2 -ContextMethod $fixedMethod -SelectionOnly
        $stage = Join-Path $root ("cv030050\experiment2_fixed_csaa\$fixedMethod")
        $selected = @(Import-Csv -LiteralPath (Join-Path $stage 'control\oos_vs_validation_selection.csv'))
        if ($selected.Count -ne 2 -or @($selected | Where-Object oos_winner -ne $fixedMethod).Count) {
            throw 'Fixed context family changed to an OOS winner'
        }
        if (@($selected | Where-Object winners_agree -ne 'NOT_APPLICABLE_FIXED_FAMILY').Count) {
            throw 'Fixed-family diagnostic fabricated agreement of OOS and validation winners'
        }
        foreach ($row in $selected) {
            $source = Join-Path $root ('cv030050\experiment1\rep_{0:D3}\{1}\queries\query_000\validation\context_candidate.csv' -f [int]$row.replication,$fixedMethod)
            if ((Get-FileHash -LiteralPath $source).Hash -ne (Get-FileHash -LiteralPath $row.selected_context_file).Hash) {
                throw 'Fixed-family validation parameters changed'
            }
            if (-not $row.selected_context_file.StartsWith($stage, [StringComparison]::OrdinalIgnoreCase)) {
                throw 'Fixed branch selection escaped its isolated output directory'
            }
        }
        $status = Get-Content -LiteralPath (Join-Path $stage 'control\status.json') -Raw | ConvertFrom-Json
        if ($status.contextMethod -ne $fixedMethod -or $status.selection -ne 'USER_FIXED_CONTEXT_FAMILY') {
            throw 'Fixed-family status incorrectly labeled'
        }
        if (Test-Path -LiteralPath (Join-Path $stage 'primary')) { throw 'SelectionOnly launched a solver' }
    }
    $rfFile = Join-Path $root 'cv030050\experiment2_fixed_csaa\RF-CSAA\control\selected_contexts\rep_000\experiment1_selected_context_oos.csv'
    $triFile = Join-Path $root 'cv030050\experiment2_fixed_csaa\CSAA-Tri\control\selected_contexts\rep_000\experiment1_selected_context_oos.csv'
    if ((Get-FileHash -LiteralPath $rfFile).Hash -eq (Get-FileHash -LiteralPath $triFile).Hash) {
        throw 'RF and Tri branches mixed their selected context inputs'
    }
    # An incomplete baseline must prevent global selection.
    $bad = Join-Path $testRoot 'rf\cv030050\experiment1\rep_001\CSAA-Exp\queries\query_039\oos\summary.csv'
    Move-Item -LiteralPath $bad -Destination ($bad + '.missing-test')
    $rejected = $false
    try {
        & $runner -TaskRoot $testRoot -ExperimentRoot (Join-Path $testRoot 'rf') -Cell cv030050 -ReplicationCount 2 -SelectionOnly
    } catch { $rejected = $_.Exception.Message -like 'Missing or empty CSV:*' }
    if (-not $rejected) { throw 'Incomplete baseline was not rejected' }
    Write-Output "PASS: global auto choice, fixed RF/Tri isolation, per-rep parameters preserved, no solver launch, missing output rejected. $testRoot"
} finally {
    Set-Location $originalLocation
}
