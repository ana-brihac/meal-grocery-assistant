-- Seed the default single-tenant user (id = 1).
--
-- This app is effectively single-user: endpoints that take a userId expect 1
-- (see docs/architecture.md), and nutrition_log.user_id has a FK to users(id).
-- Without this row, the documented `POST /api/nutrition/log {"userId": 1, ...}`
-- call fails with a foreign-key violation on a fresh database. Mirrors how
-- 004_user_preference.sql seeds its default id = 1 row.
--
-- Applied only on first container start (fresh volume). To add it to an existing
-- volume, run this file by hand — see docs/setup.md.

INSERT INTO users (id, username, email)
VALUES (1, 'default', 'default@example.com')
ON CONFLICT (id) DO NOTHING;
