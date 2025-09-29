INSERT INTO category (id, slug, name)
VALUES
    (
        uuid_generate_v4(),
        'quran',
        '{"en": "Quran", "ar": "القرآن", "nl": "Koran"}'
    ),
    (
        uuid_generate_v4(),
        'seerah',
        '{"en": "Seerah", "ar": "السيرة", "nl": "Sīra"}'
    ),
    (
        uuid_generate_v4(),
        'kids',
        '{"en": "Kids", "ar": "أطفال", "nl": "Kinderen"}'
    ),
    (
        uuid_generate_v4(),
        'lectures',
        '{"en": "Lectures", "ar": "محاضرات", "nl": "Lezingen"}'
    )
ON CONFLICT (slug) DO NOTHING;
