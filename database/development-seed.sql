-- Run after Flyway migrations, from the MySQL CLI using an authorized administrator.
-- Set @seed_username and @seed_bcrypt in that SAME session before SOURCE-ing this file.
-- Generate the hash with org.smarttrack.jdbc.SeedPasswordHash, never a plaintext value.
-- Unset or malformed values become NULL and fail the table's NOT NULL constraint.
SET @seed_username = NULLIF(TRIM(@seed_username), '');
SET @seed_bcrypt = IF(@seed_bcrypt REGEXP '^[$]2[aby][$][0-9]{2}[$][./A-Za-z0-9]{53}$', @seed_bcrypt, NULL);
INSERT INTO users(username,password_hash) VALUES (@seed_username,@seed_bcrypt)
ON DUPLICATE KEY UPDATE username=users.username;
SET @seed_bcrypt = NULL;
SELECT id, username FROM users WHERE username=@seed_username;
-- Existing passwords are never replaced. Startup's environment-driven seed is an alternative.
