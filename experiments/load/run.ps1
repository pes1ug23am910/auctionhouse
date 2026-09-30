param(
 [Parameter(Mandatory=$true)][string]$OutputDirectory,
 [Parameter(Mandatory=$true)][string]$Label,
 [int]$Rate=20,[int]$DurationSeconds=60,[int]$Seed=42,
 [int]$PreallocatedVUs=40,[int]$MaxVUs=80
)
$ErrorActionPreference='Stop'
if(!$env:AUCTIONHOUSE_DEMO_PASSWORD) {throw 'Set AUCTIONHOUSE_DEMO_PASSWORD in the current process without printing it'}
if($Label -notmatch '^[A-Za-z0-9._-]{1,64}$') {throw 'Label must be a short filename-safe identifier'}
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$destination=[System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $destination -Force | Out-Null
if(Test-Path -LiteralPath (Join-Path $destination 'raw.log')) {throw 'Choose a fresh output directory'}
$image='grafana/k6:2.3.0@sha256:9c2dee7f8ed74d317e4027c06a10f169b625638189de8d4555d0b3486a5aeb34'
$system=Get-CimInstance Win32_OperatingSystem
$processor=Get-CimInstance Win32_Processor | Select-Object Name,NumberOfCores,NumberOfLogicalProcessors
$metadata=[pscustomobject]@{
 StartedAt=[DateTime]::UtcNow.ToString('o');WorkingDirectory=$repo;Image=$image;
 Rate=$Rate;DurationSeconds=$DurationSeconds;Seed=$Seed;PreallocatedVUs=$PreallocatedVUs;MaxVUs=$MaxVUs;
 Label=$Label;ScriptSHA256=(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'k6-workload.js')).Hash;
 Host=$system.Caption;TotalMemoryKiB=$system.TotalVisibleMemorySize;FreeMemoryKiB=$system.FreePhysicalMemory;Processor=$processor;
 ContainerMemoryMiB=256;ContainerCPUs=1;BaseURL='http://host.docker.internal:8080';
}
$metadata | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $destination 'environment.json')
docker stats --no-stream --format '{{json .}}' | Set-Content -LiteralPath (Join-Path $destination 'docker-before.jsonl')
docker run --rm --memory=256m --cpus=1 -e AUCTIONHOUSE_DEMO_PASSWORD -e BASE_URL=http://host.docker.internal:8080 -e "RATE=$Rate" -e "DURATION_SECONDS=$DurationSeconds" -e "PREALLOCATED_VUS=$PreallocatedVUs" -e "MAX_VUS=$MaxVUs" -e "SEED=$Seed" -e "COMPARISON_LABEL=$Label" -e SUMMARY_PATH=/evidence/summary.json -v "$($PSScriptRoot):/scripts:ro" -v "$($destination):/evidence" $image run /scripts/k6-workload.js *> (Join-Path $destination 'raw.log')
$result=$LASTEXITCODE
docker stats --no-stream --format '{{json .}}' | Set-Content -LiteralPath (Join-Path $destination 'docker-after.jsonl')
[pscustomobject]@{FinishedAt=[DateTime]::UtcNow.ToString('o');ExitCode=$result;FreeMemoryKiB=(Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $destination 'exit.json')
if(Test-Path -LiteralPath (Join-Path $destination 'summary.json')) {Get-Content -LiteralPath (Join-Path $destination 'summary.json')}
else {Get-Content -LiteralPath (Join-Path $destination 'raw.log') -Tail 25}
exit $result
