-- Auth/onboarding bypass for RLS via SECURITY DEFINER functions.
-- awap_app is the non-superuser runtime role; awap (superuser) owns these
-- functions, so they bypass row-level security. RETURN TABLE uses camelCase
-- aliases so Hibernate native-query result mapping works.

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'awap_app') THEN
    CREATE ROLE awap_app LOGIN PASSWORD 'awap_app';
  END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO awap_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO awap_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO awap_app;

CREATE FUNCTION awap_find_tenant_by_slug(p_slug VARCHAR)
RETURNS TABLE(
  id uuid, slug varchar, name varchar, plan varchar,
  "maxUsers" int, "maxWorkflows" int, "maxAiCallsPerMonth" bigint,
  active boolean, "createdAt" timestamptz, "updatedAt" timestamptz, deleted boolean
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
  id uuid, "tenantId" uuid, email varchar, "passwordHash" varchar,
  "fullName" varchar, role varchar, "failedLoginAttempts" int,
  "lockedUntil" timestamptz, "createdAt" timestamptz, "updatedAt" timestamptz,
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
  id uuid, "tenantId" uuid, "userId" uuid, "tokenHash" varchar,
  "expiresAt" timestamptz, revoked boolean
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
