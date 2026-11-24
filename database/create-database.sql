-- Execute as a MySQL administrator. Provision a dedicated local account separately.
CREATE DATABASE IF NOT EXISTS smarttrack CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
-- Flyway applies ../smarttrack-backend/src/main/resources/db/migration/V1__schema.sql.
-- Never grant the application global privileges or connect ESP32/Android to MySQL.
