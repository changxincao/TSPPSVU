param(
    [Parameter(Mandatory = $true)][string]$TaskRoot,
    [Parameter(Mandatory = $true)][string]$ExperimentRoot,
    [ValidateRange(1, 4)][int]$MaxParallel = 4
)

$ErrorActionPreference = 'Stop'
$java = 'D:\Java\jdk-21\bin\java.exe'
$native = 'D:\software\IBM\ILOG\CPLEX_Studio2211\cplex\bin\x64_win64;D:\ccx\TSPP_SVU\lib'
$classpath = "$TaskRoot\bin;D:\software\IBM\ILOG\CPLEX_Studio2211\cplex\lib\cplex.jar;D:\ccx\TSPP_SVU\lib\mosek.jar"
$resultRoot = Join-Path $ExperimentRoot 'experiment2_rf_center_20261001'
$control = Join-Path $resultRoot 'control'
New-Item -ItemType Directory -Force -Path $control | Out-Null

$env:Path = "$native;D:\Java\jdk-21\bin;$env:Path"
$env:OMP_NUM_THREADS = '4'
$env:OPENBLAS_NUM_THREADS = '1'
$env:MKL_NUM_THREADS = '1'
Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
Set-Location -LiteralPath $TaskRoot

$lambdaGrid = '0.1,0.25,0.5,1'
$queue = [System.Collections.Generic.Queue[object]]::new()
$running = [System.Collections.Generic.List[object]]::new()
$events = Join-Path $control 'events.csv'

function Add-Event($task, [string]$state, $value) {
    [pscustomobject]@{
        time = [DateTime]::Now.ToString('o')
        rep = $task.rep
        attempt = $task.attempt
        state = $state
        value = $value
    } | Export-Csv -LiteralPath $events -Append -NoTypeInformation -Encoding UTF8
}

function Write-Status([string]$state) {
    @(
        "state=$state"
        "updated=$([DateTime]::Now.ToString('o'))"
        "queued=$($queue.Count)"
        "running=$($running.Count)"
        "parallel=$MaxParallel"
        'solverThreads=4'
        "lambdaGrid=$lambdaGrid"
        'contextFamily=RF'
    ) | Set-Content -LiteralPath (Join-Path $control 'status.txt') -Encoding UTF8
}

foreach ($rep in 0..24) {
    $name = 'rep_{0:D3}' -f $rep
    $input = Join-Path $ExperimentRoot "input\$name"
    $selected = Join-Path $ExperimentRoot "experiment1\$name\RF-CSAA\queries\query_000\validation\context_candidate.csv"
    $target = Join-Path $resultRoot "primary\C-Chi2\$name"
    if (-not (Test-Path -LiteralPath $input -PathType Container)) {
        throw "Missing replication input: $input"
    }
    if (-not (Test-Path -LiteralPath $selected -PathType Leaf)) {
        throw "Missing RF selected-context file: $selected"
    }
    $header = Get-Content -LiteralPath $selected -TotalCount 2
    if ($header.Count -ne 2 -or $header[1] -notmatch ',RF,') {
        throw "Selected-context file is not RF: $selected"
    }
    if (Test-Path -LiteralPath (Join-Path $target 'complete.txt')) { continue }
    $queue.Enqueue([pscustomobject]@{
        rep = $rep
        input = $input
        selected = $selected
        target = $target
        attempt = 0
    })
}

"replications=0-24`ncontextFamily=RF`nlambdaGrid=$lambdaGrid`nparallel=$MaxParallel`nsolverThreads=4" |
    Set-Content -LiteralPath (Join-Path $control 'protocol.txt') -Encoding UTF8
Write-Status 'RUNNING'

while ($queue.Count -gt 0 -or $running.Count -gt 0) {
    while ($queue.Count -gt 0 -and $running.Count -lt $MaxParallel) {
        $task = $queue.Dequeue()
        if (Test-Path -LiteralPath (Join-Path $task.target 'complete.txt')) {
            Add-Event $task 'SKIPPED_COMPLETE' ''
            continue
        }
        $task.attempt++
        New-Item -ItemType Directory -Force -Path $task.target | Out-Null
        $args = @(
            '-Xmx2g'
            ('"-Djava.library.path=' + $native + '"')
            '-cp'
            ('"' + $classpath + '"')
            'Test.analysis.synthetic.TRBSVUExperiment2IdeMain'
            '--worker'
            ('"' + $task.input + '"')
            ('"' + $task.selected + '"')
            ('"' + $task.target + '"')
            $task.rep
            4
            14400
            'PRIMARY'
            'C-Chi2'
            $lambdaGrid
        )
        $stdout = Join-Path $task.target ("attempt_{0:D3}.stdout.log" -f $task.attempt)
        $stderr = Join-Path $task.target ("attempt_{0:D3}.stderr.log" -f $task.attempt)
        try {
            $process = Start-Process -FilePath $java -ArgumentList ($args -join ' ') `
                -WorkingDirectory $TaskRoot -WindowStyle Hidden -PassThru `
                -RedirectStandardOutput $stdout -RedirectStandardError $stderr
            $heldHandle = $process.Handle
            $running.Add([pscustomobject]@{task = $task; process = $process})
            Add-Event $task 'STARTED' $process.Id
        } catch {
            Add-Event $task 'START_FAILED_RETRY_QUEUED' $_.Exception.Message
            $queue.Enqueue($task)
            break
        }
    }

    Start-Sleep -Seconds 10
    foreach ($item in @($running.ToArray())) {
        if (-not $item.process.HasExited) { continue }
        $item.process.WaitForExit()
        $code = $item.process.ExitCode
        if ($code -eq 0 -and (Test-Path -LiteralPath (Join-Path $item.task.target 'complete.txt'))) {
            Add-Event $item.task 'COMPLETE' $code
        } else {
            Add-Event $item.task 'RETRY_QUEUED' $code
            $queue.Enqueue($item.task)
        }
        [void]$running.Remove($item)
        $item.process.Dispose()
    }
    Write-Status 'RUNNING'
}

Write-Status 'FINISHED'
