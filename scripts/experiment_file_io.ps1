# Module-independent hashing and non-fatal atomic telemetry for experiment controllers.
function Get-ExperimentSha256([string]$Path) {
    $stream=[IO.File]::OpenRead($Path)
    $hash=[Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($hash.ComputeHash($stream)).Replace('-','') }
    finally { $hash.Dispose();$stream.Dispose() }
}
function Write-ExperimentJson($Value,[string]$Path,[int]$Attempts=10) {
    $temporary=$Path+'.'+[Guid]::NewGuid().ToString('N')+'.tmp'
    $text=$Value|ConvertTo-Json -Depth 10
    try {
        [IO.File]::WriteAllText($temporary,$text,[Text.UTF8Encoding]::new($false))
        for($attempt=1;$attempt-le$Attempts;$attempt++) {
            try {
                if([IO.File]::Exists($Path)){[IO.File]::Replace($temporary,$Path,[NullString]::Value)}
                else {[IO.File]::Move($temporary,$Path)}
                return $true
            } catch {
                if(-not($_.Exception.GetBaseException()-is[IO.IOException])){throw}
                if($attempt-lt$Attempts){Start-Sleep -Milliseconds 100}
            }
        }
        Write-Warning "Status file busy; retained previous snapshot, controller continues: $Path"
        return $false
    } finally {
        if([IO.File]::Exists($temporary)){[IO.File]::Delete($temporary)}
    }
}
