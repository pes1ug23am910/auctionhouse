param([string]$Directory=(Join-Path $PSScriptRoot '../build/telemetry'))
$ErrorActionPreference='Stop'
$version='2.31.1'
$expected='bbf83c151b6400709e2f225bdd07a04f839d9d13b8b93464241333fd25d3e3ba'
New-Item -ItemType Directory -Path $Directory -Force | Out-Null
$target=Join-Path ([System.IO.Path]::GetFullPath($Directory)) "opentelemetry-javaagent-$version.jar"
if(!(Test-Path -LiteralPath $target)) {
    Invoke-WebRequest -Uri "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v$version/opentelemetry-javaagent.jar" -OutFile $target
}
$actual=(Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
if($actual -ne $expected) {throw 'Agent checksum mismatch; do not execute this file'}
[pscustomobject]@{Path=$target;Version=$version;SHA256=$actual}
