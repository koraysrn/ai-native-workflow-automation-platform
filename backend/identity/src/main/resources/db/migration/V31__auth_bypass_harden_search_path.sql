-- V31: Harden the SECURITY DEFINER auth-bypass functions against the pg_temp
-- search-path attack. A function that searches pg_temp before public can be
-- hijacked by a low-privilege user who creates a shadow table in their temp
-- schema. Pin the search_path to "public, pg_temp" so unqualified names always
-- resolve to public first. The V30 RETURNS SETOF <table> full-row form is
-- preserved so future audit columns never break the entity mapping again.

DROP FUNCTION awap_find_tenant_by_slug(VARCHAR);
DROP FUNCTION awap_find_user_by_email(UUID, VARCHAR);
DROP FUNCTION awap_find_refresh_token(VARCHAR);

CREATE FUNCTION awap_find_tenant_by_slug(p_slug VARCHAR)
RETURNS SETOF tenants
LANGUAGE sql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
  SELECT * FROM tenants WHERE slug = p_slug AND deleted = false
$$;

CREATE FUNCTION awap_find_user_by_email(p_tenant UUID, p_email VARCHAR)
RETURNS SETOF users
LANGUAGE sql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
  SELECT * FROM users WHERE tenant_id = p_tenant AND email = p_email AND deleted = false
$$;

CREATE FUNCTION awap_find_refresh_token(p_hash VARCHAR)
RETURNS SETOF refresh_tokens
LANGUAGE sql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
  SELECT * FROM refresh_tokens WHERE token_hash = p_hash
$$;

GRANT EXECUTE ON FUNCTION awap_find_tenant_by_slug(VARCHAR) TO awap_app;
GRANT EXECUTE ON FUNCTION awap_find_user_by_email(UUID, VARCHAR) TO awap_app;
GRANT EXECUTE ON FUNCTION awap_find_refresh_token(VARCHAR) TO awap_app;
