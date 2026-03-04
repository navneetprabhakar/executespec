-- V5__seed_admin_user.sql
-- Seeds the default super admin account.
-- Password is bcrypt(strength=12) of 'admin'.
-- must_change_password = true forces a password change on first login.

INSERT INTO app_user (
    id,
    email,
    first_name,
    last_name,
    password_hash,
    global_role,
    tier,
    status,
    must_change_password,
    email_verified,
    org_id,
    created_at,
    updated_at
) VALUES (
    'admin-00000000-0000-0000-0000-000000000001',
    'admin@executespec.ai',
    'Super',
    'Admin',
    '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TdxAlt5FwvZSNHJsQi79t1R1K0Cm',
    'SUPER_ADMIN',
    'ENTERPRISE',
    'ACTIVE',
    true,
    true,
    null,
    NOW(),
    NOW()
) ON CONFLICT (email) DO NOTHING;
