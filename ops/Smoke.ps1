param([Parameter(Mandatory)][uri]$BaseUrl, [int]$Attempts = 40)
$ErrorActionPreference = 'Stop'
for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
    try {
        $health = Invoke-RestMethod -Uri ([uri]::new($BaseUrl, '/actuator/health/readiness')) -TimeoutSec 5
        if ($health.status -ne 'UP') { throw 'Readiness is not UP' }
        $page = Invoke-WebRequest -Uri ([uri]::new($BaseUrl, '/')) -TimeoutSec 5
        if ($page.StatusCode -ne 200 -or $page.Content -notmatch '<div id="root">') { throw 'Frontend is absent' }
        $csrf = Invoke-RestMethod -Uri ([uri]::new($BaseUrl, '/api/auth/csrf')) -TimeoutSec 5
        if (-not $csrf.token -or -not $csrf.headerName) { throw 'CSRF endpoint failed' }
        try { Invoke-WebRequest -Uri ([uri]::new($BaseUrl, '/api/auth/session')) -TimeoutSec 5 | Out-Null; throw 'Anonymous session unexpectedly succeeded' }
        catch { if ([int]$_.Exception.Response.StatusCode -ne 401) { throw } }
        Write-Output 'PASS: readiness, bundled frontend, CSRF contract and anonymous-session rejection'
        return
    } catch {
        if ($attempt -eq $Attempts) { throw }
        Start-Sleep -Seconds 2
    }
}
