WITH channels AS (
    SELECT 'UC9jKNK9E8bZMXsFQ0iCFJMw'::varchar AS yt_id
    UNION ALL SELECT 'UC3o5hQk_lJ0Y9rJ1Z2J6Rmg'
    UNION ALL SELECT 'UCx4fVJQ4J5BVQxZsR3aohKg'
    UNION ALL SELECT 'UC1l1FirdawszzU8uY2'
    UNION ALL SELECT 'UC5alHikmah837Lmqg'
)
INSERT INTO channel_registry (yt_channel_id)
SELECT yt_id
FROM channels
ON CONFLICT (yt_channel_id) DO NOTHING;

INSERT INTO channel_category (channel_id, category_id)
SELECT c.id, cat.id
FROM channel_registry c
JOIN category cat ON cat.slug = 'lectures'
WHERE c.yt_channel_id IN ('UC9jKNK9E8bZMXsFQ0iCFJMw', 'UC3o5hQk_lJ0Y9rJ1Z2J6Rmg', 'UC1l1FirdawszzU8uY2')
ON CONFLICT DO NOTHING;

INSERT INTO channel_category (channel_id, category_id)
SELECT c.id, cat.id
FROM channel_registry c
JOIN category cat ON cat.slug = 'quran'
WHERE c.yt_channel_id IN ('UC9jKNK9E8bZMXsFQ0iCFJMw', 'UC3o5hQk_lJ0Y9rJ1Z2J6Rmg', 'UC5alHikmah837Lmqg')
ON CONFLICT DO NOTHING;

INSERT INTO channel_category (channel_id, category_id)
SELECT c.id, cat.id
FROM channel_registry c
JOIN category cat ON cat.slug = 'kids'
WHERE c.yt_channel_id = 'UCx4fVJQ4J5BVQxZsR3aohKg'
ON CONFLICT DO NOTHING;

INSERT INTO channel_category (channel_id, category_id)
SELECT c.id, cat.id
FROM channel_registry c
JOIN category cat ON cat.slug = 'seerah'
WHERE c.yt_channel_id IN ('UC1l1FirdawszzU8uY2')
ON CONFLICT DO NOTHING;

WITH playlist_data AS (
    SELECT 'PLyaqeen001'::varchar AS yt_id, 'UC9jKNK9E8bZMXsFQ0iCFJMw'::varchar AS channel
    UNION ALL SELECT 'PLbayyinah001', 'UC3o5hQk_lJ0Y9rJ1Z2J6Rmg'
    UNION ALL SELECT 'PLmiracleKids001', 'UCx4fVJQ4J5BVQxZsR3aohKg'
    UNION ALL SELECT 'PLfirdaws001', 'UC1l1FirdawszzU8uY2'
    UNION ALL SELECT 'PLhikmah001', 'UC5alHikmah837Lmqg'
)
INSERT INTO playlist_registry (yt_playlist_id, channel_id)
SELECT p.yt_id, c.id
FROM playlist_data p
JOIN channel_registry c ON c.yt_channel_id = p.channel
ON CONFLICT (yt_playlist_id) DO NOTHING;

INSERT INTO playlist_category (playlist_id, category_id)
SELECT p.id, cat.id
FROM playlist_registry p
JOIN channel_registry c ON p.channel_id = c.id
JOIN category cat ON cat.slug = 'lectures'
WHERE p.yt_playlist_id IN ('PLyaqeen001', 'PLbayyinah001', 'PLfirdaws001')
ON CONFLICT DO NOTHING;

INSERT INTO playlist_category (playlist_id, category_id)
SELECT p.id, cat.id
FROM playlist_registry p
JOIN category cat ON cat.slug = 'quran'
WHERE p.yt_playlist_id IN ('PLbayyinah001', 'PLhikmah001')
ON CONFLICT DO NOTHING;

INSERT INTO playlist_category (playlist_id, category_id)
SELECT p.id, cat.id
FROM playlist_registry p
JOIN category cat ON cat.slug = 'kids'
WHERE p.yt_playlist_id = 'PLmiracleKids001'
ON CONFLICT DO NOTHING;

WITH video_data AS (
    SELECT 'yaqeen01'::varchar AS yt_id, 'UC9jKNK9E8bZMXsFQ0iCFJMw'::varchar AS channel, 'PLyaqeen001'::varchar AS playlist
    UNION ALL SELECT 'bayyina01', 'UC3o5hQk_lJ0Y9rJ1Z2J6Rmg', 'PLbayyinah001'
    UNION ALL SELECT 'miracle01', 'UCx4fVJQ4J5BVQxZsR3aohKg', 'PLmiracleKids001'
    UNION ALL SELECT 'firdaws01', 'UC1l1FirdawszzU8uY2', 'PLfirdaws001'
    UNION ALL SELECT 'hikmah01', 'UC5alHikmah837Lmqg', 'PLhikmah001'
)
INSERT INTO video_registry (yt_video_id, channel_id, playlist_id)
SELECT v.yt_id, c.id, p.id
FROM video_data v
JOIN channel_registry c ON c.yt_channel_id = v.channel
LEFT JOIN playlist_registry p ON p.yt_playlist_id = v.playlist
ON CONFLICT (yt_video_id) DO NOTHING;

INSERT INTO video_category (video_id, category_id)
SELECT v.id, cat.id
FROM video_registry v
JOIN category cat ON cat.slug = 'lectures'
WHERE v.yt_video_id IN ('yaqeen01', 'bayyina01', 'firdaws01')
ON CONFLICT DO NOTHING;

INSERT INTO video_category (video_id, category_id)
SELECT v.id, cat.id
FROM video_registry v
JOIN category cat ON cat.slug = 'quran'
WHERE v.yt_video_id IN ('bayyina01', 'hikmah01')
ON CONFLICT DO NOTHING;

INSERT INTO video_category (video_id, category_id)
SELECT v.id, cat.id
FROM video_registry v
JOIN category cat ON cat.slug = 'kids'
WHERE v.yt_video_id = 'miracle01'
ON CONFLICT DO NOTHING;

INSERT INTO channel_excluded_video (channel_id, yt_video_id)
SELECT c.id, 'miracle01'
FROM channel_registry c
WHERE c.yt_channel_id = 'UCx4fVJQ4J5BVQxZsR3aohKg'
ON CONFLICT DO NOTHING;

INSERT INTO playlist_excluded_video (playlist_id, yt_video_id)
SELECT p.id, 'miracle01'
FROM playlist_registry p
WHERE p.yt_playlist_id = 'PLmiracleKids001'
ON CONFLICT DO NOTHING;
