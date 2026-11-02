-- V50 (#159): give the partitioned outbox a replica identity for Debezium.
--
-- With wal_level=logical, PostgreSQL rejects UPDATEs on a published table that has no replica
-- identity. The outbox relay claims rows with an UPDATE before publishing, so CDC would break the
-- polling path as soon as the outbox is part of a logical publication. The table has no primary key
-- (id alone cannot be a partitioned primary key), so use FULL on the parent, every existing
-- partition, and every future monthly partition.

ALTER TABLE outbox_events REPLICA IDENTITY FULL;

DO $$
DECLARE
  partition_name REGCLASS;
BEGIN
  FOR partition_name IN
    SELECT child.oid::regclass
    FROM pg_inherits
    JOIN pg_class child ON child.oid = pg_inherits.inhrelid
    WHERE pg_inherits.inhparent = 'outbox_events'::regclass
  LOOP
    EXECUTE format('ALTER TABLE %s REPLICA IDENTITY FULL', partition_name);
  END LOOP;
END
$$;

-- Keep the monthly partition helper consistent with the partitioned parent (V32).
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
    EXECUTE format('ALTER TABLE %I REPLICA IDENTITY FULL', v_partition_name);
  END IF;
END
$$;
