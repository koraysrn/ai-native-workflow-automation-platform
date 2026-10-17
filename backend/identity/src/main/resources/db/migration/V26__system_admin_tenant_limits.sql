-- V26: SECURITY DEFINER helpers so SYSTEM_ADMIN can read and update another
-- tenant's limits while RLS remains the primary isolation mechanism (issue #30).
-- The application still enforces authorization via requireAccess.

CREATE OR REPLACE FUNCTION awap_find_tenant_by_id(p_tenant UUID)
RETURNS SETOF tenants
LANGUAGE sql
SECURITY DEFINER
SET search_path = public AS $$
    SELECT * FROM tenants WHERE id = p_tenant;
$$;

REVOKE ALL ON FUNCTION awap_find_tenant_by_id(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION awap_find_tenant_by_id(UUID) TO awap_app;

CREATE OR REPLACE FUNCTION awap_update_tenant_limits(
    p_tenant UUID,
    p_plan TEXT,
    p_max_users INT,
    p_max_workflows INT,
    p_max_ai_calls BIGINT
) RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public AS $$
BEGIN
    UPDATE tenants
       SET plan = p_plan,
           max_users = p_max_users,
           max_workflows = p_max_workflows,
           max_ai_calls_per_month = p_max_ai_calls,
           updated_at = now()
     WHERE id = p_tenant;
END;
$$;

REVOKE ALL ON FUNCTION awap_update_tenant_limits(UUID, TEXT, INT, INT, BIGINT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION awap_update_tenant_limits(UUID, TEXT, INT, INT, BIGINT) TO awap_app;
