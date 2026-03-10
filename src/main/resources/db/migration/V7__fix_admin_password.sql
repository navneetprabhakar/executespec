-- V7__fix_admin_password.sql
-- The hash seeded in V5 was incorrect and did not match any known password.
-- This migration replaces it with a verified bcrypt(strength=12) hash of the default admin password.
-- must_change_password is set to false so the admin can log in immediately.
-- Change the password via Settings → Change Password after first login.

UPDATE app_user
SET password_hash       = '$2a$12$F2wHGqi4W7pN3aMNCcO3IuiIWCRJy82Fml4sosxbzsRLvECpcSsci',
    must_change_password = false,
    updated_at           = NOW()
WHERE email = 'admin@executespec.ai';
