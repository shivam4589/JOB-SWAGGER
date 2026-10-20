-- 1) Wake-up notifications: tell listening workers whenever a job may have become runnable.
--    The payload is always empty, so many notifications inside one transaction collapse into one.
CREATE OR REPLACE FUNCTION notify_jobs_available() RETURNS trigger AS $$
BEGIN
    PERFORM pg_notify('jobqueue_jobs', '');
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- new jobs (one notification per INSERT statement, even for bulk inserts)
CREATE TRIGGER trg_jobs_inserted
    AFTER INSERT ON jobs
    FOR EACH STATEMENT EXECUTE FUNCTION notify_jobs_available();

-- jobs that go back to PENDING (retry, manual retry, reclaimed from a lost worker) or get a new run_at
CREATE TRIGGER trg_jobs_requeued
    AFTER UPDATE OF status, run_at ON jobs
    FOR EACH ROW
    WHEN (NEW.status = 'PENDING'
          AND (OLD.status IS DISTINCT FROM NEW.status OR OLD.run_at IS DISTINCT FROM NEW.run_at))
    EXECUTE FUNCTION notify_jobs_available();

-- 2) Workers sleep until the earliest waiting job is due; this index makes that lookup cheap.
CREATE INDEX idx_jobs_pending_run_at ON jobs (run_at) WHERE status = 'PENDING';

-- 3) The retention job deletes old finished jobs oldest-first.
CREATE INDEX idx_jobs_finished_updated_at ON jobs (updated_at)
    WHERE status IN ('SUCCEEDED', 'DEAD', 'CANCELLED', 'FAILED');
