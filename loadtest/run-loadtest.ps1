<#
.SYNOPSIS
  Measures how fast the worker containers drain a batch of jobs at different worker counts.

.DESCRIPTION
  WARNING: empties the jobs and job_attempts tables before every run.

  Jobs are bulk-inserted with SQL, so the test measures the queue and the workers,
  not the REST API. Throughput = jobs / (last attempt finished - first attempt started),
  taken from timestamps stored in Postgres. The script also checks correctness under load:
  "Duplicates" counts jobs that were attempted more than once (must be 0 for a healthy run).

  Each worker container runs 4 polling threads (see docker-compose.yml).

.EXAMPLE
  .\loadtest\run-loadtest.ps1 -Type NOOP -Jobs 5000
  .\loadtest\run-loadtest.ps1 -Type SEND_EMAIL -Jobs 1000
  .\loadtest\run-loadtest.ps1 -Type SEND_EMAIL -Jobs 600 -WorkerCounts 1,2,4
#>
param(
    [int]$Jobs = 1000,
    [int[]]$WorkerCounts = @(1, 3, 6),
    [ValidateSet("NOOP", "SEND_EMAIL")][string]$Type = "SEND_EMAIL",
    [int]$TimeoutSeconds = 900
)

$ErrorActionPreference = "Continue"
Set-Location (Split-Path -Parent $PSScriptRoot)   # project root, where docker-compose.yml lives

function Invoke-Sql([string]$Sql) {
    $out = docker compose exec -T postgres psql -U jobqueue -d jobqueue -t -A -c $Sql 2>&1
    if ($LASTEXITCODE -ne 0) { throw "SQL failed: $out" }
    return ($out | Out-String).Trim()
}

function Wait-ForWorkers([int]$Count) {
    $deadline = (Get-Date).AddSeconds(150)
    while ((Get-Date) -lt $deadline) {
        $running = @(docker compose ps --status running -q worker 2>$null).Count
        $logs = docker compose logs worker --no-color 2>&1 | Out-String
        $started = ([regex]::Matches($logs, "Worker started")).Count
        if ($running -eq $Count -and $started -ge $Count) { return }
        Start-Sleep -Seconds 3
    }
    throw "Timed out waiting for $Count worker container(s) to start"
}

$rows = @()

foreach ($w in $WorkerCounts) {
    Write-Host ""
    Write-Host "=== $w worker container(s) x 4 threads, $Jobs x $Type jobs ===" -ForegroundColor Cyan

    docker compose up -d --scale worker=$w 2>&1 | Out-Null
    Wait-ForWorkers $w
    Start-Sleep -Seconds 3

    Invoke-Sql "TRUNCATE jobs CASCADE;" | Out-Null
    Invoke-Sql "INSERT INTO jobs (id, type, payload, status, priority, run_at, max_attempts) SELECT gen_random_uuid(), '$Type', jsonb_build_object('to','load@test.com'), 'PENDING', 5, now(), 3 FROM generate_series(1, $Jobs);" | Out-Null

    $sw = [Diagnostics.Stopwatch]::StartNew()
    $done = 0
    while ($sw.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        $done = [int](Invoke-Sql "SELECT count(*) FROM jobs WHERE status IN ('SUCCEEDED','DEAD');")
        Write-Host ("`r  finished {0}/{1}" -f $done, $Jobs) -NoNewline
        if ($done -ge $Jobs) { break }
        Start-Sleep -Seconds 2
    }
    Write-Host ""
    if ($done -lt $Jobs) { Write-Warning "Timed out with only $done/$Jobs jobs finished" }

    $stats = Invoke-Sql "SELECT count(*) FILTER (WHERE success), extract(epoch FROM (max(finished_at) - min(started_at))), count(*) - count(DISTINCT job_id), count(*) FILTER (WHERE NOT success) FROM job_attempts;"
    $p = $stats -split '\|'
    $ok = [int]$p[0]
    $secs = [double]::Parse($p[1], [Globalization.CultureInfo]::InvariantCulture)
    $dups = [int]$p[2]
    $failed = [int]$p[3]
    $rate = if ($secs -gt 0) { [math]::Round($ok / $secs, 1) } else { 0 }

    $rows += [pscustomobject]@{
        Workers    = $w
        Threads    = $w * 4
        Jobs       = $ok
        Seconds    = [math]::Round($secs, 2)
        JobsPerSec = $rate
        Duplicates = $dups
        Failed     = $failed
    }
    Write-Host ("  -> {0} jobs in {1}s = {2} jobs/sec, duplicates: {3}, failed: {4}" -f $ok, [math]::Round($secs, 2), $rate, $dups, $failed) -ForegroundColor Green
}

Write-Host ""
Write-Host "Results ($Type)" -ForegroundColor Cyan
$rows | Format-Table -AutoSize

$md = @(
    "| Workers | Threads | Jobs | Seconds | Jobs/sec | Duplicate attempts | Failed |",
    "|---|---|---|---|---|---|---|"
)
foreach ($r in $rows) {
    $md += "| $($r.Workers) | $($r.Threads) | $($r.Jobs) | $($r.Seconds) | $($r.JobsPerSec) | $($r.Duplicates) | $($r.Failed) |"
}
$file = Join-Path $PSScriptRoot "results-$Type.md"
[IO.File]::WriteAllLines($file, $md)
Write-Host "Markdown table saved to $file"

# leave the stack in its normal 3-worker state
docker compose up -d --scale worker=3 2>&1 | Out-Null
