-- ── V57: Add display_name to tier_config for consistent UI labels ──────

ALTER TABLE tier_config ADD COLUMN display_name VARCHAR(50);

UPDATE tier_config SET display_name = 'Free'       WHERE tier = 'FREE';
UPDATE tier_config SET display_name = 'Pro'        WHERE tier = 'PRO';
UPDATE tier_config SET display_name = 'Enterprise' WHERE tier = 'ENTERPRISE';

ALTER TABLE tier_config ALTER COLUMN display_name SET NOT NULL;
