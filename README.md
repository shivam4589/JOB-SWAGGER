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

## Frontend

The React dashboard is in `frontend/`. Start the API and database with Docker Compose, then run the frontend in a separate terminal:

```bash
cd frontend
npm install
npm run dev
```

Open the URL printed by Vite (usually http://localhost:5173). The dashboard proxies API requests to `http://localhost:8081`, refreshes job data every five seconds, and supports submitting jobs and viewing their attempts.

To create a production bundle, run `npm run build` from `frontend/`; the output is written to `frontend/dist/`.

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
- [x] Phase 4: cancel, retry, stats, filtering and paging, CORS, dashboard frontend
- [x] CI (GitHub Actions) and load-test script
- [ ] Phase 5: recurring (cron) jobs, idempotency keys
- [ ] Phase 6: metrics (Prometheus / Grafana)


## Benchmark results

All services (PostgreSQL 16, API, workers) ran on one laptop in Docker Desktop with 4 CPUs and about 3.7 GB RAM, so treat these as relative numbers, not absolute capacity. Each worker container runs 4 polling threads. Reproduce with `loadtest/run-loadtest.ps1`.

**Queue overhead** (`NOOP` jobs, 5,000 per run):

| Workers | Threads | Jobs | Seconds | Jobs/sec | Duplicate attempts | Failed |
|---|---|---|---|---|---|---|
| 1 | 4 | 5000 | 15.74 | 317.7 | 0 | 0 |
| 3 | 12 | 5000 | 10.25 | 487.6 | 0 | 0 |
| 6 | 24 | 5000 | 27.56 | 181.4 | 0 | 0 |

**Scaling with real work** (`SEND_EMAIL`, about 300 ms simulated work per job, 1,000 per run):

| Workers | Threads | Jobs | Seconds | Jobs/sec | Duplicate attempts | Failed |
|---|---|---|---|---|---|---|
| 1 | 4 | 1000 | 82.46 | 12.1 | 0 | 0 |
| 2 | 8 | 1000 | 45.94 | 21.8 | 0 | 0 |
| 4 | 16 | 1000 | 30.07 | 33.3 | 0 | 0 |

What the numbers show:
- **Correctness under load:** no job was executed twice across 18,000 job runs, with up to 24 threads claiming jobs at the same time.
- **Queue overhead:** one container processes about 320 no-op jobs/sec and three reach about 490. At six containers throughput drops, most likely because seven JVMs plus Postgres compete for 4 CPUs. That reflects the test machine, not the design.
- **Scaling:** with I/O-bound jobs, throughput grows from 12.1 to 33.3 jobs/sec (2.75x) going from 1 to 4 containers. Each added container helps a bit less because all of them share the same CPUs and database.

## Dashboard

A React + Vite dashboard lives in `frontend/`. It shows live queue stats, a paged and filterable job list, per-job attempt history, and Cancel / Retry buttons.

    cd frontend
    npm install
    npm run dev    # http://localhost:5173, proxies /api to localhost:8081

