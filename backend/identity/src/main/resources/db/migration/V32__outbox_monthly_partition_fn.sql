-- V32: Monthly outbox partition maintenance helper. V27 created a DEFAULT
-- partition (so inserts never fail) but did not create explicit monthly
-- partitions. This function creates the partition for a given month when
-- missing; OutboxPartitionScheduler invokes it on the first day of each month.

CREATE OR REPLACE FUNCTION awap_ensure_outbox_monthly_partition(p_month DATE)
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
  v_partition_name TEXT;
  v_start TIMESTAMPTZ;
  v_end TIMESTAMPTZ;
BEGIN
  v_start := date_trunc('month', p_month)::timestamptz;
  v_end := v_start + interval '1 month';
  v_partition_name := 'outbox_events_' || to_char(v_start, 'YYYY_MM');

  IF NOT EXISTS (
    SELECT 1 FROM pg_tables
    WHERE schemaname = 'public' AND tablename = v_partition_name
  ) THEN
    EXECUTE format(
      'CREATE TABLE %I PARTITION OF outbox_events FOR VALUES FROM (%L) TO (%L)',
      v_partition_name, v_start, v_end);
  END IF;
END
$$;
