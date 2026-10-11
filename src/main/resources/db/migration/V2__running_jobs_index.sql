-- The stuck-job reaper scans RUNNING jobs by lock age; keep that scan cheap.
CREATE INDEX idx_jobs_running_locked_at ON jobs (locked_at) WHERE status = 'RUNNING';
