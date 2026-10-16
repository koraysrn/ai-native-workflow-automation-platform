-- Form Service schema (Phase 0).

CREATE TABLE form_definitions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    schema_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted BOOLEAN NOT NULL
);

CREATE TABLE form_submissions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    form_id UUID NOT NULL,
    workflow_instance_id UUID,
    data_json TEXT NOT NULL,
    submitted_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_form_definitions_tenant ON form_definitions(tenant_id);
CREATE INDEX idx_form_submissions_form ON form_submissions(form_id);
