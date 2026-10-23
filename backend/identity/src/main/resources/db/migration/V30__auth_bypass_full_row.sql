-- V30: Auth-bypass read helpers must return the full table row. The V15 versions
-- declared an explicit column list and therefore missed the audit columns
-- (created_by, updated_by) added in V18/V23, which broke Hibernate's native-query
-- entity mapping ("The column name created_by was not found in this ResultSet").
-- Recreate them as RETURNS SETOF <table> so they always match the entity.

DROP FUNCTION awap_find_tenant_by_slug(VARCHAR);
DROP FUNCTION awap_find_user_by_email(UUID, VARCHAR);
DROP FUNCTION awap_find_refresh_token(VARCHAR);

CREATE FUNCTION awap_find_tenant_by_slug(p_slug VARCHAR)
RETURNS SETOF tenants
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT * FROM tenants WHERE slug = p_slug AND deleted = false
$$;

CREATE FUNCTION awap_find_user_by_email(p_tenant UUID, p_email VARCHAR)
RETURNS SETOF users
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT * FROM users WHERE tenant_id = p_tenant AND email = p_email AND deleted = false
$$;

CREATE FUNCTION awap_find_refresh_token(p_hash VARCHAR)
RETURNS SETOF refresh_tokens
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT * FROM refresh_tokens WHERE token_hash = p_hash
$$;

GRANT EXECUTE ON FUNCTION awap_find_tenant_by_slug(VARCHAR) TO awap_app;
GRANT EXECUTE ON FUNCTION awap_find_user_by_email(UUID, VARCHAR) TO awap_app;
GRANT EXECUTE ON FUNCTION awap_find_refresh_token(VARCHAR) TO awap_app;
