param([Parameter(Mandatory=$true)][string]$Root,
      [Parameter(Mandatory=$true)][string]$ExperimentRoot,
      [Parameter(Mandatory=$true)][int]$SeedStart)
$ErrorActionPreference='Stop'
$Root=(Resolve-Path -LiteralPath $Root).Path
if(Test-Path -LiteralPath (Join-Path $Root 'inputs')){throw 'Already prepared; do not overwrite frozen inputs/runtime'}
$old=Join-Path $ExperimentRoot 'olist_five_markets_trend_20261002'
$previous=Join-Path $ExperimentRoot 'olist_lambda_extension_20261002'
$cfg=Get-Content -LiteralPath (Join-Path $old 'config.json') -Raw|ConvertFrom-Json
$cfg|Add-Member -Force NoteProperty methods @('RF')
$cfg|Add-Member -Force NoteProperty fixedTrend104 $true
$cfg.includeTrend=$true
$cfg.maxParallel=4; $cfg.solverThreads=4
$cfg|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $Root 'config.json') -Encoding UTF8
New-Item -ItemType Directory -Force -Path (Join-Path $Root 'runtime')|Out-Null
Copy-Item -LiteralPath (Join-Path $previous 'trend/runtime/classes') -Destination (Join-Path $Root 'runtime') -Recurse
Copy-Item -LiteralPath (Join-Path $old 'scripts/rf_leaf_weights.py') -Destination (Join-Path $Root 'scripts')
$classes=Join-Path $Root 'runtime/classes'
$cp="$classes;$($cfg.cplexJar);$($cfg.mosekJar)"
$sources=@(Get-ChildItem -LiteralPath (Join-Path $Root 'src') -Filter '*.java'|ForEach-Object FullName)
& (Join-Path (Split-Path $cfg.java -Parent) 'javac.exe') --release 21 -encoding UTF-8 -cp $cp -d $classes @sources
if($LASTEXITCODE -ne 0){throw 'Java21 compilation failed'}
$hashes=@(foreach($file in Get-ChildItem -LiteralPath $classes -Recurse -Filter '*.class'){
    $bytes=[IO.File]::ReadAllBytes($file.FullName);$major=256*[int]$bytes[6]+[int]$bytes[7]
    if($major -gt 65){throw "Java version mismatch: $($file.FullName)"}
    [pscustomobject]@{file=$file.FullName.Substring($classes.Length+1);major=$major;sha256=(Get-FileHash -LiteralPath $file.FullName).Hash}
})
$hashes|Export-Csv -LiteralPath (Join-Path $Root 'runtime/classes_manifest.csv') -NoTypeInformation -Encoding UTF8
$common=@('-Dolist.includeTrend=true','-Dolist.fixedTrend104=true',"-Dolist.input=$(Join-Path $Root 'source/weekly_demands.csv')",'-cp',$cp)
& $cfg.java @common "-Dolist.marketCount=10" "-Dolist.seedStart=$SeedStart" 'Test.analysis.brazil.OlistContextualBatchMain' prepare $Root
if($LASTEXITCODE -ne 0){throw 'Frozen market generation failed'}
& $cfg.java @common 'Test.analysis.brazil.OlistContextualBatchMain' check $Root
if($LASTEXITCODE -ne 0){throw 'Input check failed'}
$instance=Join-Path $Root 'inputs/market_000/instance.tsv'
foreach($fixed in @('true','false')){
    & $cfg.java @common "-Dolist.fixedTrend104=$fixed" "-Dolist.instance=$instance" "-Dolist.python=$($cfg.python)" "-Dolist.rfScript=$(Join-Path $Root 'scripts/rf_leaf_weights.py')" 'Test.analysis.brazil.OlistContextualSelfCheck'
    if($LASTEXITCODE -ne 0){throw "Window/scaling/RF selfcheck failed fixed=$fixed"}
}
$dro=Join-Path $Root 'dro_rf'
New-Item -ItemType Directory -Force -Path (Join-Path $dro 'runtime')|Out-Null
Copy-Item -LiteralPath $classes -Destination (Join-Path $dro 'runtime') -Recurse
$followup=[pscustomobject]@{previousRoot=$previous;seedStart=$SeedStart;marketCount=10;lambdaGrid='0.01,0.05,0.1,0.25,0.5,1,2,5,10';methods=@('RF','RF_CHI2');trend='FIXED_WEEK_DIV_104'}
$followup|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $Root 'followup.json') -Encoding UTF8
& (Join-Path $PSScriptRoot 'run_olist_rf_followup.ps1') -Root $Root -CheckOnly
Write-Output "PREPARED_FIXED_TREND_RF_DRO seedStart=$SeedStart markets=10 classes=$($hashes.Count)"
