-- Form schema versioning: add version column.

ALTER TABLE form_definitions ADD COLUMN version INTEGER NOT NULL DEFAULT 1;
