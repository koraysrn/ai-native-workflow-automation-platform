-- V45: Tenant usage metering and quota telemetry (issue #125).
--
-- usage_counters keeps one row per (tenant, UTC day, metric) and is the authoritative
-- quota input. usage_events is the idempotency ledger: its primary key is the per-event
-- idempotency key, so a retried HTTP call or a redelivered Kafka record can never be
-- counted twice. tenant_usage_limits carries per-tenant daily overrides; when a row is
-- absent the application falls back to the metric's documented default.
--
-- The metric column is a controlled enum name (bounded cardinality), never a free-form
-- label, so Prometheus labels stay cheap.

CREATE TABLE usage_counters (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    day DATE NOT NULL,
    metric VARCHAR(40) NOT NULL,
    value BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_usage_counters UNIQUE (tenant_id, day, metric),
    CONSTRAINT ck_usage_counters_value_non_negative CHECK (value >= 0)
);

CREATE INDEX idx_usage_counters_tenant_day ON usage_counters (tenant_id, day);

CREATE TABLE usage_events (
    event_key VARCHAR(180) PRIMARY KEY,
    tenant_id UUID NOT NULL,
    metric VARCHAR(40) NOT NULL,
    day DATE NOT NULL,
    delta BIGINT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_usage_events_delta_positive CHECK (delta > 0)
);

CREATE INDEX idx_usage_events_tenant_day ON usage_events (tenant_id, day);

CREATE TABLE tenant_usage_limits (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    metric VARCHAR(40) NOT NULL,
    daily_limit BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_tenant_usage_limits UNIQUE (tenant_id, metric)
);

CREATE INDEX idx_tenant_usage_limits_tenant ON tenant_usage_limits (tenant_id);

-- Retention/archive target: counters older than the retention window (and every counter
-- of a soft-deleted tenant) are moved here before the live rows are removed.
CREATE TABLE usage_counter_archive (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    day DATE NOT NULL,
    metric VARCHAR(40) NOT NULL,
    value BIGINT NOT NULL,
    archived_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_usage_counter_archive UNIQUE (tenant_id, day, metric)
);

CREATE INDEX idx_usage_counter_archive_tenant_day
    ON usage_counter_archive (tenant_id, day);

ALTER TABLE usage_counters ENABLE ROW LEVEL SECURITY;
ALTER TABLE usage_counters FORCE ROW LEVEL SECURITY;
CREATE POLICY usage_counters_isolation ON usage_counters
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);

ALTER TABLE usage_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE usage_events FORCE ROW LEVEL SECURITY;
CREATE POLICY usage_events_isolation ON usage_events
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);

ALTER TABLE tenant_usage_limits ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_usage_limits FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_usage_limits_isolation ON tenant_usage_limits
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);

ALTER TABLE usage_counter_archive ENABLE ROW LEVEL SECURITY;
ALTER TABLE usage_counter_archive FORCE ROW LEVEL SECURITY;
CREATE POLICY usage_counter_archive_isolation ON usage_counter_archive
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);

-- Atomic, idempotent metering write.
--
-- Returns the counter value after the increment. Sentinel values:
--   -2 : the event key was already recorded (duplicate delivery, nothing changed)
--   -1 : the increment would cross the daily limit (nothing is counted)
--
-- p_limit < 0 means "no limit". The usage_events insert is rolled back when the limit is
-- hit, so a rejected event is not remembered and can be retried on a later day.
-- SECURITY INVOKER keeps RLS in force: the caller must have awap.tenant_id set on the
-- session, which the tenant transaction aspect guarantees for HTTP and Kafka threads.
CREATE OR REPLACE FUNCTION awap_usage_record_event(
    p_event_key TEXT,
    p_tenant UUID,
    p_day DATE,
    p_metric TEXT,
    p_delta BIGINT,
    p_limit BIGINT
) RETURNS BIGINT
LANGUAGE plpgsql
SET search_path = public, pg_temp
AS $$
DECLARE
    v_inserted INT;
    v_value BIGINT;
BEGIN
    IF p_delta IS NULL OR p_delta <= 0 THEN
        RAISE EXCEPTION 'usage delta must be positive';
    END IF;

    INSERT INTO usage_events (event_key, tenant_id, metric, day, delta, recorded_at)
    VALUES (p_event_key, p_tenant, p_metric, p_day, p_delta, now())
    ON CONFLICT (event_key) DO NOTHING;
    GET DIAGNOSTICS v_inserted = ROW_COUNT;
    IF v_inserted = 0 THEN
        RETURN -2;
    END IF;

    INSERT INTO usage_counters (id, tenant_id, day, metric, value, updated_at)
    VALUES (gen_random_uuid(), p_tenant, p_day, p_metric, 0, now())
    ON CONFLICT (tenant_id, day, metric) DO NOTHING;

    -- Single conditional UPDATE: concurrent callers serialize on the row lock and the
    -- predicate is re-evaluated after the wait, so exactly `limit` increments succeed.
    UPDATE usage_counters
       SET value = value + p_delta,
           updated_at = now()
     WHERE tenant_id = p_tenant
       AND day = p_day
       AND metric = p_metric
       AND (p_limit < 0 OR value + p_delta <= p_limit)
    RETURNING value INTO v_value;

    IF v_value IS NULL THEN
        DELETE FROM usage_events WHERE event_key = p_event_key;
        RETURN -1;
    END IF;

    RETURN v_value;
END
$$;

REVOKE ALL ON FUNCTION awap_usage_record_event(TEXT, UUID, DATE, TEXT, BIGINT, BIGINT)
    FROM PUBLIC;
GRANT EXECUTE ON FUNCTION awap_usage_record_event(TEXT, UUID, DATE, TEXT, BIGINT, BIGINT)
    TO awap_app;

-- Authorized cross-tenant read used by the usage API (the service still enforces
-- object-level authorization; RLS alone cannot serve SYSTEM_ADMIN cross-tenant reads).
CREATE OR REPLACE FUNCTION awap_usage_counters(p_tenant UUID, p_from DATE, p_to DATE)
RETURNS SETOF usage_counters
LANGUAGE sql
SECURITY DEFINER
STABLE
SET search_path = public, pg_temp
AS $$
    SELECT * FROM usage_counters
    WHERE tenant_id = p_tenant AND day BETWEEN p_from AND p_to
    ORDER BY day, metric
$$;

REVOKE ALL ON FUNCTION awap_usage_counters(UUID, DATE, DATE) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION awap_usage_counters(UUID, DATE, DATE) TO awap_app;

CREATE OR REPLACE FUNCTION awap_usage_limits(p_tenant UUID)
RETURNS SETOF tenant_usage_limits
LANGUAGE sql
SECURITY DEFINER
STABLE
SET search_path = public, pg_temp
AS $$
    SELECT * FROM tenant_usage_limits WHERE tenant_id = p_tenant ORDER BY metric
$$;

REVOKE ALL ON FUNCTION awap_usage_limits(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION awap_usage_limits(UUID) TO awap_app;

-- Nightly reconciliation: recompute every counter in the window from the raw
-- usage_events ledger, repair drifting rows and return the rows that were repaired.
CREATE OR REPLACE FUNCTION awap_usage_reconcile(p_from DATE, p_to DATE)
RETURNS TABLE(
    tenant_id UUID,
    day DATE,
    metric TEXT,
    expected BIGINT,
    actual BIGINT,
    drift BIGINT
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
#variable_conflict use_column
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        WITH agg AS (
            SELECT e.tenant_id AS t, e.day AS d, e.metric AS m, SUM(e.delta) AS expected
            FROM usage_events e
            WHERE e.day BETWEEN p_from AND p_to
            GROUP BY e.tenant_id, e.day, e.metric
        )
        SELECT a.t, a.d, a.m, a.expected, COALESCE(c.value, 0) AS actual
        FROM agg a
        LEFT JOIN usage_counters c
               ON c.tenant_id = a.t AND c.day = a.d AND c.metric = a.m
        WHERE COALESCE(c.value, 0) <> a.expected
    LOOP
        INSERT INTO usage_counters (id, tenant_id, day, metric, value, updated_at)
        VALUES (gen_random_uuid(), r.t, r.d, r.m, r.expected, now())
        ON CONFLICT (tenant_id, day, metric)
        DO UPDATE SET value = EXCLUDED.value, updated_at = now();

        tenant_id := r.t;
        day := r.d;
        metric := r.m;
        expected := r.expected;
        actual := r.actual;
        drift := r.actual - r.expected;
        RETURN NEXT;
    END LOOP;
END
$$;

REVOKE ALL ON FUNCTION awap_usage_reconcile(DATE, DATE) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION awap_usage_reconcile(DATE, DATE) TO awap_app;

-- Retention/archive: counters older than the cutoff and all counters of soft-deleted
-- tenants move to usage_counter_archive; their raw events are removed. Returns the
-- number of counter rows archived/removed.
CREATE OR REPLACE FUNCTION awap_usage_archive(p_cutoff DATE)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_total INT := 0;
    v_rows INT;
BEGIN
    INSERT INTO usage_counter_archive (id, tenant_id, day, metric, value, archived_at)
    SELECT c.id, c.tenant_id, c.day, c.metric, c.value, now()
    FROM usage_counters c
    LEFT JOIN tenants t ON t.id = c.tenant_id
    WHERE c.day < p_cutoff OR COALESCE(t.deleted, false) = true
    ON CONFLICT (tenant_id, day, metric) DO NOTHING;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    v_total := v_total + v_rows;

    DELETE FROM usage_counters c
    USING tenants t
    WHERE t.id = c.tenant_id AND COALESCE(t.deleted, false) = true;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    v_total := v_total + v_rows;

    DELETE FROM usage_counters WHERE day < p_cutoff;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    v_total := v_total + v_rows;

    DELETE FROM usage_events e
    USING tenants t
    WHERE t.id = e.tenant_id AND COALESCE(t.deleted, false) = true;
    DELETE FROM usage_events WHERE day < p_cutoff;

    RETURN v_total;
END
$$;

REVOKE ALL ON FUNCTION awap_usage_archive(DATE) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION awap_usage_archive(DATE) TO awap_app;
