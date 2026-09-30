[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^(sha256:[a-f0-9]{64}|[A-Za-z0-9._/:-]+@sha256:[a-f0-9]{64})$')][string]$Image,
    [ValidatePattern('^[a-z][a-z0-9-]{2,40}$')][string]$Project = 'auctionhouse-release',
    [ValidateRange(1024,65535)][int]$Port = 18080,
    [string]$StateDirectory = (Join-Path $PSScriptRoot '../.release'),
    [switch]$SchemaCompatibleRollback,
    [ValidateRange(1,120)][int]$CandidateSmokeAttempts = 40
)
$ErrorActionPreference = 'Stop'
if (-not $env:AUCTIONHOUSE_DB_PASSWORD) { throw 'Set AUCTIONHOUSE_DB_PASSWORD for this local fixture' }
$repo = Split-Path $PSScriptRoot -Parent
$compose = Join-Path $repo 'compose.app.yaml'
New-Item -ItemType Directory -Path $StateDirectory -Force | Out-Null
$stateFile = Join-Path $StateDirectory ($Project + '.json')
$previous = if (Test-Path -LiteralPath $stateFile) { Get-Content -Raw -LiteralPath $stateFile | ConvertFrom-Json } else { $null }
if ($previous -and -not $SchemaCompatibleRollback) { throw 'Review migrations and explicitly attest -SchemaCompatibleRollback before replacing an existing release' }
$env:AUCTIONHOUSE_IMAGE = $Image
$env:AUCTIONHOUSE_HTTP_PORT = "$Port"
function Compose([string[]]$Arguments) {
    & docker compose --project-name $Project -f $compose @Arguments
    if ($LASTEXITCODE -ne 0) { throw ('Compose failed: ' + ($Arguments -join ' ')) }
}
# Local IDs are already content addressed; remote releases must use repository@sha256.
if ($Image -notmatch '^sha256:') {
    & docker pull $Image
    if ($LASTEXITCODE -ne 0) { throw 'Pull failed' }
}
& docker image inspect $Image --format '{{.Id}}' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Image does not exist locally' }
Compose -Arguments @('up','-d','--wait','postgres')
# A failed migration never starts a candidate and never attempts to reverse schema changes.
Compose -Arguments @('--profile','migration','run','--rm','migrate')
try {
    Compose -Arguments @('up','-d','--no-deps','app')
    & (Join-Path $PSScriptRoot 'Smoke.ps1') -BaseUrl "http://127.0.0.1:$Port/" -Attempts $CandidateSmokeAttempts
    $priorImage = if ($previous -and $previous.image -eq $Image) { $previous.previousImage } else { $previous.image }
    @{ image=$Image; previousImage=$priorImage; project=$Project; port=$Port; releasedAt=[DateTime]::UtcNow.ToString('o') } |
        ConvertTo-Json | Set-Content -LiteralPath ($stateFile + '.new') -Encoding utf8
    Move-Item -LiteralPath ($stateFile + '.new') -Destination $stateFile -Force
    Write-Output ('Released immutable image: ' + $Image)
} catch {
    $candidateFailure = $_
    if ($previous) {
        $env:AUCTIONHOUSE_IMAGE = $previous.image
        Compose -Arguments @('up','-d','--no-deps','app')
        & (Join-Path $PSScriptRoot 'Smoke.ps1') -BaseUrl "http://127.0.0.1:$Port/"
        Write-Output ('Rollback verified: ' + $previous.image)
    } else {
        Compose -Arguments @('rm','--stop','--force','app')
    }
    throw $candidateFailure
}
