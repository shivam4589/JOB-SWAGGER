-- Cooperative cancellation: asking to cancel a RUNNING job only sets this flag. The worker that owns the job
-- notices it on its next heartbeat and interrupts the handler.
ALTER TABLE jobs ADD COLUMN cancel_requested BOOLEAN NOT NULL DEFAULT FALSE;
