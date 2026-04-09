-- ============================================================
-- V63: Topup Package Restructure
-- Split INR/USD into separate packages (like billing plans).
-- Deactivate old 3 packages, insert 6 new ones.
-- ============================================================

-- Deactivate old packages
UPDATE topup_package SET active = FALSE WHERE active = TRUE;

-- Insert new INR packages
INSERT INTO topup_package (name, run_count, amount_inr, amount_usd, sort_order) VALUES
    ('Top-Up 5 Runs INR',  5,  84900, 0, 1),
    ('Top-Up 10 Runs INR', 10, 169900, 0, 2),
    ('Top-Up 20 Runs INR', 20, 339900, 0, 3);

-- Insert new USD packages
INSERT INTO topup_package (name, run_count, amount_inr, amount_usd, sort_order) VALUES
    ('Top-Up 5 Runs USD',  5,  0, 1000, 4),
    ('Top-Up 10 Runs USD', 10, 0, 2000, 5),
    ('Top-Up 20 Runs USD', 20, 0, 4000, 6);
