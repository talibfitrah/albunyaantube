ALTER TABLE category
    ADD COLUMN IF NOT EXISTS description JSONB NOT NULL DEFAULT '{}'::jsonb;
