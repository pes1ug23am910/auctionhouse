param(
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [int]$Runs=20, [int]$Clients=200, [long]$Seed=20261001,
    [int]$PoolPerInstance=16, [int]$Attempts=8,
    [string]$BackgroundActivity="not recorded"
)
$ErrorActionPreference='Stop'
$repository=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$destination=[System.IO.Path]::GetFullPath($OutputDirectory)
if(Test-Path -LiteralPath (Join-Path $destination 'summary.json')) {throw 'Choose a fresh output directory.'}
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$values=@{
    AUCTIONHOUSE_CONTENTION_EXPERIMENT='true'; AUCTIONHOUSE_CONTENTION_RUNS="$Runs";
    AUCTIONHOUSE_CONTENTION_CLIENTS="$Clients"; AUCTIONHOUSE_CONTENTION_SEED="$Seed";
    AUCTIONHOUSE_CONTENTION_POOL="$PoolPerInstance"; AUCTIONHOUSE_CONTENTION_ATTEMPTS="$Attempts";
    AUCTIONHOUSE_CONTENTION_OUTPUT=$destination; AUCTIONHOUSE_CONTENTION_BACKGROUND=$BackgroundActivity;
    AUCTIONHOUSE_CONTENTION_COMMAND="experiments/contention/run.ps1 -OutputDirectory $destination -Runs $Runs -Clients $Clients -Seed $Seed -PoolPerInstance $PoolPerInstance -Attempts $Attempts -BackgroundActivity $BackgroundActivity"
}
$previous=@{}
foreach($name in $values.Keys) {$previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try {
    foreach($name in $values.Keys) {[Environment]::SetEnvironmentVariable($name,$values[$name],'Process')}
    Push-Location $repository
    try {
        & .\gradlew.bat --no-daemon --no-watch-fs --console=plain integrationTest --tests '*ContentionExperimentTest' --rerun 2>&1 |
            Tee-Object -FilePath (Join-Path $destination 'console.log')
        $result=$LASTEXITCODE
        "EXIT=$result" | Add-Content -LiteralPath (Join-Path $destination 'console.log')
        [System.IO.File]::WriteAllText((Join-Path $destination 'exit-code.txt'),"$result"+[Environment]::NewLine)
        $xml=Join-Path $repository 'build/test-results/integrationTest'
        if(Test-Path -LiteralPath $xml) {Copy-Item -LiteralPath $xml -Destination (Join-Path $destination 'junit-xml') -Recurse}
        if($result -ne 0) {throw "Contention experiment exit $result; preserve $destination"}
    } finally {Pop-Location}
} finally {
    foreach($name in $values.Keys) {[Environment]::SetEnvironmentVariable($name,$previous[$name],'Process')}
}
