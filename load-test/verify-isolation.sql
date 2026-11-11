-- Postgres-level tenant isolation assertion for the 1000+ tenant load test (issue #201).
--
-- Run as the non-superuser runtime role so RLS is actually enforced; a superuser would bypass it
-- and turn this check into a false negative.
--
--   docker compose -f infra/docker-compose.yml exec -T postgres \
--     psql -v ON_ERROR_STOP=1 -U awap_app -d awap \
--       -v tenant_id=<tenant-a> -v foreign_id=<tenant-b> -f /dev/stdin \
--       < load-test/verify-isolation.sql
--
-- psql does not substitute variables inside dollar-quoted bodies, so both identifiers are first
-- stored in session settings and read back with current_setting() inside the assertions.

DO $$
BEGIN
  IF (SELECT rolsuper FROM pg_roles WHERE rolname = current_user) THEN
    RAISE EXCEPTION
      'tenant isolation must be asserted as the non-superuser runtime role, current user is superuser';
  END IF;
END
$$;

SET awap.tenant_id = :'tenant_id';
SET awap.foreign_tenant_id = :'foreign_id';

DO $$
DECLARE
  leaked INTEGER;
  own_tenant UUID := current_setting('awap.tenant_id')::uuid;
  foreign_tenant UUID := current_setting('awap.foreign_tenant_id')::uuid;
BEGIN
  SELECT count(*) INTO leaked FROM workflow_instances WHERE tenant_id <> own_tenant;
  IF leaked > 0 THEN
    RAISE EXCEPTION 'RLS breach: % workflow_instances from another tenant are visible', leaked;
  END IF;

  SELECT count(*) INTO leaked FROM tasks WHERE tenant_id <> own_tenant;
  IF leaked > 0 THEN
    RAISE EXCEPTION 'RLS breach: % tasks from another tenant are visible', leaked;
  END IF;

  SELECT count(*) INTO leaked FROM users WHERE tenant_id <> own_tenant;
  IF leaked > 0 THEN
    RAISE EXCEPTION 'RLS breach: % users from another tenant are visible', leaked;
  END IF;

  -- Tenant B's row must stay invisible; a count of 1 means direct-id access bypassed RLS.
  SELECT count(*) INTO leaked FROM tenants WHERE id = foreign_tenant;
  IF leaked > 0 THEN
    RAISE EXCEPTION 'RLS breach: tenant % is visible from tenant %', foreign_tenant, own_tenant;
  END IF;
END
$$;

SELECT 'tenant isolation assertions passed' AS result;
