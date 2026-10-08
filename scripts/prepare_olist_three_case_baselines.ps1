param(
    [Parameter(Mandatory=$true)][string]$SourceRoot,
    [Parameter(Mandatory=$true)][string]$Root
)
# Preparation only: never launches a scheduler or an optimizer.
$ErrorActionPreference = 'Stop'
$SourceRoot = (Resolve-Path -LiteralPath $SourceRoot).Path
$Root = [IO.Path]::GetFullPath($Root)
if (Test-Path -LiteralPath $Root) { throw "Use a new supplement directory; refusing to overwrite: $Root" }
$cfg = Get-Content -LiteralPath (Join-Path $SourceRoot 'config.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if ($cfg.includeTrend -ne $true -or $cfg.fixedTrend104 -ne $true) {
    throw 'Expected the frozen fixed-calendar-trend Olist batch.'
}
if ($cfg.solverThreads -ne 4 -or $cfg.maxParallel -ne 4) {
    throw 'Expected four solver threads and four parallel workers; do not silently change the source protocol.'
}
$rows = @(Import-Csv -LiteralPath (Join-Path $SourceRoot 'inputs/markets.tsv') -Delimiter "`t")
$selected = @()
$references = @()
for ($index = 1; $index -le 3; $index++) {
    $market = 'market_{0:D3}' -f $index
    $matches = @($rows | Where-Object { $_.market -eq $market })
    if ($matches.Count -ne 1) { throw "Missing/duplicate source market: $market" }
    $row = $matches[0]
    if ([long]$row.market_seed -ne (351022 + $index) -or [long]$row.rf_seed -ne 20261020) {
        throw "Unexpected frozen seed for $market"
    }
    # Do not accept arbitrary manifest paths outside the selected input directory.
    if ($row.instance.Replace('\','/') -ne "inputs/$market/instance.tsv") { throw "Unexpected input path: $($row.instance)" }
    $inputFile = Join-Path $SourceRoot $row.instance
    $inputHash = (Get-FileHash -LiteralPath $inputFile -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($inputHash -ne $row.sha256) { throw "Frozen input hash mismatch: $market" }
    $resultRoot = Join-Path $SourceRoot "results/$market"
    $protocol = [IO.File]::ReadAllText((Join-Path $resultRoot 'protocol.txt'))
    foreach ($expected in @("inputSha256=$inputHash", "marketSeed=$($row.market_seed)",
            'includeTrend=true', 'trendFeature=FIXED_ONE_BASED_WEEK_DIV_104_NO_WINDOW_SCALING')) {
        if (@($protocol -split '\r?\n') -notcontains $expected) { throw "RF protocol mismatch: $market $expected" }
    }
    for ($trial = 0; $trial -lt 51; $trial++) {
        $rf = Join-Path $resultRoot ('trial_{0:D3}/RF' -f $trial)
        if (Test-Path -LiteralPath (Join-Path $rf 'failure.txt')) { throw "Existing RF failure: $rf" }
        foreach ($relative in @('complete.txt','selection.tsv','candidates.tsv','final_result.tsv',
                'final/result.tsv','final/incumbent.tsv','final/weights.tsv','final/model_scenarios.tsv',
                'final/max_scaling.tsv','final/lane_oos.tsv','final/carrier_oos.tsv','final/logs/cplex.log')) {
            $file = Join-Path $rf $relative
            if (!(Test-Path -LiteralPath $file -PathType Leaf) -or (Get-Item -LiteralPath $file).Length -eq 0) {
                throw "Incomplete RF reference: $file"
            }
        }
        if ((Get-FileHash -LiteralPath (Join-Path $rf 'final_result.tsv')).Hash -ne
                (Get-FileHash -LiteralPath (Join-Path $rf 'final/result.tsv')).Hash) {
            throw "RF final result copies disagree: $rf"
        }
        $references += [pscustomobject]@{
            market=$market; market_seed=$row.market_seed; trial=$trial; method='RF'
            result=(Join-Path $rf 'final_result.tsv')
            sha256=(Get-FileHash -LiteralPath (Join-Path $rf 'final_result.tsv')).Hash.ToLowerInvariant()
            input_sha256=$inputHash
        }
    }
    $selected += $row
}
$scriptNames = @('run_olist_batch.ps1','start_olist_detached.ps1','olist_windows_process.ps1')
foreach ($name in $scriptNames) {
    if (!(Test-Path -LiteralPath (Join-Path $PSScriptRoot $name) -PathType Leaf)) { throw "Missing scheduler script: $name" }
}
$classes = Join-Path $SourceRoot 'runtime/classes'
foreach ($relative in @('Test/analysis/brazil/OlistContextualRunner.class','Test/analysis/brazil/OlistContextualBatchMain.class')) {
    if (!(Test-Path -LiteralPath (Join-Path $classes $relative) -PathType Leaf)) { throw "Missing frozen runtime: $relative" }
}
$rfScript = Join-Path $SourceRoot 'scripts/rf_leaf_weights.py'
if (!(Test-Path -LiteralPath $rfScript -PathType Leaf)) { throw 'Missing frozen RF script' }
# Copy the frozen runtime, rather than compiling current sources against old RF results.
New-Item -ItemType Directory -Path (Join-Path $Root 'runtime'), (Join-Path $Root 'scripts'), (Join-Path $Root 'inputs') -Force | Out-Null
Copy-Item -LiteralPath $classes -Destination (Join-Path $Root 'runtime/classes') -Recurse
if (Test-Path -LiteralPath (Join-Path $SourceRoot 'runtime/classes_manifest.csv')) {
    Copy-Item -LiteralPath (Join-Path $SourceRoot 'runtime/classes_manifest.csv') -Destination (Join-Path $Root 'runtime/classes_manifest.csv')
}
foreach ($row in $selected) {
    $directory = Join-Path $Root "inputs/$($row.market)"
    New-Item -ItemType Directory -Path $directory | Out-Null
    Copy-Item -LiteralPath (Join-Path $SourceRoot $row.instance) -Destination (Join-Path $directory 'instance.tsv')
}
# Java's frozen manifest reader splits literal tabs; Export-Csv would add quotes.
$manifestLines = @("market`tmarket_seed`trf_seed`tinstance`tsha256")
$manifestLines += @($selected | ForEach-Object { @($_.market,$_.market_seed,$_.rf_seed,$_.instance,$_.sha256) -join "`t" })
$manifestLines | Set-Content -LiteralPath (Join-Path $Root 'inputs/markets.tsv') -Encoding UTF8
# RF is read from the original complete results, never enqueued again or overwritten.
$cfg | Add-Member -NotePropertyName methods -NotePropertyValue @('D','SAA','EXP') -Force
$cfg | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $Root 'config.json') -Encoding UTF8
foreach ($name in $scriptNames) { Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $Root "scripts/$name") }
Copy-Item -LiteralPath $rfScript -Destination (Join-Path $Root 'scripts/rf_leaf_weights.py')
$references | Export-Csv -LiteralPath (Join-Path $Root 'rf_result_references.tsv') -Delimiter "`t" -NoTypeInformation -Encoding UTF8
[pscustomobject]@{
    sourceRoot=$SourceRoot; preparedAt=(Get-Date -Format o); markets=@($selected.market)
    marketSeeds=@($selected.market_seed); newMethods=@('D','SAA','EXP'); reusedMethod='RF'
    newTasks=9; newFinalSolves=459; reusedRfWeeks=153; optimizerStarted=$false
    rfReferenceValidation='Files/protocol/hashes checked; full task audit remains required before reporting.'
} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $Root 'supplement_manifest.json') -Encoding UTF8
Write-Host "PREPARED: 001/002/003; nine D/SAA/EXP tasks; 153 existing RF weeks referenced; no solves started."
Write-Host "Preflight (no solves): & '$Root/scripts/run_olist_batch.ps1' -Root '$Root' -CheckOnly"
Write-Host "Start only when authorized: & '$Root/scripts/start_olist_detached.ps1' -Root '$Root'"
