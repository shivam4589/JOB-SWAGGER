CREATE TABLE jobs (
    id            UUID         PRIMARY KEY,
    type          VARCHAR(100) NOT NULL,
    payload       JSONB        NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    priority      INT          NOT NULL DEFAULT 5,
    run_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    attempts      INT          NOT NULL DEFAULT 0,
    max_attempts  INT          NOT NULL DEFAULT 5,
    locked_by     VARCHAR(100),
    locked_at     TIMESTAMPTZ,
    last_error    TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_jobs_status
        CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED','DEAD','CANCELLED')),
    CONSTRAINT chk_jobs_priority CHECK (priority BETWEEN 1 AND 10)
);

-- Partial index: workers only ever look at PENDING jobs, so keep the index small and fast.
CREATE INDEX idx_jobs_claim ON jobs (priority DESC, run_at) WHERE status = 'PENDING';
CREATE INDEX idx_jobs_status_created ON jobs (status, created_at DESC);

CREATE TABLE job_attempts (
    id           BIGSERIAL    PRIMARY KEY,
    job_id       UUID         NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    worker_id    VARCHAR(100),
    started_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at  TIMESTAMPTZ,
    success      BOOLEAN,
    error        TEXT
);

CREATE INDEX idx_job_attempts_job ON job_attempts (job_id);
