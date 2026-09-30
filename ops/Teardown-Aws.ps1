[CmdletBinding()]
param([Parameter(Mandatory)][ValidatePattern('^[0-9]{12}$')][string]$ExpectedAccount,
      [Parameter(Mandatory)][ValidatePattern('^[a-z][a-z0-9-]{2,20}$')][string]$Session,
      [string]$Profile = 'auctionhouse',
      [string]$PlanPath = (Join-Path $PSScriptRoot '../.release/destroy.tfplan'),
      [switch]$Execute, [string]$ApprovedPlanSha256)
$ErrorActionPreference='Stop'
$infra = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../infra'))
$env:AWS_PROFILE=$Profile
$owner = & terraform "-chdir=$infra" output -json ownership | ConvertFrom-Json
if ($LASTEXITCODE -ne 0 -or $owner.project -ne 'auctionhouse' -or $owner.session_id -ne $Session -or $owner.account_id -ne $ExpectedAccount) { throw 'This is not the explicitly selected project state' }
$identity = & aws --profile $Profile --region $owner.region sts get-caller-identity | ConvertFrom-Json
if ($LASTEXITCODE -ne 0 -or $identity.Account -ne $ExpectedAccount) { throw 'AWS project identity mismatch' }
New-Item -ItemType Directory -Path (Split-Path $PlanPath -Parent) -Force | Out-Null
$plan = [IO.Path]::GetFullPath($PlanPath)
if (-not $Execute) {
    & (Join-Path $PSScriptRoot 'Inventory-Aws.ps1') -ExpectedAccount $ExpectedAccount -Region $owner.region -Session $Session -Profile $Profile
    & terraform "-chdir=$infra" plan -destroy "-out=$plan"
    if ($LASTEXITCODE -ne 0) { throw 'Destroy plan failed' }
    Write-Output ('Review this saved plan, then explicitly approve its SHA256: ' + (Get-FileHash -Algorithm SHA256 -LiteralPath $plan).Hash)
    Write-Output 'RDS deletion protection must first be disabled with a reviewed allow_destroy=true apply. ECR retained images must be reviewed separately. Final snapshot and bootstrap-owned SSM secrets remain and must appear in residual inventory.'
    return
}
if (-not $ApprovedPlanSha256 -or (Get-FileHash -Algorithm SHA256 -LiteralPath $plan).Hash -ne $ApprovedPlanSha256) { throw 'Explicit approval of the exact saved plan hash is required' }
$review = & terraform "-chdir=$infra" show -json $plan | ConvertFrom-Json
if ($LASTEXITCODE -ne 0) { throw 'Cannot review saved plan' }
$ownedParents=@{}
foreach ($parent in $review.resource_changes) {
    $tags=$parent.change.before.tags_all
    if ($parent.mode -eq 'managed' -and $tags.Project -eq 'auctionhouse' -and $tags.SessionId -eq $Session) {
        $ownedParents[$parent.address]=$parent.change.before
    }
}
foreach ($change in $review.resource_changes) {
    if ($change.mode -ne 'managed') { continue }
    if (@($change.change.actions | Where-Object { $_ -notin @('delete','no-op') }).Count) { throw 'Saved plan includes actions other than teardown' }
    $tags=$change.change.before.tags_all
    # Untaggable children must bind to this saved plan's tagged module parents.
    $before=$change.change.before
    $knownUntagged=$false
    $roleParent=@{
        'aws_iam_role_policy.host'='aws_iam_role.app'
        'aws_iam_role_policy.deploy'='aws_iam_role.deploy'
        'aws_iam_role_policy.bootstrap[0]'='aws_iam_role.bootstrap[0]'
        'aws_iam_role_policy_attachment.ssm'='aws_iam_role.app'
    }
    if ($change.type -eq 'aws_iam_role_policy' -and $roleParent.ContainsKey($change.address)) {
        $parent=$ownedParents[$roleParent[$change.address]]
        $knownUntagged=$null -ne $parent -and $before.role -eq $parent.name
    } elseif ($change.type -eq 'aws_iam_role_policy_attachment' -and $change.address -eq 'aws_iam_role_policy_attachment.ssm') {
        $parent=$ownedParents['aws_iam_role.app']
        $knownUntagged=$null -ne $parent -and $before.role -eq $parent.name -and $before.policy_arn -eq 'arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore'
    } elseif ($change.type -eq 'aws_route_table_association') {
        $subnetAddress=$null; $routeAddress=$null
        if ($change.address -eq 'aws_route_table_association.app') {
            $subnetAddress='aws_subnet.app'; $routeAddress='aws_route_table.app'
        } elseif ($change.address -match '^aws_route_table_association\.database(\["[a-z0-9-]+"\])$') {
            $subnetAddress='aws_subnet.database'+$Matches[1]; $routeAddress='aws_route_table.database'
        }
        if ($subnetAddress -and $ownedParents.ContainsKey($subnetAddress) -and $ownedParents.ContainsKey($routeAddress)) {
            $knownUntagged=$before.subnet_id -eq $ownedParents[$subnetAddress].id -and $before.route_table_id -eq $ownedParents[$routeAddress].id
        }
    }
    if (-not $knownUntagged -and (-not $tags -or $tags.Project -ne 'auctionhouse' -or $tags.SessionId -ne $Session)) {
        throw ('Missing or unexpected ownership: ' + $change.address)
    }
}
& terraform "-chdir=$infra" apply $plan
if ($LASTEXITCODE -ne 0) { throw 'Teardown incomplete; preserve state and review residual resources' }
& (Join-Path $PSScriptRoot 'Inventory-Aws.ps1') -ExpectedAccount $ExpectedAccount -Region $owner.region -Session $Session -Profile $Profile -SkipState
