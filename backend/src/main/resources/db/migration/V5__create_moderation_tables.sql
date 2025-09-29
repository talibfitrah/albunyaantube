CREATE TABLE moderation_proposal (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    kind VARCHAR(32) NOT NULL,
    yt_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    notes TEXT,
    proposer_id UUID NOT NULL REFERENCES app_user(id),
    decided_by_id UUID REFERENCES app_user(id),
    decided_at TIMESTAMPTZ,
    decision_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_moderation_proposal_kind CHECK (kind IN ('CHANNEL', 'PLAYLIST', 'VIDEO')),
    CONSTRAINT chk_moderation_proposal_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'))
);

CREATE UNIQUE INDEX uq_moderation_proposal_kind_yt_id ON moderation_proposal(kind, yt_id);
CREATE INDEX idx_moderation_proposal_status_created ON moderation_proposal(status, created_at, id);

CREATE TABLE moderation_proposal_suggested_category (
    proposal_id UUID NOT NULL REFERENCES moderation_proposal(id) ON DELETE CASCADE,
    category_id UUID NOT NULL REFERENCES category(id),
    position INTEGER NOT NULL,
    PRIMARY KEY (proposal_id, position)
);

CREATE TRIGGER trg_moderation_proposal_updated
    BEFORE UPDATE ON moderation_proposal
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();
