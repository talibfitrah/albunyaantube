CREATE TABLE admin_audit_entry (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    action VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(128) NOT NULL,
    resource_slug VARCHAR(128),
    actor_id UUID,
    actor_email TEXT,
    actor_display_name TEXT,
    actor_status VARCHAR(32),
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    trace_id TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_admin_audit_entry_created ON admin_audit_entry(created_at DESC, id DESC);
CREATE INDEX idx_admin_audit_entry_actor ON admin_audit_entry(actor_id);

CREATE TRIGGER trg_admin_audit_entry_updated
    BEFORE UPDATE ON admin_audit_entry
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TABLE admin_audit_entry_actor_roles (
    audit_entry_id UUID NOT NULL REFERENCES admin_audit_entry(id) ON DELETE CASCADE,
    role TEXT NOT NULL,
    PRIMARY KEY (audit_entry_id, role)
);
