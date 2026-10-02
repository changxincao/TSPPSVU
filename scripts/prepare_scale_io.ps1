param(
    [Parameter(Mandatory=$true)][string]$OutputRoot,
    [string]$Java = 'java',
    [string]$Javac = 'javac',
    [Parameter(Mandatory=$true)][string]$CplexJar,
    [Parameter(Mandatory=$true)][string]$MosekJar,
    [string]$Configuration = ''
)
# Local preparation only: compile, no-solver self-check, and task manifest. Never dispatches workers.
$ErrorActionPreference = 'Stop'
if (-not $Configuration) { $Configuration = Join-Path $PSScriptRoot 'scale_experiment_pending.properties' }
$repo = Split-Path $PSScriptRoot -Parent
$root = [IO.Path]::GetFullPath($OutputRoot)
if (Test-Path -LiteralPath $root) { throw "Use a new preparation directory: $root" }
foreach ($file in @($CplexJar, $MosekJar, $Configuration)) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing file: $file" }
}
$classes = Join-Path $root 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$dependencies = "$(Join-Path $repo 'bin');$CplexJar;$MosekJar"
$sources = @(
    'src/Test/analysis/synthetic/TRBSVUScaleExperiment.java',
    'src/Test/analysis/synthetic/TRBSVUScaleIoSelfCheck.java',
    'src/Test/analysis/synthetic/TRBSVUSyntheticCaseIO.java'
) | ForEach-Object { Join-Path $repo $_ }
& $Javac --release 21 -encoding UTF-8 -cp $dependencies -sourcepath (Join-Path $repo 'src') -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw "Compilation failed ($LASTEXITCODE)" }
& $Java -cp "$classes;$dependencies" Test.analysis.synthetic.TRBSVUScaleIoSelfCheck (Join-Path $PSScriptRoot 'scale_experiment_pending.properties')
if ($LASTEXITCODE -ne 0) { throw "IO self-check failed ($LASTEXITCODE)" }
& $Java -cp "$classes;$dependencies" Test.analysis.synthetic.TRBSVUScaleExperiment plan (Join-Path $root 'plan') $Configuration
if ($LASTEXITCODE -ne 0) { throw "Plan preparation failed ($LASTEXITCODE)" }
Write-Output "PREPARED_ONLY root=$root formalInstances=0 nativeSolves=0 remoteChanges=0"
