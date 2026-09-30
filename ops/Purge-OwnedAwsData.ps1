[CmdletBinding()]
param([Parameter(Mandatory)][ValidatePattern('^[0-9]{12}$')][string]$ExpectedAccount,
      [Parameter(Mandatory)][string]$Region,
      [Parameter(Mandatory)][ValidatePattern('^[a-z][a-z0-9-]{2,20}$')][string]$Session,
      [Parameter(Mandatory)][ValidateSet('EcrImages','SsmParameters','FinalSnapshot')][string]$Kind,
      [string]$Profile='auctionhouse', [switch]$Execute, [switch]$AcceptDataLoss)
$ErrorActionPreference='Stop'
function Aws([string[]]$Arguments) {
    $output=& aws --profile $Profile --region $Region @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'AWS operation failed; no broader deletion was attempted' }
    if ($output) { return ($output | ConvertFrom-Json) }
}
function Assert-Owned($Tags) {
    $project=@($Tags | Where-Object Key -eq 'Project').Value
    $session=@($Tags | Where-Object Key -eq 'SessionId').Value
    if ($project -ne 'auctionhouse' -or $session -ne $Session) { throw 'Resource is not owned by this project/session' }
}
$identity=Aws -Arguments @('sts','get-caller-identity')
if ($identity.Account -ne $ExpectedAccount) { throw 'AWS project identity mismatch' }
if ($Execute -and -not $AcceptDataLoss) { throw 'Explicit -AcceptDataLoss is required after reviewing inventory' }
$name='auctionhouse-'+$Session
switch($Kind) {
    EcrImages {
        $repo=(Aws -Arguments @('ecr','describe-repositories','--repository-names',$name)).repositories[0]
        Assert-Owned (Aws -Arguments @('ecr','list-tags-for-resource','--resource-arn',$repo.repositoryArn)).tags
        $images=@((Aws -Arguments @('ecr','list-images','--repository-name',$name)).imageIds)
        $images | ConvertTo-Json -Depth 5
        if ($Execute) {
            # Exact returned digests only, one at a time; never delete another repository or unowned tag set.
            foreach($image in $images) { Aws -Arguments @('ecr','batch-delete-image','--repository-name',$name,'--image-ids',('imageDigest='+$image.imageDigest)) | Out-Null }
        }
    }
    SsmParameters {
        foreach($kindName in @('runtime','migration')) {
            $parameter='/auctionhouse/'+$Session+'/'+$kindName
            Assert-Owned (Aws -Arguments @('ssm','list-tags-for-resource','--resource-type','Parameter','--resource-id',$parameter)).TagList
            Write-Output $parameter
            if($Execute){ Aws -Arguments @('ssm','delete-parameter','--name',$parameter) | Out-Null }
        }
    }
    FinalSnapshot {
        $snapshot=(Aws -Arguments @('rds','describe-db-snapshots','--db-snapshot-identifier',($name+'-final'))).DBSnapshots[0]
        Assert-Owned (Aws -Arguments @('rds','list-tags-for-resource','--resource-name',$snapshot.DBSnapshotArn)).TagList
        Write-Output $snapshot.DBSnapshotArn
        if($Execute){ Aws -Arguments @('rds','delete-db-snapshot','--db-snapshot-identifier',($name+'-final')) | Out-Null }
    }
}
if(-not $Execute){ Write-Output 'Read-only inventory. Exact-resource deletion requires -Execute -AcceptDataLoss after approval.' }
