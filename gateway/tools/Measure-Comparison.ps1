[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateRange(1,2147483647)][int]$JavaProcessId,
    [Parameter(Mandatory)][ValidateRange(1,2147483647)][int]$GatewayProcessId,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [ValidateSet('paired','java','gateway')][string]$Mode = 'paired',
    [ValidateRange(1,32)][int]$Clients = 1,
    [ValidateRange(1,200)][int]$Events = 20,
    [ValidateRange(0,1000)][int]$IntervalMs = 25,
    [ValidateRange(100,2000)][int]$SampleIntervalMs = 250
)
$ErrorActionPreference = 'Stop'
if (-not $env:AUCTIONHOUSE_DEMO_PASSWORD) { throw 'Supply the private demo credential through the process environment' }
$gatewayDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$output = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $output) { throw 'Use a new evidence output directory; existing evidence is never overwritten' }
$java = Get-Process -Id $JavaProcessId
$gateway = Get-Process -Id $GatewayProcessId
if ($java.ProcessName -notmatch '^java(w)?$' -or $gateway.ProcessName -ne 'node') {
    throw 'Explicit process IDs must name the intended native Java and Node gateway processes'
}
function Socket-Snapshot {
    $rows = @(Get-NetTCPConnection -ErrorAction SilentlyContinue | Where-Object { $_.OwningProcess -in @($JavaProcessId,$GatewayProcessId) })
    [ordered]@{
        capturedAt=[DateTime]::UtcNow.ToString('o')
        counts=@($rows | Group-Object OwningProcess,State | ForEach-Object {
            [ordered]@{ processId=$_.Group[0].OwningProcess; state=[string]$_.Group[0].State; count=$_.Count }
        })
        scope='Windows TCP table at a point outside the collector interval; includes idle HTTP and database sockets'
    }
}
$socketBefore = Socket-Snapshot
$operatingSystem = Get-CimInstance Win32_OperatingSystem
$hostBefore = [ordered]@{
    capturedAt=[DateTime]::UtcNow.ToString('o')
    totalMemoryBytes=[long]$operatingSystem.TotalVisibleMemorySize*1024
    freeMemoryBytes=[long]$operatingSystem.FreePhysicalMemory*1024
}
function Sample-Process([System.Diagnostics.Process]$Process, [string]$Role, [double]$Elapsed) {
    try {
        $Process.Refresh()
        [ordered]@{
            role = $Role; processId = $Process.Id; elapsedMs = $Elapsed
            cpuSeconds = $Process.TotalProcessorTime.TotalSeconds
            workingSetBytes = $Process.WorkingSet64
            privateBytes = $Process.PrivateMemorySize64
            handleCount = $Process.HandleCount
        }
    } catch {
        [ordered]@{ role = $Role; processId = $Process.Id; elapsedMs = $Elapsed; unavailable = $true }
    }
}
$baseline = @(
    (Sample-Process $java 'java' 0)
    (Sample-Process $gateway 'gateway' 0)
)
if ($baseline | Where-Object { $_.unavailable }) { throw 'A named application process is unavailable' }
New-Item -ItemType Directory -Path $output | Out-Null
$environmentNames = @('AUCTIONHOUSE_COMPARISON_MODE','AUCTIONHOUSE_COMPARISON_CLIENTS','AUCTIONHOUSE_COMPARISON_EVENTS','AUCTIONHOUSE_COMPARISON_INTERVAL_MS')
$priorEnvironment = @{}
foreach ($name in $environmentNames) { $priorEnvironment[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
$env:AUCTIONHOUSE_COMPARISON_MODE=$Mode
$env:AUCTIONHOUSE_COMPARISON_CLIENTS=[string]$Clients
$env:AUCTIONHOUSE_COMPARISON_EVENTS=[string]$Events
$env:AUCTIONHOUSE_COMPARISON_INTERVAL_MS=[string]$IntervalMs
$process = $null
$clock = [Diagnostics.Stopwatch]::StartNew()
$samples = [System.Collections.Generic.List[object]]::new()
try {
    $process = Start-Process -FilePath (Get-Command node).Source -ArgumentList @('tools/compare.mjs') -WorkingDirectory $gatewayDirectory -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $output 'comparison.json') -RedirectStandardError (Join-Path $output 'collector-error.log')
    while (-not $process.HasExited) {
        foreach ($entry in @(@($java,'java'),@($gateway,'gateway'),@($process,'collector'))) {
            $samples.Add((Sample-Process $entry[0] $entry[1] $clock.Elapsed.TotalMilliseconds))
        }
        Start-Sleep -Milliseconds $SampleIntervalMs
        if ($clock.Elapsed.TotalSeconds -gt 300) {
            Stop-Process -Id $process.Id
            throw 'Comparison exceeded its bounded 300-second runtime; partial resource samples retained'
        }
        $process.Refresh()
    }
    $process.WaitForExit()
} finally {
    if ($process -and -not $process.HasExited) { Stop-Process -Id $process.Id }
    foreach ($entry in @(@($java,'java'),@($gateway,'gateway'))) {
        $samples.Add((Sample-Process $entry[0] $entry[1] $clock.Elapsed.TotalMilliseconds))
    }
    $clock.Stop()
    $socketAfter = Socket-Snapshot
    $operatingSystem = Get-CimInstance Win32_OperatingSystem
    $hostAfter = [ordered]@{
        capturedAt=[DateTime]::UtcNow.ToString('o')
        freeMemoryBytes=[long]$operatingSystem.FreePhysicalMemory*1024
    }
    foreach ($name in $environmentNames) { [Environment]::SetEnvironmentVariable($name,$priorEnvironment[$name],'Process') }
    $summary = foreach ($role in @('java','gateway','collector')) {
        $rows = @($samples | Where-Object { $_.role -eq $role -and -not $_.unavailable })
        if ($rows.Count -eq 0) { continue }
        $first = if ($role -eq 'collector') { $rows[0] } else { $baseline | Where-Object { $_.role -eq $role } }
        $last = $rows[-1]
        [ordered]@{
            role=$role; processId=$last.processId
            cpuDeltaSeconds=$last.cpuSeconds-$first.cpuSeconds
            firstSampleMs=$first.elapsedMs; lastSampleMs=$last.elapsedMs
            initialWorkingSetBytes=$first.workingSetBytes; finalWorkingSetBytes=$last.workingSetBytes
            initialPrivateBytes=$first.privateBytes; finalPrivateBytes=$last.privateBytes
            sampledPeakWorkingSetBytes=($rows.workingSetBytes | Measure-Object -Maximum).Maximum
            sampledPeakPrivateBytes=($rows.privateBytes | Measure-Object -Maximum).Maximum
            sampledPeakHandleCount=($rows.handleCount | Measure-Object -Maximum).Maximum
        }
    }
    [ordered]@{
        recordedAt=[DateTime]::UtcNow.ToString('o')
        mode=$Mode; clientsPerPath=$Clients; eventCount=$Events; intervalAfterResponseMs=$IntervalMs
        sampleIntervalMs=$SampleIntervalMs; elapsedMs=$clock.Elapsed.TotalMilliseconds
        logicalProcessors=[Environment]::ProcessorCount
        osDescription=[Runtime.InteropServices.RuntimeInformation]::OSDescription
        collectorExitCode=$(if ($process -and $process.HasExited) { $process.ExitCode } else { $null })
        hostBefore=$hostBefore; hostAfter=$hostAfter
        socketBefore=$socketBefore; socketAfter=$socketAfter
        processSummary=@($summary); samples=$samples
        limits=@(
            'Native Windows processes only; no container/host resource attribution.',
            'Java and gateway CPU deltas include fixture setup, idle settle, requests and drain time.',
            'Paired mode measures both paths together; its process cost cannot be attributed to a single topology.',
            'Working set/private memory are interval-sampled maxima, not exact peaks or incremental allocations.',
            'Collector process sampling omits its initial pre-sample CPU; comparison.json reports its own CPU interval.',
            'Background database, OS, sampler and other workloads are not included in per-process CPU totals.',
            'TCP socket snapshots occur before/after collector timing, not during peak fan-out; no socket count is inferred from handles.',
            'Gateway authorization HTTP attempts are separate cumulative metrics in comparison.json; no Java internal authorization count is inferred.'
        )
    } | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $output 'resources.json') -Encoding utf8NoBOM
}
if ($process.ExitCode -ne 0) { throw "Comparison failed with exit code $($process.ExitCode); evidence retained" }
Write-Output "Comparison complete; results saved under $output"
