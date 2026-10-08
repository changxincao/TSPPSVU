$ErrorActionPreference = 'Stop'
$sandbox = Join-Path ([IO.Path]::GetTempPath()) ('olist_baseline_test_' + [guid]::NewGuid().ToString('N'))
$source = Join-Path $sandbox 'source'
function FixtureFile([string]$Path,[string]$Text) {
    New-Item -ItemType Directory -Force -Path (Split-Path $Path -Parent) | Out-Null
    [IO.File]::WriteAllText($Path,$Text)
}
function Assert($Condition,[string]$Message) { if (!$Condition) { throw $Message } }
function MustFail([scriptblock]$Action,[string]$Pattern) {
    $caught=$false
    try { & $Action } catch { $caught=$true; Assert ($_.Exception.Message -like $Pattern) "Unexpected failure: $_" }
    Assert $caught "Expected rejection: $Pattern"
}
try {
    FixtureFile (Join-Path $source 'config.json') '{"includeTrend":true,"fixedTrend104":true,"solverThreads":4,"maxParallel":4,"methods":["RF"],"limitSeconds":14400,"rfSeed":20261020}'
    FixtureFile (Join-Path $source 'runtime/classes/Test/analysis/brazil/OlistContextualRunner.class') 'frozen-runner'
    FixtureFile (Join-Path $source 'runtime/classes/Test/analysis/brazil/OlistContextualBatchMain.class') 'frozen-batch'
    FixtureFile (Join-Path $source 'scripts/rf_leaf_weights.py') 'frozen-rf-script'
    $manifest=@("market`tmarket_seed`trf_seed`tinstance`tsha256")
    foreach ($i in 1..3) {
        $market='market_{0:D3}' -f $i
        $seed=351022+$i
        $instance="inputs/$market/instance.tsv"
        FixtureFile (Join-Path $source $instance) "frozen-input-$seed"
        $hash=(Get-FileHash -LiteralPath (Join-Path $source $instance)).Hash.ToLowerInvariant()
        $manifest+="$market`t$seed`t20261020`t$instance`t$hash"
        FixtureFile (Join-Path $source "results/$market/protocol.txt") "inputSha256=$hash`nmarketSeed=$seed`nincludeTrend=true`ntrendFeature=FIXED_ONE_BASED_WEEK_DIV_104_NO_WINDOW_SCALING`n"
        foreach ($trial in 0..50) {
            $rf=Join-Path $source ('results/{0}/trial_{1:D3}/RF' -f $market,$trial)
            foreach ($name in @('complete.txt','selection.tsv','candidates.tsv','final_result.tsv',
                    'final/result.tsv','final/incumbent.tsv','final/weights.tsv','final/model_scenarios.tsv',
                    'final/max_scaling.tsv','final/lane_oos.tsv','final/carrier_oos.tsv','final/logs/cplex.log')) {
                FixtureFile (Join-Path $rf $name) 'same-fixture-content'
            }
        }
    }
    FixtureFile (Join-Path $source 'inputs/markets.tsv') ($manifest -join "`n")
    $before=@(Get-ChildItem -LiteralPath $source -Recurse -File | Get-FileHash | Select-Object Path,Hash | ConvertTo-Csv -NoTypeInformation)
    $target=Join-Path $sandbox 'supplement'
    & (Join-Path $PSScriptRoot 'prepare_olist_three_case_baselines.ps1') -SourceRoot $source -Root $target
    $config=Get-Content -LiteralPath (Join-Path $target 'config.json') -Raw | ConvertFrom-Json
    Assert (($config.methods -join ',') -eq 'D,SAA,EXP') 'RF/DRO must not be enqueued'
    Assert ($config.fixedTrend104 -and $config.maxParallel -eq 4 -and $config.solverThreads -eq 4) 'Protocol changed'
    $lines=@(Get-Content -LiteralPath (Join-Path $target 'inputs/markets.tsv'))
    Assert ($lines.Count -eq 4 -and !$lines[1].Contains('"')) 'Java manifest must contain three unquoted tab-separated rows'
    Assert (($lines[1] -split "`t")[1] -eq '351023') 'Seed changed'
    $refs=@(Import-Csv -LiteralPath (Join-Path $target 'rf_result_references.tsv') -Delimiter "`t")
    Assert ($refs.Count -eq 153) 'All RF weeks must be referenced'
    Assert (!(Test-Path -LiteralPath (Join-Path $target 'results'))) 'Preparation must not invent or copy solve outputs'
    $after=@(Get-ChildItem -LiteralPath $source -Recurse -File | Get-FileHash | Select-Object Path,Hash | ConvertTo-Csv -NoTypeInformation)
    Assert (($before -join "`n") -eq ($after -join "`n")) 'Source artifacts changed'
    MustFail { & (Join-Path $PSScriptRoot 'prepare_olist_three_case_baselines.ps1') -SourceRoot $source -Root $target } '*refusing to overwrite*'
    FixtureFile (Join-Path $source 'results/market_003/trial_050/RF/failure.txt') 'failed'
    $rejected=Join-Path $sandbox 'rejected'
    MustFail { & (Join-Path $PSScriptRoot 'prepare_olist_three_case_baselines.ps1') -SourceRoot $source -Root $rejected } '*Existing RF failure*'
    Assert (!(Test-Path -LiteralPath $rejected)) 'Invalid source must be rejected before staging'
    FixtureFile (Join-Path $source 'inputs/market_001/instance.tsv') 'tampered-input'
    MustFail { & (Join-Path $PSScriptRoot 'prepare_olist_three_case_baselines.ps1') -SourceRoot $source -Root $rejected } '*Frozen input hash mismatch*'
    Write-Host 'PASS: three markets, fixed seeds/trend, 153 RF references, nine new tasks, no source mutations/solves, rejection checks.'
} finally {
    $resolved=[IO.Path]::GetFullPath($sandbox)
    $temp=[IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if ($resolved.StartsWith($temp,[StringComparison]::OrdinalIgnoreCase) -and (Split-Path $resolved -Leaf).StartsWith('olist_baseline_test_')) {
        Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction SilentlyContinue
    }
}
