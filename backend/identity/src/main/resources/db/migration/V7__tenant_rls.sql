-- Row-Level Security for tenant isolation (PostgreSQL only; H2 tests disable Flyway).
-- Safe-rollout pattern: rows remain visible until the application sets awap.tenant_id
-- on the database session. Once the app propagates the tenant context, the fallback
-- branch can be removed in a later migration.

ALTER TABLE tenants ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenants FORCE ROW LEVEL SECURITY;
ALTER TABLE users ENABLE ROW LEVEL SECURITY;
ALTER TABLE users FORCE ROW LEVEL SECURITY;
ALTER TABLE refresh_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE refresh_tokens FORCE ROW LEVEL SECURITY;
ALTER TABLE workflow_definitions ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_definitions FORCE ROW LEVEL SECURITY;
ALTER TABLE workflow_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE tasks ENABLE ROW LEVEL SECURITY;
ALTER TABLE tasks FORCE ROW LEVEL SECURITY;
ALTER TABLE form_definitions ENABLE ROW LEVEL SECURITY;
ALTER TABLE form_definitions FORCE ROW LEVEL SECURITY;
ALTER TABLE form_submissions ENABLE ROW LEVEL SECURITY;
ALTER TABLE form_submissions FORCE ROW LEVEL SECURITY;
ALTER TABLE workflow_instances ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_instances FORCE ROW LEVEL SECURITY;
ALTER TABLE execution_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE execution_logs FORCE ROW LEVEL SECURITY;
ALTER TABLE companies ENABLE ROW LEVEL SECURITY;
ALTER TABLE companies FORCE ROW LEVEL SECURITY;
ALTER TABLE departments ENABLE ROW LEVEL SECURITY;
ALTER TABLE departments FORCE ROW LEVEL SECURITY;
ALTER TABLE teams ENABLE ROW LEVEL SECURITY;
ALTER TABLE teams FORCE ROW LEVEL SECURITY;
ALTER TABLE positions ENABLE ROW LEVEL SECURITY;
ALTER TABLE positions FORCE ROW LEVEL SECURITY;
ALTER TABLE organization_users ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_users FORCE ROW LEVEL SECURITY;

CREATE POLICY tenants_isolation ON tenants
    USING (current_setting('awap.tenant_id', true) IS NULL
        OR id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY users_isolation ON users
    USING (current_setting('awap.tenant_id', true) IS NULL
        OR tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY refresh_tokens_isolation ON refresh_tokens
    USING (current_setting('awap.tenant_id', true) IS NULL
        OR tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY workflow_definitions_isolation ON workflow_definitions
    USING (current_setting('awap.tenant_id', true) IS NULL
        OR tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY workflow_versions_isolation ON workflow_versions
    USING (current_setting('awap.tenant_id', true) IS NULL
        OR tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY tasks_isolation ON tasks
    USING (current_setting('awap.tenant_id', true) IS NULL
        OR tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY form_definitions_isolation ON form_definitions
    USING (current_setting('awap.tenant_id', true) IS NULL
        OR tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY form_submissions_isolation ON form_submissions
    USING (current_setting('awap.tenant_id', true) IS NULL
        OR tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY workflow_instances_isolation ON workflow_instances
    USING (current_setting('awap.tenant_id', true) IS NULL
        OR tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY execution_logs_isolation ON execution_logs
    USING (current_setting('awap.tenant_id', true) IS NULL
        OR tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY companies_isolation ON companies
    USING (current_setting('awap.tenant_id', true) IS NULL
        OR tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY departments_isolation ON departments
    USING (current_setting('awap.tenant_id', true) IS NULL
        OR tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY teams_isolation ON teams
    USING (current_setting('awap.tenant_id', true) IS NULL
        OR tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY positions_isolation ON positions
    USING (current_setting('awap.tenant_id', true) IS NULL
        OR tenant_id = current_setting('awap.tenant_id', true)::uuid);
CREATE POLICY organization_users_isolation ON organization_users
    USING (current_setting('awap.tenant_id', true) IS NULL
        OR tenant_id = current_setting('awap.tenant_id', true)::uuid);
