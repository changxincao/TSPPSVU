param(
    [string]$Bundle = 'analysis_runs/olist_five_markets_20261001',
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$CplexJar,
    [Parameter(Mandatory=$true)][string]$CplexNative,
    [Parameter(Mandatory=$true)][string]$MosekJar,
    [string]$Python = '.venv-rsome/Scripts/python.exe'
)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
Push-Location $project
try {
    $Bundle = [IO.Path]::GetFullPath($Bundle)
    $Python = [IO.Path]::GetFullPath($Python)
    $classes = Join-Path $Bundle 'runtime/classes'
    New-Item -ItemType Directory -Force -Path $classes, (Join-Path $Bundle 'scripts') | Out-Null
    if (Test-Path -LiteralPath (Join-Path $Bundle 'results')) { throw 'Build into a fresh directory; do not replace runtime of existing results.' }
    $cp = "bin;$CplexJar;$MosekJar"
    $entrySources = @('OlistContextualData','OlistContextualRunner','OlistContextualBatchMain','OlistContextualSelfCheck') | ForEach-Object { "src/Test/analysis/brazil/$_.java" }
    & "$JavaHome/bin/javac.exe" --release 21 -encoding UTF-8 -cp $cp -d bin @entrySources
    if ($LASTEXITCODE -ne 0) { throw 'Entry compilation failed' }
    $entries = @('bin/Test/analysis/brazil/OlistContextualBatchMain.class', 'bin/Test/analysis/brazil/OlistContextualRunner.class')
    $dependencies = & "$JavaHome/bin/jdeps.exe" -recursive -verbose:class -filter:none --class-path $cp @entries
    if ($LASTEXITCODE -ne 0) { throw 'Dependency discovery failed' }
    $names = New-Object 'System.Collections.Generic.HashSet[string]'
    foreach ($line in $dependencies) {
        if ($line -match '^\s*(\S+)\s+->\s+(\S+)\s+') {
            foreach ($name in @($Matches[1], $Matches[2])) {
                if ($name -match '^(Basic|Helper|Model|Test)\.') { [void]$names.Add(($name -split '\$')[0]) }
            }
        }
    }
    $catalog = @{}
    foreach ($source in (Get-ChildItem src -Recurse -Filter '*.java' -File)) {
        $text = [IO.File]::ReadAllText($source.FullName)
        if ($text -match '(?m)^\s*package\s+([\w.]+)\s*;') {
            $package = $Matches[1]
            $declarations = [regex]::Matches($text, '(?m)^(?:(?:public|final|abstract|sealed)\s+)*(?:class|interface|enum|record)\s+(\w+)')
            foreach ($declaration in $declarations) {
                $name = $package + '.' + $declaration.Groups[1].Value
                if (!$catalog.ContainsKey($name)) { $catalog[$name] = @() }
                $catalog[$name] += $source.FullName
            }
        }
    }
    $sources = foreach ($name in $names) {
        $standard = Join-Path $project ('src/' + $name.Replace('.', '/') + '.java')
        if (Test-Path -LiteralPath $standard) { $standard }
        elseif ($catalog.ContainsKey($name) -and $catalog[$name].Count -eq 1) { $catalog[$name][0] }
        else { throw "Missing/ambiguous dependency source: $name" }
    }
    # Recompile the complete project dependency closure: never ship Java22/major66 bin files.
    $sources = @($sources | Sort-Object -Unique)
    & "$JavaHome/bin/javac.exe" --release 21 -encoding UTF-8 -cp $cp -d $classes @sources
    if ($LASTEXITCODE -ne 0) { throw 'Portable Java21 compilation failed' }
    $portableCp = "$classes;$CplexJar;$MosekJar"
    $verify = & "$JavaHome/bin/jdeps.exe" -recursive -verbose:class -filter:none --class-path $portableCp (Join-Path $classes 'Test/analysis/brazil/OlistContextualBatchMain.class') (Join-Path $classes 'Test/analysis/brazil/OlistContextualRunner.class')
    if ($LASTEXITCODE -ne 0 -or @($verify | Where-Object { $_ -match '->\s+(Basic|Helper|Model|Test)\..*not found' }).Count) { throw 'Incomplete portable dependency closure' }
    foreach ($file in (Get-ChildItem $classes -Recurse -Filter '*.class' -File)) {
        $bytes = [IO.File]::ReadAllBytes($file.FullName)
        $major = 256 * $bytes[6] + $bytes[7]
        if ($major -gt 65) { throw "Not Java21 compatible: $($file.FullName) major=$major" }
    }
    Copy-Item -LiteralPath 'analysis/trb_svu/rf_leaf_weights.py' -Destination (Join-Path $Bundle 'scripts/rf_leaf_weights.py')
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'run_olist_batch.ps1'), (Join-Path $PSScriptRoot 'start_olist_detached.ps1') -Destination (Join-Path $Bundle 'scripts')
    & "$JavaHome/bin/java.exe" -cp $portableCp Test.analysis.brazil.OlistContextualBatchMain prepare $Bundle
    if ($LASTEXITCODE -ne 0) { throw 'Frozen market preparation failed' }
    & "$JavaHome/bin/java.exe" -cp $portableCp Test.analysis.brazil.OlistContextualBatchMain check $Bundle
    if ($LASTEXITCODE -ne 0) { throw 'Frozen market audit failed' }
    $config = [ordered]@{ java="$JavaHome/bin/java.exe"; cplexJar=$CplexJar; cplexNative=$CplexNative;
        mosekJar=$MosekJar; python=$Python; maxParallel=4; solverThreads=4; limitSeconds=14400; heap='2g'; maxAttempts=2 }
    [IO.File]::WriteAllText((Join-Path $Bundle 'config.json'), ($config | ConvertTo-Json), (New-Object Text.UTF8Encoding($false)))
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'olist_bundle_README.md') -Destination (Join-Path $Bundle 'README.md')
    $javaVersion = (& "$JavaHome/bin/java.exe" -version 2>&1 | Out-String).Trim()
    $revision = (& git rev-parse HEAD | Out-String).Trim()
    $info = "prepared=$(Get-Date -Format o)`ncompile=javac --release 21 -encoding UTF-8`nclassMajorMaximum=65`nsourceCount=$($sources.Count)`nsourceBaseCommit=$revision`nlocalUncommittedChangesMayBeIncluded=true`njava=$javaVersion`ncplexJarSha256=$((Get-FileHash $CplexJar).Hash)`n"
    [IO.File]::WriteAllText((Join-Path $Bundle 'build_info.txt'), $info, (New-Object Text.UTF8Encoding($false)))
    $manifest = "path`tsha256`n"
    foreach ($file in (Get-ChildItem $Bundle -Recurse -File | Where-Object { $_.FullName -ne (Join-Path $Bundle 'payload_manifest.tsv') })) {
        $relative = $file.FullName.Substring($Bundle.Length + 1)
        $manifest += "$relative`t$((Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLower())`n"
    }
    [IO.File]::WriteAllText((Join-Path $Bundle 'payload_manifest.tsv'), $manifest, (New-Object Text.UTF8Encoding($false)))
    Write-Host "BUNDLE_READY $Bundle sources=$($sources.Count) Java21_compatible no_formal_solves"
} finally { Pop-Location }
