-- Setup wizard after the first sign-in (company profile in a few steps). Finished or skipped = timestamp set.
-- Accounts that existed before the wizard are treated as set up.
ALTER TABLE users ADD COLUMN setup_completed_at timestamptz;
UPDATE users SET setup_completed_at = now();
