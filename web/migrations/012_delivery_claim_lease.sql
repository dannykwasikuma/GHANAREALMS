-- 012: lease-based claiming so concurrent pollers cannot deliver the same row.
-- Safe to re-run.
ALTER TABLE delivery_queue ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_delivery_queue_claim ON delivery_queue (status, claimed_at)
  WHERE status IN ('PENDING','RETRYING','PROCESSING');
