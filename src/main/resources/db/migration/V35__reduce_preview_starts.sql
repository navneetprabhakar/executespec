-- Reduce per-run preview start limits
UPDATE tier_config SET max_preview_starts = 2  WHERE tier = 'FREE';
UPDATE tier_config SET max_preview_starts = 5  WHERE tier = 'PRO';
-- ENTERPRISE stays at -1 (unlimited)
