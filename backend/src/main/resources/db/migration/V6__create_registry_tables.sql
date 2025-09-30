CREATE TABLE channel_registry (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    yt_channel_id VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE channel_category (
    channel_id UUID NOT NULL REFERENCES channel_registry(id) ON DELETE CASCADE,
    category_id UUID NOT NULL REFERENCES category(id) ON DELETE CASCADE,
    PRIMARY KEY (channel_id, category_id)
);

CREATE TABLE channel_excluded_video (
    channel_id UUID NOT NULL REFERENCES channel_registry(id) ON DELETE CASCADE,
    yt_video_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (channel_id, yt_video_id)
);

CREATE TABLE channel_excluded_playlist (
    channel_id UUID NOT NULL REFERENCES channel_registry(id) ON DELETE CASCADE,
    yt_playlist_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (channel_id, yt_playlist_id)
);

CREATE TABLE playlist_registry (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    channel_id UUID NOT NULL REFERENCES channel_registry(id) ON DELETE CASCADE,
    yt_playlist_id VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE playlist_category (
    playlist_id UUID NOT NULL REFERENCES playlist_registry(id) ON DELETE CASCADE,
    category_id UUID NOT NULL REFERENCES category(id) ON DELETE CASCADE,
    PRIMARY KEY (playlist_id, category_id)
);

CREATE TABLE playlist_excluded_video (
    playlist_id UUID NOT NULL REFERENCES playlist_registry(id) ON DELETE CASCADE,
    yt_video_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (playlist_id, yt_video_id)
);

CREATE TABLE video_registry (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    channel_id UUID NOT NULL REFERENCES channel_registry(id) ON DELETE CASCADE,
    playlist_id UUID REFERENCES playlist_registry(id) ON DELETE SET NULL,
    yt_video_id VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE video_category (
    video_id UUID NOT NULL REFERENCES video_registry(id) ON DELETE CASCADE,
    category_id UUID NOT NULL REFERENCES category(id) ON DELETE CASCADE,
    PRIMARY KEY (video_id, category_id)
);

CREATE INDEX idx_channel_registry_created_at ON channel_registry(created_at DESC, id DESC);
CREATE INDEX idx_playlist_registry_created_at ON playlist_registry(created_at DESC, id DESC);
CREATE INDEX idx_video_registry_created_at ON video_registry(created_at DESC, id DESC);

CREATE TRIGGER trg_channel_registry_updated
    BEFORE UPDATE ON channel_registry
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_playlist_registry_updated
    BEFORE UPDATE ON playlist_registry
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_video_registry_updated
    BEFORE UPDATE ON video_registry
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();
