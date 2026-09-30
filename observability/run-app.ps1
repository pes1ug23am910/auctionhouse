param(
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [Parameter(Mandatory=$true)][string]$Instance,
    [string]$AgentJar=(Join-Path $PSScriptRoot '../build/telemetry/opentelemetry-javaagent-2.31.1.jar'),
    [int]$Port=8080,[int]$PoolSize=16,[string]$Profiles='local,broker',
    [string]$Sampler='parentbased_always_on',[string]$SamplerArgument='1.0'
)
$ErrorActionPreference='Stop'
if($Port -lt 1024 -or $Port -gt 65535 -or $PoolSize -lt 1 -or $PoolSize -gt 64 -or $Instance -notmatch '^[A-Za-z0-9._-]{1,64}$') {throw 'Invalid bounded local settings'}
if(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {throw "Port $Port is occupied; stop only the intended application first"}
$repository=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$jar=(Resolve-Path (Join-Path $repository 'build/libs/auctionhouse.jar')).Path
$agent=(Resolve-Path -LiteralPath $AgentJar).Path
$config=(Resolve-Path (Join-Path $PSScriptRoot 'agent.properties')).Path
if((Get-FileHash -LiteralPath $agent -Algorithm SHA256).Hash.ToLowerInvariant() -ne 'bbf83c151b6400709e2f225bdd07a04f839d9d13b8b93464241333fd25d3e3ba') {throw 'Agent checksum mismatch'}
$destination=[System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $destination -Force | Out-Null
if(Test-Path -LiteralPath (Join-Path $destination 'process.json')) {throw 'Choose a fresh output directory'}
$values=@{
    AUCTIONHOUSE_DB_POOL_SIZE="$PoolSize";OTEL_JAVAAGENT_CONFIGURATION_FILE=$config;
    OTEL_RESOURCE_ATTRIBUTES="service.instance.id=$Instance,deployment.environment.name=local";
    OTEL_TRACES_SAMPLER=$Sampler;OTEL_TRACES_SAMPLER_ARG=$SamplerArgument
}
$previous=@{}
foreach($name in $values.Keys) {$previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try {
    foreach($name in $values.Keys) {[Environment]::SetEnvironmentVariable($name,$values[$name],'Process')}
    $arguments=@('-Xms128m','-Xmx384m','-Duser.timezone=UTC',('-javaagent:"{0}"' -f $agent),'-jar',('"{0}"' -f $jar),"--spring.profiles.active=$Profiles","--server.port=$Port")
    $process=Start-Process -FilePath (Get-Command java).Source -ArgumentList $arguments -WorkingDirectory $repository -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $destination 'stdout.log') -RedirectStandardError (Join-Path $destination 'stderr.log')
    $metadata=[pscustomobject]@{
        ProcessId=$process.Id;StartedAt=$process.StartTime.ToUniversalTime().ToString('o');
        Instance=$Instance;Port=$Port;PoolSize=$PoolSize;Profiles=$Profiles;Jar=$jar;
        JarSHA256=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant();
        AgentVersion='2.31.1';AgentSHA256=(Get-FileHash -LiteralPath $agent -Algorithm SHA256).Hash.ToLowerInvariant();
        Sampler=$Sampler;SamplerArgument=$SamplerArgument;MaxHeapMiB=384
    }
    $metadata | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $destination 'process.json')
    $metadata
} finally {
    foreach($name in $values.Keys) {[Environment]::SetEnvironmentVariable($name,$previous[$name],'Process')}
}
