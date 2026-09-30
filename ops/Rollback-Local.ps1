param([Parameter(Mandatory)][string]$StateFile, [Parameter(Mandatory)][switch]$SchemaCompatible)
$ErrorActionPreference = 'Stop'
if (-not $SchemaCompatible) { throw 'Migration compatibility review is required' }
$state = Get-Content -Raw -LiteralPath $StateFile | ConvertFrom-Json
if (-not $state.previousImage -or $state.previousImage -notmatch '^(sha256:[a-f0-9]{64}|[A-Za-z0-9._/:-]+@sha256:[a-f0-9]{64})$') { throw 'No valid recorded previous image' }
$env:AUCTIONHOUSE_IMAGE=$state.previousImage
$env:AUCTIONHOUSE_HTTP_PORT=[string]$state.port
$compose=Join-Path $PSScriptRoot '../compose.app.yaml'
# Rollback changes application content only. It does not run or reverse migrations.
& docker compose --project-name $state.project -f $compose up -d --no-deps app
if ($LASTEXITCODE -ne 0) { throw 'Rollback startup failed' }
& (Join-Path $PSScriptRoot 'Smoke.ps1') -BaseUrl "http://127.0.0.1:$($state.port)/"
$old=$state.image
$state.image=$state.previousImage
$state.previousImage=$old
$state.releasedAt=[DateTime]::UtcNow.ToString('o')
$state | ConvertTo-Json | Set-Content -LiteralPath $StateFile -Encoding utf8
Write-Output ('Rollback verified: '+$state.image)
