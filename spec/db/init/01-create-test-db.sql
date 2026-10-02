-- Runs once, when the local Postgres volume is created. Integration tests use this database
-- (TEST_DATABASE_URL) so they can truncate tables without touching your dev data.
CREATE DATABASE shopflow_test OWNER shopflow;
