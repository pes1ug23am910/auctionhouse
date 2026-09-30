param([Parameter(Mandatory)][string]$GoodImage, [int]$Port = 18080,
      [ValidatePattern('^[a-z][a-z0-9-]{2,40}$')][string]$Project='auctionhouse-rollback-test')
$ErrorActionPreference='Stop'
$state=Join-Path $PSScriptRoot '../.release/rollback-test'
& (Join-Path $PSScriptRoot 'Deploy-Local.ps1') -Image $GoodImage -Project $project -Port $Port -StateDirectory $state -SchemaCompatibleRollback
# Same schema and application, deliberately listens on a port not exposed by the release contract.
$baseTag='auctionhouse:rollback-base-'+($GoodImage -replace '.*sha256:','').Substring(0,12)
docker tag $GoodImage $baseTag
if ($LASTEXITCODE -ne 0) { throw 'Cannot tag the exact local fixture base' }
"FROM $baseTag`nENV PORT=9999`n" | docker build -t auctionhouse:rollback-fault -
if ($LASTEXITCODE -ne 0) { throw 'Fault fixture build failed' }
$badImage = docker image inspect auctionhouse:rollback-fault --format '{{.Id}}'
$failed=$false
try {
    & (Join-Path $PSScriptRoot 'Deploy-Local.ps1') -Image $badImage -Project $project -Port $Port -StateDirectory $state -SchemaCompatibleRollback -CandidateSmokeAttempts 8
} catch { $failed=$true }
if (-not $failed) { throw 'Fault fixture unexpectedly passed readiness' }
$record=Get-Content -Raw -LiteralPath (Join-Path $state ($project+'.json')) | ConvertFrom-Json
if ($record.image -ne $GoodImage) { throw 'Failed release replaced the known-good record' }
$container=docker compose --project-name $project -f (Join-Path $PSScriptRoot '../compose.app.yaml') ps -q app
$actual=docker inspect $container --format '{{.Image}}'
$expected=docker image inspect $GoodImage --format '{{.Id}}'
if ($actual -ne $expected) { throw 'Rollback did not restore the original content-addressed image' }
& (Join-Path $PSScriptRoot 'Smoke.ps1') -BaseUrl "http://127.0.0.1:$Port/"
Write-Output 'PASS: failed candidate restored the exact previous image and retained its release record'
