-- Tenant invitation flow (issue #38).

CREATE TABLE invitations (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    email VARCHAR(255) NOT NULL,
    role VARCHAR(32) NOT NULL,
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    consumed BOOLEAN NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    invited_by UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_invitations_tenant ON invitations(tenant_id);
CREATE INDEX idx_invitations_email ON invitations(tenant_id, email);

-- Invitations are tenant-scoped and must follow the same RLS isolation model
-- as every other tenant table (V7/V13/V14/V15/V21).
ALTER TABLE invitations ENABLE ROW LEVEL SECURITY;

CREATE POLICY invitations_select ON invitations FOR SELECT
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY invitations_insert ON invitations FOR INSERT
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY invitations_update ON invitations FOR UPDATE
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY invitations_delete ON invitations FOR DELETE
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid);

-- The accept flow runs before any tenant context exists, so it looks up the
-- invitation through a SECURITY DEFINER helper. The application still enforces
-- single-use + expiry in the domain before acting on the result.
CREATE OR REPLACE FUNCTION awap_find_invitation_by_token_hash(p_hash VARCHAR)
RETURNS SETOF invitations
LANGUAGE sql
SECURITY DEFINER
SET search_path = public AS $$
    SELECT * FROM invitations WHERE token_hash = p_hash;
$$;

REVOKE ALL ON FUNCTION awap_find_invitation_by_token_hash(VARCHAR) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION awap_find_invitation_by_token_hash(VARCHAR) TO awap_app;

-- Accept also needs the target tenant and its user count without a tenant
-- context. These helpers mirror the existing auth bypass functions.
CREATE OR REPLACE FUNCTION awap_find_tenant_by_id(p_tenant UUID)
RETURNS SETOF tenants
LANGUAGE sql
SECURITY DEFINER
SET search_path = public AS $$
    SELECT * FROM tenants WHERE id = p_tenant;
$$;

REVOKE ALL ON FUNCTION awap_find_tenant_by_id(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION awap_find_tenant_by_id(UUID) TO awap_app;

CREATE OR REPLACE FUNCTION awap_count_tenant_users(p_tenant UUID)
RETURNS BIGINT
LANGUAGE sql
SECURITY DEFINER
SET search_path = public AS $$
    SELECT count(*) FROM users WHERE tenant_id = p_tenant AND deleted = false;
$$;

REVOKE ALL ON FUNCTION awap_count_tenant_users(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION awap_count_tenant_users(UUID) TO awap_app;
