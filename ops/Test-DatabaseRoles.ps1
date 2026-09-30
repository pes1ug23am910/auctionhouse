param([Parameter(Mandatory)][string]$Image,
      [string]$DatabaseContainer='auctionhouse-rollback-test-postgres-1',
      [string]$Network='auctionhouse-rollback-test_database',
      [ValidatePattern('^[a-z][a-z0-9_]{2,50}$')][string]$Database='delivery_roles_fixture')
$ErrorActionPreference='Stop'
$bootstrap=Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'cloud/bootstrap-database.py')
$sql=[regex]::Match($bootstrap,"sql=f'''(?<sql>[\s\S]*?)'''",'Singleline').Groups['sql'].Value
if(-not $sql){ throw 'Cannot locate the bootstrap SQL contract' }
$migration=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
$runtime=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
$sql=$sql.Replace('{migration}',$migration).Replace('{runtime_password}',$runtime).Replace('auctionhouse',$database).Replace('ah_bootstrap','auctionhouse')
# This fixture owns a new empty DB. It never changes the running application's schema/data.
"CREATE DATABASE $database;" | docker exec -i $DatabaseContainer psql -X -q -v ON_ERROR_STOP=on -U auctionhouse -d postgres
if($LASTEXITCODE -ne 0){ throw 'Use a fresh fixture DB; no existing database is overwritten' }
$sql | docker exec -i $DatabaseContainer psql -X -q -v ON_ERROR_STOP=on -U auctionhouse -d $database
if($LASTEXITCODE -ne 0){ throw 'Bootstrap role SQL failed' }
$hadDatabasePassword = Test-Path Env:AUCTIONHOUSE_DB_PASSWORD
$previousDatabasePassword = $env:AUCTIONHOUSE_DB_PASSWORD
$hadPgPassword = Test-Path Env:PGPASSWORD
$previousPgPassword = $env:PGPASSWORD
try {
    $env:AUCTIONHOUSE_DB_PASSWORD=$migration
    & docker run --rm --network $Network --memory 512m --read-only --tmpfs /tmp --cap-drop ALL --security-opt no-new-privileges -e "AUCTIONHOUSE_DB_URL=jdbc:postgresql://postgres:5432/$database" -e AUCTIONHOUSE_DB_USER=ah_migrator -e AUCTIONHOUSE_DB_PASSWORD -e AUCTIONHOUSE_RUNTIME_DB_USER=ah_runtime $Image migrate
    if($LASTEXITCODE -ne 0){ throw 'Schema-owner migration failed' }
    $env:PGPASSWORD=$runtime
    function Runtime-Sql([string]$Statement,[bool]$ShouldPass){
        $output=$Statement | docker exec -i -e PGPASSWORD $DatabaseContainer psql -X -q -v ON_ERROR_STOP=on -h 127.0.0.1 -U ah_runtime -d $database 2>&1
        $passed=$LASTEXITCODE -eq 0
        if($passed -ne $ShouldPass){ throw ('Runtime permission assertion failed: '+$Statement) }
    }
    Runtime-Sql "INSERT INTO accounts(id,issuer,subject,display_name) VALUES ('00000000-0000-0000-0000-000000000777','fixture','runtime','Runtime fixture');" $true
    Runtime-Sql "UPDATE accounts SET display_name='Updated fixture' WHERE subject='runtime';" $true
    Runtime-Sql 'CREATE TABLE public.forbidden(id bigint);' $false
    Runtime-Sql 'ALTER TABLE public.accounts ADD COLUMN forbidden text;' $false
    Runtime-Sql 'DELETE FROM public.flyway_schema_history;' $false
    Runtime-Sql 'CREATE DATABASE forbidden_database;' $false
    Runtime-Sql "DELETE FROM accounts WHERE subject='runtime';" $true
} finally {
    if ($hadDatabasePassword) {
        $env:AUCTIONHOUSE_DB_PASSWORD = $previousDatabasePassword
    } else {
        Remove-Item Env:AUCTIONHOUSE_DB_PASSWORD -ErrorAction SilentlyContinue
    }
    if ($hadPgPassword) {
        $env:PGPASSWORD = $previousPgPassword
    } else {
        Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
    }
}
Write-Output 'PASS: migration owner created schema; runtime can perform DML but cannot create/alter schema, alter migration history, or create databases'
