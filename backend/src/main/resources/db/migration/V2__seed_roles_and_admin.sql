INSERT INTO role (id, code) VALUES
    (uuid_generate_v4(), 'ADMIN'),
    (uuid_generate_v4(), 'MODERATOR')
ON CONFLICT (code) DO NOTHING;

INSERT INTO app_user (id, email, password_hash, display_name, status)
VALUES (
    uuid_generate_v4(),
    'admin@albunyaan.tube',
    '$2a$10$H/dlF3YV6nOjZDFWYdwFbe3ENcy9XUG6u.ZYIY7M1bRnwZ0w.zb1S', -- password: ChangeMe!123
    'Initial Admin',
    'ACTIVE'
)
ON CONFLICT (email) DO NOTHING;

WITH admin_role AS (
    SELECT r.id AS role_id, u.id AS user_id
    FROM role r
    CROSS JOIN app_user u
    WHERE r.code = 'ADMIN' AND u.email = 'admin@albunyaan.tube'
)
INSERT INTO user_roles (user_id, role_id)
SELECT user_id, role_id FROM admin_role
ON CONFLICT DO NOTHING;
