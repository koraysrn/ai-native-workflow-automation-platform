-- V47: Tenant-managed notification templates (issue #127).
--
-- notification_templates holds the current revision per (tenant, key, locale);
-- notification_template_versions keeps every saved revision for audit. The
-- version column doubles as the optimistic locking token, so a stale edit is
-- rejected with 409 instead of silently overwriting another editor.

CREATE TABLE notification_templates (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    template_key VARCHAR(128) NOT NULL,
    locale VARCHAR(16) NOT NULL,
    subject VARCHAR(512) NOT NULL,
    body TEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by UUID,
    updated_by UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_notification_templates UNIQUE (tenant_id, template_key, locale),
    CONSTRAINT ck_notification_templates_body_bytes CHECK (octet_length(body) <= 1048576),
    CONSTRAINT ck_notification_templates_key CHECK (template_key ~ '^[a-z0-9][a-z0-9._-]*$'),
    CONSTRAINT ck_notification_templates_locale CHECK (locale ~ '^[a-z]{2}(-[A-Z]{2})?$')
);

CREATE INDEX idx_notification_templates_tenant_key
    ON notification_templates (tenant_id, template_key, active);

CREATE TABLE notification_template_versions (
    id UUID PRIMARY KEY,
    template_id UUID NOT NULL REFERENCES notification_templates (id),
    tenant_id UUID NOT NULL,
    version BIGINT NOT NULL,
    subject VARCHAR(512) NOT NULL,
    body TEXT NOT NULL,
    active BOOLEAN NOT NULL,
    created_by UUID,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_notification_template_versions UNIQUE (template_id, version)
);

CREATE INDEX idx_notification_template_versions_template
    ON notification_template_versions (template_id, version DESC);

ALTER TABLE notification_templates ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_templates FORCE ROW LEVEL SECURITY;
CREATE POLICY notification_templates_isolation ON notification_templates
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);

ALTER TABLE notification_template_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_template_versions FORCE ROW LEVEL SECURITY;
CREATE POLICY notification_template_versions_isolation ON notification_template_versions
    USING (tenant_id = current_setting('awap.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('awap.tenant_id', true)::uuid);

-- Seed the built-in EN/TR templates for tenants that already exist. New tenants get the same
-- rows lazily through the code fallbacks in NotificationTemplateRenderer, which stay authoritative
-- when a row is missing or archived.
INSERT INTO notification_templates
    (id, tenant_id, template_key, locale, subject, body, version, active, created_at, updated_at)
SELECT gen_random_uuid(), t.id, seed.template_key, seed.locale, seed.subject, seed.body,
       0, TRUE, now(), now()
FROM tenants t
CROSS JOIN (VALUES
    ('welcome', 'en', 'Welcome to AWAP', 'Hello {{name}}, welcome to AWAP.'),
    ('welcome', 'tr', 'AWAP''a hoş geldiniz', 'Merhaba {{name}}, AWAP''a hoş geldiniz.'),
    ('task-assigned', 'en', 'New task', 'You have a new task: {{title}}.'),
    ('task-assigned', 'tr', 'Yeni görev', 'Yeni bir göreviniz var: {{title}}.'),
    ('approval-requested', 'en', 'Approval requested', 'Approval requested for {{title}}.'),
    ('approval-requested', 'tr', 'Onay talebi', '{{title}} için onay talep edildi.'),
    ('workflow-completed', 'en', 'Workflow completed', 'Workflow {{name}} completed.'),
    ('workflow-completed', 'tr', 'Süreç tamamlandı', '{{name}} süreci tamamlandı.'),
    ('notification', 'en', 'Notification', '{{message}}'),
    ('notification', 'tr', 'Bildirim', '{{message}}')
) AS seed(template_key, locale, subject, body)
WHERE t.deleted = FALSE
ON CONFLICT ON CONSTRAINT uq_notification_templates DO NOTHING;
