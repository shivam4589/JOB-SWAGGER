-- Recurring jobs: a schedule creates a normal job every time its cron expression fires.
CREATE TABLE schedules (
    id            UUID         PRIMARY KEY,
    name          VARCHAR(100) NOT NULL UNIQUE,
    job_type      VARCHAR(100) NOT NULL,
    payload       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    priority      INT          NOT NULL DEFAULT 5,
    max_attempts  INT          NOT NULL DEFAULT 5,
    cron          VARCHAR(120) NOT NULL,           -- normalized 6-field Spring cron (sec min hour dom month dow)
    timezone      VARCHAR(50)  NOT NULL DEFAULT 'UTC',
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    next_run_at   TIMESTAMPTZ  NOT NULL,
    last_run_at   TIMESTAMPTZ,
    last_job_id   UUID,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_schedules_priority CHECK (priority BETWEEN 1 AND 10),
    CONSTRAINT chk_schedules_attempts CHECK (max_attempts BETWEEN 1 AND 20)
);

-- The scheduler only ever looks for enabled schedules that are due.
CREATE INDEX idx_schedules_due ON schedules (next_run_at) WHERE enabled;

-- Which schedule created a job (kept when the schedule is deleted).
ALTER TABLE jobs ADD COLUMN schedule_id UUID REFERENCES schedules(id) ON DELETE SET NULL;
CREATE INDEX idx_jobs_schedule ON jobs (schedule_id, created_at DESC) WHERE schedule_id IS NOT NULL;
