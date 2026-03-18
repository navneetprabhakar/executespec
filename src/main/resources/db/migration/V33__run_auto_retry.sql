ALTER TABLE tier_config ADD COLUMN max_run_auto_retries INT NOT NULL DEFAULT 0;
UPDATE tier_config SET max_run_auto_retries = 0 WHERE tier = 'FREE';
UPDATE tier_config SET max_run_auto_retries = 2 WHERE tier = 'PRO';
UPDATE tier_config SET max_run_auto_retries = 3 WHERE tier = 'ENTERPRISE';

ALTER TABLE pipeline_run ADD COLUMN run_retry_count INT NOT NULL DEFAULT 0;
