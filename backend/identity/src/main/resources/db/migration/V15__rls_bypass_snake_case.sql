-- Recreate auth bypass functions with snake_case result aliases, matching the
-- column names Hibernate expects for native-query entity mapping.

DROP FUNCTION awap_find_tenant_by_slug(VARCHAR);
DROP FUNCTION awap_find_user_by_email(UUID, VARCHAR);
DROP FUNCTION awap_find_refresh_token(VARCHAR);

CREATE FUNCTION awap_find_tenant_by_slug(p_slug VARCHAR)
RETURNS TABLE(
  id uuid, slug varchar, name varchar, plan varchar,
  max_users int, max_workflows int, max_ai_calls_per_month bigint,
  active boolean, created_at timestamptz, updated_at timestamptz, deleted boolean
)
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT t.id, t.slug, t.name, t.plan,
         t.max_users, t.max_workflows, t.max_ai_calls_per_month,
         t.active, t.created_at, t.updated_at, t.deleted
  FROM tenants t WHERE t.slug = p_slug AND t.deleted = false
$$;

CREATE FUNCTION awap_find_user_by_email(p_tenant UUID, p_email VARCHAR)
RETURNS TABLE(
  id uuid, tenant_id uuid, email varchar, password_hash varchar,
  full_name varchar, role varchar, failed_login_attempts int,
  locked_until timestamptz, created_at timestamptz, updated_at timestamptz,
  deleted boolean
)
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT u.id, u.tenant_id, u.email, u.password_hash, u.full_name, u.role,
         u.failed_login_attempts, u.locked_until, u.created_at, u.updated_at,
         u.deleted
  FROM users u WHERE u.tenant_id = p_tenant AND u.email = p_email AND u.deleted = false
$$;

CREATE FUNCTION awap_find_refresh_token(p_hash VARCHAR)
RETURNS TABLE(
  id uuid, tenant_id uuid, user_id uuid, token_hash varchar,
  expires_at timestamptz, revoked boolean
)
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT r.id, r.tenant_id, r.user_id, r.token_hash, r.expires_at, r.revoked
  FROM refresh_tokens r WHERE r.token_hash = p_hash
$$;

GRANT EXECUTE ON FUNCTION awap_find_tenant_by_slug(VARCHAR) TO awap_app;
GRANT EXECUTE ON FUNCTION awap_find_user_by_email(UUID, VARCHAR) TO awap_app;
GRANT EXECUTE ON FUNCTION awap_find_refresh_token(VARCHAR) TO awap_app;
