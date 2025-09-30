ALTER TABLE category
    ADD COLUMN IF NOT EXISTS subcategories jsonb NOT NULL DEFAULT '[]'::jsonb;

UPDATE category
SET subcategories = COALESCE(subcategories, '[]'::jsonb);
