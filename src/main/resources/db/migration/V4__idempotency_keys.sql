-- Idempotency keys: a client may attach a key to a submit; repeating the same request with the same key
-- returns the original job instead of creating a duplicate.
ALTER TABLE jobs ADD COLUMN idempotency_key VARCHAR(200);
ALTER TABLE jobs ADD COLUMN request_hash    CHAR(64);   -- SHA-256 of the canonical request, to detect key reuse with a different request

-- The database itself guarantees uniqueness, so two simultaneous requests with one key cannot both insert.
CREATE UNIQUE INDEX uq_jobs_idempotency_key ON jobs (idempotency_key) WHERE idempotency_key IS NOT NULL;
