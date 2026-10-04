param([Parameter(Mandatory=$true)][string]$TaskRoot,
      [Parameter(Mandatory=$true)][string]$BaselineRoot,
      [Parameter(Mandatory=$true)][string]$OutputRoot,
      [switch]$CsaaOnly)
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
try {
    & (Join-Path $OutputRoot 'control\run_main_grid_completion_remote.ps1') -TaskRoot $TaskRoot `
        -BaselineRoot $BaselineRoot -OutputRoot $OutputRoot -ToolsRoot (Join-Path $OutputRoot 'tools') -MaxParallel 4 -CsaaOnly:$CsaaOnly
    $comparisonArgs=@($BaselineRoot,$OutputRoot)
    if($CsaaOnly){$comparisonArgs+='--csaa-only'}
    & (Join-Path $TaskRoot 'runtime\python\python.exe') (Join-Path $OutputRoot 'control\compare_main_grid_completion.py') @comparisonArgs
    if($LASTEXITCODE -ne 0) {throw 'Post-completion result audit failed; inspect comparison log.'}
} catch {
    $_ | Out-String | Set-Content -LiteralPath (Join-Path $OutputRoot 'control\fatal_error.txt') -Encoding UTF8
    throw
}
