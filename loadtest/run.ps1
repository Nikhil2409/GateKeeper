<#
.SYNOPSIS
    Runs the Gatekeeper load tests (k6 in Docker) against the full docker-compose stack.

.DESCRIPTION
    Start the stack first:  docker compose up -d --build   (from the repository root)
    Then, from the repository root:
        .\loadtest\run.ps1                      every test, in order (about 40 minutes)
        .\loadtest\run.ps1 -Test baseline       one test
    Tests:
        baseline    same request straight to the backend vs through the gateway (latency added)
        throughput  rate climbs until errors > 1% or p99 > 500 ms (highest stable req/s)
        scaling     throughput against 1 gateway instance directly, then 3 behind nginx
        ratelimit   2x the route limit for 3 minutes: how close the 3 instances stay to the limit
        hotreload   a route changed every 10 s under load: failed requests and propagation time
        failover    one gateway instance stopped mid-test, then started again
        chaos       Kafka, Redis and api-management each stopped mid-test, one at a time
    Results: loadtest\results\<test>.json (k6 summary), <test>.csv.gz (per-request samples), machine.txt,
    and <test>-prometheus.json for the runs that break something. The test tenants' traffic shows up in the normal
    dashboards (Grafana via System metrics, Live performance, the analytics pages) like any tenant's.

    The test tenants (lt-shop, lt-payments, lt-media: admins, routes, token settings) are created through the product's APIs at the
    start of every run; their users' tokens come from the loadtest-idp container. Nothing of it is in the product.
#>
param(
    [ValidateSet('all', 'baseline', 'throughput', 'scaling', 'ratelimit', 'hotreload', 'failover', 'chaos')]
    [string]$Test = 'all',
    # highest rate the throughput test climbs to (8 steps up to it)
    [int]$MaxRate = 2000,
    # requests per second while something is broken (failover, chaos)
    [int]$SteadyRate = 200,
    # which dependencies the chaos test stops, one after the other
    [string[]]$ChaosServices = @('kafka', 'redis', 'api-management-service')
)

# native tools write progress to stderr, which 'Stop' would turn into errors in Windows PowerShell 5.1;
# failures are checked through exit codes instead
$ErrorActionPreference = 'Continue'
$root = Split-Path $PSScriptRoot -Parent
$results = Join-Path $PSScriptRoot 'results'
$composeArgs = @('compose', '-f', (Join-Path $root 'docker-compose.yml'), '-f', (Join-Path $PSScriptRoot 'docker-compose.loadtest.yml'))
New-Item -ItemType Directory -Force $results | Out-Null

function Compose { & docker @composeArgs @args; if ($LASTEXITCODE -ne 0) { throw "docker compose $args failed" } }

function Write-MachineInfo {
    $cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
    $os = Get-CimInstance Win32_OperatingSystem
    @(
        "date:        $(Get-Date -Format s)"
        "cpu:         $($cpu.Name) ($($cpu.NumberOfCores) cores / $($cpu.NumberOfLogicalProcessors) threads)"
        "ram:         {0:N1} GB" -f ($os.TotalVisibleMemorySize / 1MB)
        "os:          $($os.Caption) $($os.Version)"
        "docker vm:   {0:N1} GB, {1} CPUs" -f ((docker info --format '{{.MemTotal}}') / 1GB), (docker info --format '{{.NCPU}}')
        "docker:      $(docker version --format '{{.Server.Version}}')"
        "note:        k6, the gateways and every dependency share this one machine"
    ) | Set-Content -Encoding utf8 (Join-Path $results 'machine.txt')
}

# Stack up with the load-test additions (the loadtest-idp container writes the JWKS and tokens)
function Initialize-Stack {
    Compose up -d
    Write-Host 'Waiting for the gateway...'
    for ($i = 0; $i -lt 60; $i++) {
        try { if ((Invoke-RestMethod -TimeoutSec 3 http://localhost:8080/_gateway/health).status -eq 'UP') { return } } catch { }
        Start-Sleep 5
    }
    throw 'The gateway did not become ready'
}

function Invoke-K6([string]$Script, [string]$Name, [hashtable]$Env = @{}) {
    $envArgs = @('-e', "RESULT_NAME=$Name")
    foreach ($key in $Env.Keys) { $envArgs += @('-e', "$key=$($Env[$key])") }
    Write-Host "`n=== $Name ($Script)" -ForegroundColor Cyan
    & docker @composeArgs run --rm --name "loadtest-k6-$Name" @envArgs k6 run --out "csv=/results/$Name.csv.gz" "/scripts/$Script"
    if ($LASTEXITCODE -ne 0) { Write-Warning "$Name ended with exit code $LASTEXITCODE (a threshold failed or the run aborted; see the summary)" }
}

# Runs a script in the background and stops $Service 60 s into the measured part, starting it again 60 s later
function Invoke-K6WithOutage([string]$Script, [string]$Name, [string]$Service, [hashtable]$Env = @{}) {
    $container = "loadtest-k6-$Name"
    $envArgs = @('-e', "RESULT_NAME=$Name")
    foreach ($key in $Env.Keys) { $envArgs += @('-e', "$key=$($Env[$key])") }
    Write-Host "`n=== $Name ($Script, $Service stopped from 60 s to 120 s)" -ForegroundColor Cyan
    $job = Start-Job -ScriptBlock {
        param($composeArgs, $container, $envArgs, $Name, $Script)
        & docker @composeArgs run --rm --name $container @envArgs k6 run --out "csv=/results/$Name.csv.gz" "/scripts/$Script" 2>&1
    } -ArgumentList $composeArgs, $container, $envArgs, $Name, $Script

    for ($i = 0; $i -lt 120 -and -not ((docker logs $container 2>&1) -match 'LOADTEST_STARTED'); $i++) { Start-Sleep 1 }
    Start-Sleep 60
    Write-Host "  stopping $Service"; Compose stop $Service
    Start-Sleep 60
    Write-Host "  starting $Service"; Compose start $Service
    Receive-Job $job -Wait -AutoRemoveJob
    Save-PrometheusSnapshot $Name
}

# What the gateways reported during the outage: dropped traffic events, open circuits, outcomes
function Save-PrometheusSnapshot([string]$Name) {
    $queries = [ordered]@{
        traffic_events_dropped = 'sum(increase(gateway_traffic_events_dropped_total[5m]))'
        traffic_events_queued_max = 'max(max_over_time(gateway_traffic_events_queued[5m]))'
        requests_by_outcome = 'sum by (outcome, status) (increase(gateway_requests_seconds_count{organization=~"lt-.*"}[5m]))'
        requests_by_instance = 'sum by (instance) (increase(gateway_requests_seconds_count{organization=~"lt-.*"}[5m]))'
    }
    $snapshot = [ordered]@{}
    foreach ($key in $queries.Keys) {
        try {
            $snapshot[$key] = (Invoke-RestMethod -TimeoutSec 5 "http://localhost:9090/api/v1/query?query=$([uri]::EscapeDataString($queries[$key]))").data.result
        } catch { $snapshot[$key] = "query failed: $_" }
    }
    $snapshot | ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8 (Join-Path $results "$Name-prometheus.json")
}

# An overloaded run can leave route circuit breakers open (30 s); the next run must not start into them.
# (Their reported state can't be polled: an idle breaker stays "open" until a request arrives.)
function Wait-CircuitsClosed { Start-Sleep 35 }

function Wait-Gateways {
    for ($i = 0; $i -lt 60; $i++) {
        # ready = configuration loaded from Kafka (the JVM being up is not enough)
        $up = 1..3 | Where-Object { (docker exec "gatekeeper-gateway-$_" curl -sf localhost:8080/_gateway/health 2>$null) -match '"status":"UP"' }
        if (@($up).Count -eq 3) { return }
        Start-Sleep 3
    }
    throw 'Not all gateway instances came back'
}

Push-Location $root
try {
    Write-MachineInfo
    Initialize-Stack
    $run = { param($name) $Test -eq 'all' -or $Test -eq $name }

    if (& $run 'baseline') { Invoke-K6 'baseline.js' 'baseline' }
    if (& $run 'throughput') { Invoke-K6 'throughput.js' 'throughput' @{ MAX_RATE = $MaxRate } }
    if (& $run 'scaling') {
        # one instance, addressed directly (no nginx), then all three behind nginx
        Invoke-K6 'throughput.js' 'scaling-1-instance' @{ MAX_RATE = $MaxRate; GATEWAY_URL = 'http://smart-gateway-1:8080' }
        Wait-CircuitsClosed
        Invoke-K6 'throughput.js' 'scaling-3-instances' @{ MAX_RATE = $MaxRate }
    }
    if (& $run 'ratelimit') { Invoke-K6 'rate-limit.js' 'rate-limit' }
    if (& $run 'hotreload') { Invoke-K6 'hot-reload.js' 'hot-reload' }
    if (& $run 'failover') {
        Invoke-K6WithOutage 'resilience.js' 'failover-gateway-2' 'smart-gateway-2' @{ RATE = $SteadyRate }
        Wait-Gateways
    }
    if (& $run 'chaos') {
        foreach ($service in $ChaosServices) {
            Invoke-K6WithOutage 'resilience.js' "chaos-$service" $service @{ RATE = $SteadyRate }
            Wait-Gateways
            Wait-CircuitsClosed
        }
    }
    Write-Host "`nResults in $results" -ForegroundColor Green
}
finally {
    Pop-Location
}
