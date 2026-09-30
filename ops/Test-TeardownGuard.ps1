# Local command-double checks: no AWS or Terraform executable is invoked.
$ErrorActionPreference='Stop'
$fixture=Join-Path ([IO.Path]::GetTempPath()) ('auctionhouse-teardown-guard-'+[Guid]::NewGuid().ToString('N')+'.tfplan')
'local command-double fixture' | Set-Content -LiteralPath $fixture
$hash=(Get-FileHash -Algorithm SHA256 -LiteralPath $fixture).Hash
$global:AuctionhouseTeardownFixtureChanges=@()
$global:AuctionhouseTeardownFixtureApplyCalls=0
function terraform {
    $global:LASTEXITCODE=0
    if($args -contains 'show') { return (@{resource_changes=$global:AuctionhouseTeardownFixtureChanges}|ConvertTo-Json -Depth 12) }
    if($args -contains 'output') { return (@{project='auctionhouse';session_id='fixture';account_id='123456789012';region='ap-south-1'}|ConvertTo-Json) }
    if($args -contains 'apply') { $global:AuctionhouseTeardownFixtureApplyCalls++; return }
    throw 'Unexpected Terraform command in local fixture'
}
function aws {
    $global:LASTEXITCODE=0
    if($args -contains 'get-caller-identity'){ return '{"Account":"123456789012"}' }
    if($args -contains 'get-resources'){ return '{"ResourceTagMappingList":[]}' }
    throw 'Unexpected AWS command in local fixture'
}
function Check([string]$Name,[string]$Type,[string]$Address,$Tags,[string[]]$Actions,[bool]$Allowed,[string]$ParentRole="auctionhouse-fixture-host",[string]$RouteTable="rtb-fixture") {
    $owned=@{Project='auctionhouse';SessionId='fixture'}
    $global:AuctionhouseTeardownFixtureChanges=@(
        @{mode='managed';type='aws_iam_role';address='aws_iam_role.app';change=@{actions=@('delete');before=@{tags_all=$owned;name='auctionhouse-fixture-host'}}},
        @{mode='managed';type='aws_subnet';address='aws_subnet.app';change=@{actions=@('delete');before=@{tags_all=$owned;id='subnet-fixture'}}},
        @{mode='managed';type='aws_route_table';address='aws_route_table.app';change=@{actions=@('delete');before=@{tags_all=$owned;id='rtb-fixture'}}},
        @{mode='managed';type=$Type;address=$Address;change=@{actions=$Actions;before=@{tags_all=$Tags;role=$ParentRole;subnet_id='subnet-fixture';route_table_id=$RouteTable}}})
    $global:AuctionhouseTeardownFixtureApplyCalls=0
    $succeeded=$false
    try {
        & (Join-Path $PSScriptRoot 'Teardown-Aws.ps1') -ExpectedAccount '123456789012' -Session fixture -PlanPath $fixture -Execute -ApprovedPlanSha256 $hash | Out-Null
        $succeeded=$true
    } catch {
        if($Allowed){ throw }
    }
    if($succeeded -ne $Allowed -or $global:AuctionhouseTeardownFixtureApplyCalls -ne [int]$Allowed){ throw ('Guard outcome mismatch: '+$Name) }
    Write-Output ('PASS: '+$Name)
}
try {
    Check 'owned taggable resource permitted' 'aws_instance' 'aws_instance.app' @{Project='auctionhouse';SessionId='fixture'} @('delete') $true
    Check 'missing tags blocked before apply' 'aws_instance' 'aws_instance.app' $null @('delete') $false
    Check 'foreign session blocked before apply' 'aws_instance' 'aws_instance.app' @{Project='auctionhouse';SessionId='another'} @('delete') $false
    Check 'known untaggable binding permitted' 'aws_iam_role_policy' 'aws_iam_role_policy.host' $null @('delete') $true
    Check 'unknown untaggable binding blocked' 'aws_iam_role_policy' 'aws_iam_role_policy.foreign' $null @('delete') $false
    Check 'misbound inline policy blocked' 'aws_iam_role_policy' 'aws_iam_role_policy.host' $null @('delete') $false 'foreign-role'
    Check 'owned route association permitted' 'aws_route_table_association' 'aws_route_table_association.app' $null @('delete') $true
    Check 'misbound route association blocked' 'aws_route_table_association' 'aws_route_table_association.app' $null @('delete') $false 'auctionhouse-fixture-host' 'rtb-foreign'
    Check 'non-delete action blocked' 'aws_instance' 'aws_instance.app' @{Project='auctionhouse';SessionId='fixture'} @('update') $false
} finally {
    Remove-Item -LiteralPath $fixture
    Remove-Variable -Name AuctionhouseTeardownFixtureChanges,AuctionhouseTeardownFixtureApplyCalls -Scope Global
}
