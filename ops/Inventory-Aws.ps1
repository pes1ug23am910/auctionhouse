param([Parameter(Mandatory)][ValidatePattern('^[0-9]{12}$')][string]$ExpectedAccount,
      [Parameter(Mandatory)][string]$Region,
      [Parameter(Mandatory)][ValidatePattern('^[a-z][a-z0-9-]{2,20}$')][string]$Session,
      [string]$Profile = 'auctionhouse', [switch]$SkipState)
$ErrorActionPreference='Stop'
$identity = & aws --profile $Profile --region $Region sts get-caller-identity | ConvertFrom-Json
if ($LASTEXITCODE -ne 0 -or $identity.Account -ne $ExpectedAccount) { throw 'AWS project identity mismatch' }
# Read-only inventory; tag filters require BOTH exact project and session ownership.
& aws --profile $Profile --region $Region resourcegroupstaggingapi get-resources --tag-filters 'Key=Project,Values=auctionhouse' "Key=SessionId,Values=$Session" --output json
if ($LASTEXITCODE -ne 0) { throw 'Inventory failed' }
# Tagging API does not enumerate every resource type. Record Terraform state inventory separately.
if (-not $SkipState) {
    $infra = Join-Path $PSScriptRoot '../infra'
    & terraform "-chdir=$infra" output -json resource_inventory
    if ($LASTEXITCODE -ne 0) { throw 'Terraform inventory unavailable' }
}
Write-Output 'Also inspect selected-Region and us-east-1 global-service Logs/WAF inventories if such global resources were ever created; this module creates none.'
