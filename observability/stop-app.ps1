param([Parameter(Mandatory=$true)][string]$ProcessRecord)
$ErrorActionPreference='Stop'
$record=Get-Content -LiteralPath $ProcessRecord -Raw | ConvertFrom-Json
$process=Get-Process -Id $record.ProcessId -ErrorAction SilentlyContinue
if(!$process) {return}
$details=Get-CimInstance Win32_Process -Filter "ProcessId=$($record.ProcessId)"
$expected=([DateTime]$record.StartedAt).ToUniversalTime()
if($process.ProcessName -ne 'java' -or [Math]::Abs(($process.StartTime.ToUniversalTime()-$expected).TotalSeconds) -gt 2 -or !$details.CommandLine.Contains($record.Jar) -or !$details.CommandLine.Contains("--server.port=$($record.Port)")) {
    throw 'Process identity does not match the owned launch record'
}
Stop-Process -Id $record.ProcessId
