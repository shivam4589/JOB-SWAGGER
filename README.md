# JobQueue

[![CI](https://github.com/shivam4589/JOB-SWAGGER/actions/workflows/ci.yml/badge.svg)](https://github.com/shivam4589/JOB-SWAGGER/actions/workflows/ci.yml)

A distributed job scheduler / task queue built on PostgreSQL.
Clients submit jobs over REST; worker containers (added in phase 2) claim jobs safely
using `SELECT ... FOR UPDATE SKIP LOCKED`.

## Run

```bash
docker compose up --build
```

- API: http://localhost:8081
- Swagger UI: http://localhost:8081/swagger-ui.html
- Health: http://localhost:8081/actuator/health

## Try it

```bash
# submit a job
curl -X POST localhost:8081/api/jobs -H "Content-Type: application/json" \
  -d '{"type":"SEND_EMAIL","payload":{"to":"user@example.com"},"priority":7}'

# check status
curl localhost:8081/api/jobs/<id>

# list pending jobs
curl "localhost:8081/api/jobs?status=PENDING&limit=20"
```

## Workers

```bash
docker compose up -d --build --scale worker=3   # 3 worker containers x 4 threads each
docker compose logs -f worker
```

Job types are handled by `JobHandler` beans (`SEND_EMAIL`, `ALWAYS_FAIL` are included as demos).
To add a type, implement `JobHandler` and annotate it with `@Component`.

## Job lifecycle

```
PENDING --claim--> RUNNING --success--> SUCCEEDED
                      |
                      +--failure, attempts left--> PENDING (run_at pushed out by exponential backoff)
                      +--failure, no attempts left--> DEAD
                      +--worker vanished (lock older than timeout)--> reaper: PENDING, or DEAD if no attempts left
```

- Backoff: `base * 2^(attempt-1)`, capped, with +/-25% jitter (`jobqueue.worker.backoff-*`).
- Delivery is **at-least-once**: if a worker dies mid-job the job runs again, so handlers should be idempotent.
- State changes are guarded by `locked_by`, so a slow worker that was already reclaimed cannot overwrite the new owner's result.
- `GET /api/jobs/{id}/attempts` shows every try with worker, timing and error.
- `FAILED` is a legacy status from phase 2 and is no longer used.

## Crash recovery demo

```bash
# 1. submit a 20 s job, 2. see which container runs it, 3. kill that container, 4. watch it get reclaimed
curl -X POST localhost:8081/api/jobs -H "Content-Type: application/json" -d '{"type":"SLOW","payload":{"seconds":20}}'
docker compose exec postgres psql -U jobqueue -c "SELECT id, status, locked_by, attempts FROM jobs WHERE status='RUNNING';"
docker kill <first 12 chars of locked_by>
docker compose logs -f worker      # ~30 s later: "Reaper reclaimed 1 abandoned job(s)", then it succeeds on attempt 2
```

## Benchmarks

`loadtest/run-loadtest.ps1` (PowerShell) bulk-inserts a batch of jobs, scales the worker containers
(1, 3, 6 by default, 4 threads each) and measures how long they take to drain the queue.
Throughput comes from timestamps stored in Postgres, and the script also reports duplicate
executions (must be 0) and failures.

```powershell
.\loadtest\run-loadtest.ps1 -Type NOOP -Jobs 5000        # queue overhead only
.\loadtest\run-loadtest.ps1 -Type SEND_EMAIL -Jobs 1000  # ~300 ms of simulated work per job
```

It empties the `jobs` tables first. Results are saved to `loadtest/results-<type>.md`.

## Tests

```bash
mvn test        # uses Testcontainers, Docker must be running
```

## Roadmap
- [x] Phase 1: schema, REST API, Docker Compose
- [x] Phase 2: worker + SKIP LOCKED claiming
- [x] Phase 3: retries, backoff, dead letters, stuck-job reaper
- [ ] Phase 4: cancellation, delayed/recurring jobs
- [ ] Phase 5: metrics
- [x] Phase 6: CI (GitHub Actions) and load-test script
