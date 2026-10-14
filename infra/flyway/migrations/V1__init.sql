-- Baseline migration for the AWAP platform.
-- Phase 0 services will add their own versioned migrations on top of this.

CREATE TABLE IF NOT EXISTS awap_baseline (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
