# Free deployment: Render + Neon

One container serves the dashboard, the API and the workers. The database is a free Neon PostgreSQL.
Cost: $0. No credit card is needed for either service at the time of writing; check their sign-up pages.

## What to expect
- The free Render service **goes to sleep after 15 minutes without visitors** and takes about a minute to wake up.
  While it sleeps, workers and cron schedules do not run. When someone opens the site, it wakes up and processes
  whatever is waiting. That is fine for a portfolio demo.
- Neon's free database sleeps 5 minutes after the app stops talking to it and gives 100 compute-hours a month.
  **Do not set up an "uptime pinger"**: it keeps both awake and would use up Neon's monthly hours in a couple of weeks.
- Reading is public; creating, cancelling or retrying jobs asks for the admin login you configure below.

## 1. Database (Neon)
1. Sign up at https://neon.com and create a project. Pick the region closest to you (for example Singapore).
2. Open **Connection details**. Turn **Connection pooling OFF** so the host does not contain `-pooler`.
3. Note the host, database name, user and password. Build the JDBC URL:

       jdbc:postgresql://<host>/<database>?sslmode=require

## 2. Web service (Render)
1. Sign up at https://render.com with your GitHub account.
2. **New > Web Service**, connect the repository, then set:
   - Language / runtime: **Docker**
   - Dockerfile path: `./Dockerfile.deploy`
   - Instance type: **Free**
   - Health check path: `/actuator/health`
3. Add these environment variables:

   | Name | Value |
   |---|---|
   | `DB_URL` | the JDBC URL from step 1 |
   | `DB_USER` | Neon user |
   | `DB_PASSWORD` | Neon password |
   | `JOBQUEUE_WORKER_ENABLED` | `true` |
   | `JOBQUEUE_WORKER_CONCURRENCY` | `2` |
   | `JOBQUEUE_WORKER_POLL_INTERVAL_MS` | `1000` |
   | `JOBQUEUE_ADMIN_USER` | `admin` (or your choice) |
   | `JOBQUEUE_ADMIN_PASSWORD` | a strong password |
   | `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` | `5` |
   | `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` | `health` |
   | `SPRINGDOC_SWAGGER_UI_ENABLED` | `false` |
   | `SPRINGDOC_API_DOCS_ENABLED` | `false` |

4. **Create Web Service**. The first build takes several minutes (it builds the dashboard and the Java app).
5. Open `https://<your-service>.onrender.com`. Pushing to `main` redeploys automatically.

## 3. Check it
- The dashboard loads and the API answers at `/api/stats`.
- Create a job: the browser asks for the admin login; after logging in the job runs and shows as SUCCEEDED.
- Open the site in a private window: you can look at everything, but creating a job asks for the login.

## About LISTEN/NOTIFY on Neon
Workers are woken instantly through PostgreSQL LISTEN/NOTIFY. That needs a normal (direct) connection, which is why
the guide asks for the host **without** `-pooler`. If the connection is not available the app simply falls back to
polling, so nothing breaks; jobs just start up to a few seconds later.

## Troubleshooting
- **Build fails:** open the build log on Render and look at the first error.
- **Service restarts or says "out of memory":** the free instance has 512 MB. Lower `JOBQUEUE_WORKER_CONCURRENCY` to `1`.
- **"Failed to start", database errors:** re-check `DB_URL` (must start with `jdbc:postgresql://`, end with `?sslmode=require`, and use the host without `-pooler`).
- **First visit shows a loading page for a minute:** the free service was asleep.

## Want it always on?
A small paid server (about $5 to $7 a month) never sleeps, so workers and schedules run continuously.
Render's Starter instance at $7 a month removes the sleep as well.
