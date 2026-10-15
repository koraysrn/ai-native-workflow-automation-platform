-- Allow auth/onboarding INSERTs that happen before a tenant context exists.
-- The runtime role (awap_app) can only insert through the application code paths;
-- reads/updates/deletes remain protected by the mandatory RLS policies.

DROP POLICY IF EXISTS tenants_insert ON tenants;
DROP POLICY IF EXISTS users_insert ON users;
DROP POLICY IF EXISTS refresh_tokens_insert ON refresh_tokens;

CREATE POLICY tenants_insert ON tenants FOR INSERT WITH CHECK (true);
CREATE POLICY users_insert ON users FOR INSERT WITH CHECK (true);
CREATE POLICY refresh_tokens_insert ON refresh_tokens FOR INSERT WITH CHECK (true);
