param(
    [string]$Root = "analysis/TRB_reviewer_revision/271_reviewer_literal_olist_centered_oos_full_20260813"
)
$ErrorActionPreference = "Stop"
$rows = Import-Csv -LiteralPath (Join-Path $Root "method_summary.csv")
if ($rows.Count -ne 20) { throw "Expected 20 method rows, found $($rows.Count)." }
function Improvement([double]$baseline, [double]$method) {
    return 100.0 * ($baseline - $method) / $baseline
}
$out = @()
foreach ($cell in @("lognormal_low","lognormal_high","uniform_low","uniform_high")) {
    $slice = @($rows | Where-Object { $_.cell -eq $cell })
    foreach ($comparison in @("SAA-vs-D","CSAA-vs-SAA","RCSAA-vs-CSAA","DRO-vs-CSAA")) {
        $names = $comparison -split "-vs-"
        $method = @($slice | Where-Object { $_.method -eq $names[0] })[0]
        $baseline = @($slice | Where-Object { $_.method -eq $names[1] })[0]
        if ($null -eq $method -or $null -eq $baseline) { throw "Missing method pair $cell $comparison" }
        $out += [pscustomobject]@{
            cell = $cell
            comparison = $comparison
            meanPct = Improvement ([double]$baseline.mean) ([double]$method.mean)
            sdPct = Improvement ([double]$baseline.sd) ([double]$method.sd)
            q95Pct = Improvement ([double]$baseline.q95) ([double]$method.q95)
            cvar95Pct = Improvement ([double]$baseline.cvar95) ([double]$method.cvar95)
        }
    }
}
$out | Export-Csv -LiteralPath (Join-Path $Root "paired_improvements.csv") -NoTypeInformation -Encoding UTF8
$out
