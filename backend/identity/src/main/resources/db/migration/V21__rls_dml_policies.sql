-- V21: Add the missing DML (INSERT/UPDATE/DELETE) row-level security policies.
-- V13 enforced SELECT isolation; V16/V17/V19 added SELECT + WITH CHECK for the new
-- tables. This migration adds write policies for every tenant-scoped table so the
-- awap_app runtime role can persist data, and re-creates SECURITY DEFINER helpers
-- for the auth flows that legitimately run before a tenant context exists.

-- ── tenants ─────────────────────────────────────────────────────────────────
-- tenants rows are keyed by id (there is no tenant_id column).
CREATE POLICY tenants_update ON tenants FOR UPDATE
    USING (id = current_setting('awap.tenant_id', true)::uuid)
    WITH CHECK (id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY tenants_delete ON tenants FOR DELETE
    USING (id = current_setting('awap.tenant_id', true)::uuid);

-- ── workflow_definitions ────────────────────────────────────────────────────
CREATE POLICY workflow_definitions_insert ON workflow_definitions FOR INSERT
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY workflow_definitions_update ON workflow_definitions FOR UPDATE
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY workflow_definitions_delete ON workflow_definitions FOR DELETE
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid);

-- ── workflow_versions ───────────────────────────────────────────────────────
CREATE POLICY workflow_versions_insert ON workflow_versions FOR INSERT
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY workflow_versions_update ON workflow_versions FOR UPDATE
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY workflow_versions_delete ON workflow_versions FOR DELETE
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid);

-- ── tasks ───────────────────────────────────────────────────────────────────
CREATE POLICY tasks_insert ON tasks FOR INSERT
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY tasks_update ON tasks FOR UPDATE
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY tasks_delete ON tasks FOR DELETE
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid);

-- ── form_definitions ────────────────────────────────────────────────────────
CREATE POLICY form_definitions_insert ON form_definitions FOR INSERT
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY form_definitions_update ON form_definitions FOR UPDATE
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY form_definitions_delete ON form_definitions FOR DELETE
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid);

-- ── form_submissions ────────────────────────────────────────────────────────
CREATE POLICY form_submissions_insert ON form_submissions FOR INSERT
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY form_submissions_update ON form_submissions FOR UPDATE
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY form_submissions_delete ON form_submissions FOR DELETE
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid);

-- ── workflow_instances ──────────────────────────────────────────────────────
CREATE POLICY workflow_instances_insert ON workflow_instances FOR INSERT
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY workflow_instances_update ON workflow_instances FOR UPDATE
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY workflow_instances_delete ON workflow_instances FOR DELETE
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid);

-- ── execution_logs (immutable audit; write-once) ────────────────────────────
CREATE POLICY execution_logs_insert ON execution_logs FOR INSERT
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY execution_logs_delete ON execution_logs FOR DELETE
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid);

-- ── organization tables ─────────────────────────────────────────────────────
CREATE POLICY companies_insert ON companies FOR INSERT
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY companies_update ON companies FOR UPDATE
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY companies_delete ON companies FOR DELETE
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid);

CREATE POLICY departments_insert ON departments FOR INSERT
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY departments_update ON departments FOR UPDATE
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY departments_delete ON departments FOR DELETE
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid);

CREATE POLICY teams_insert ON teams FOR INSERT
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY teams_update ON teams FOR UPDATE
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY teams_delete ON teams FOR DELETE
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid);

CREATE POLICY positions_insert ON positions FOR INSERT
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY positions_update ON positions FOR UPDATE
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY positions_delete ON positions FOR DELETE
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid);

CREATE POLICY organization_users_insert ON organization_users FOR INSERT
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY organization_users_update ON organization_users FOR UPDATE
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY organization_users_delete ON organization_users FOR DELETE
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid);

-- ── graph/runtime tables (SELECT + WITH CHECK already exist from V16) ────────
-- Add DELETE policies that V16 omitted.
CREATE POLICY workflow_nodes_delete ON workflow_nodes FOR DELETE
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY workflow_transitions_delete ON workflow_transitions FOR DELETE
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY workflow_variables_delete ON workflow_variables FOR DELETE
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY execution_contexts_delete ON execution_contexts FOR DELETE
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY node_executions_delete ON node_executions FOR DELETE
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid);

-- ── outbox_events (SELECT + WITH CHECK already exist from V17) ───────────────
CREATE POLICY outbox_events_delete ON outbox_events FOR DELETE
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid);

-- ── file_records (SELECT + WITH CHECK already exist from V19) ────────────────
CREATE POLICY file_records_delete ON file_records FOR DELETE
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid);

-- ── idempotency_records: enable RLS (was missing entirely) ──────────────────
ALTER TABLE idempotency_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE idempotency_records FORCE ROW LEVEL SECURITY;
CREATE POLICY idempotency_records_select ON idempotency_records FOR SELECT
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY idempotency_records_insert ON idempotency_records FOR INSERT
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY idempotency_records_update ON idempotency_records FOR UPDATE
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY idempotency_records_delete ON idempotency_records FOR DELETE
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid);

-- ── Auth bypass: SECURITY DEFINER write helpers ─────────────────────────────
-- login/refresh run before a tenant context exists, so these writes must bypass
-- RLS through owner (awap) functions, mirroring the V12/V15 read helpers.
CREATE FUNCTION awap_record_failed_login(p_user UUID)
RETURNS void
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
  UPDATE users SET
    failed_login_attempts = CASE WHEN failed_login_attempts + 1 >= 5 THEN 0 ELSE failed_login_attempts + 1 END,
    locked_until = CASE WHEN failed_login_attempts + 1 >= 5 THEN now() + interval '15 minutes' ELSE locked_until END,
    updated_at = now()
  WHERE id = p_user
$$;

CREATE FUNCTION awap_reset_failed_login(p_user UUID)
RETURNS void
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
  UPDATE users SET failed_login_attempts = 0, locked_until = NULL, updated_at = now()
  WHERE id = p_user
$$;

CREATE FUNCTION awap_revoke_refresh_token(p_hash VARCHAR)
RETURNS void
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
  UPDATE refresh_tokens SET revoked = true WHERE token_hash = p_hash
$$;

GRANT EXECUTE ON FUNCTION awap_record_failed_login(UUID) TO awap_app;
GRANT EXECUTE ON FUNCTION awap_reset_failed_login(UUID) TO awap_app;
GRANT EXECUTE ON FUNCTION awap_revoke_refresh_token(VARCHAR) TO awap_app;
