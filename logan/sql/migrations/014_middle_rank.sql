-- Apply the starting rank once to existing profiles, without touching equipment or GP.
CREATE TABLE IF NOT EXISTS local_migrations (name VARCHAR(100) PRIMARY KEY);
START TRANSACTION;
UPDATE users SET level = 31 WHERE NOT EXISTS (SELECT 1 FROM local_migrations WHERE name = '014_middle_rank');
INSERT IGNORE INTO local_migrations (name) VALUES ('014_middle_rank');
COMMIT;
ALTER TABLE users ALTER COLUMN level SET DEFAULT 31;
