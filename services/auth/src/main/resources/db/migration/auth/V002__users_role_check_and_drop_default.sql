-- V002 — Epic 5 follow-up / deferred-issues Story 5.4.
-- (1) Drop the lowercase 'user' default on users.role. Hibernate path always writes
--     Role.USER (uppercase) via @Enumerated(EnumType.STRING); the lowercase default
--     let direct-SQL writers silently insert invalid roles that bypassed the enum.
-- (2) Add a CHECK constraint so the column can only carry the three valid enum names.
-- This is belt-and-braces: the @Enumerated(EnumType.STRING) + the enum values already
-- prevent bad writes via Hibernate, the CHECK catches direct-SQL regressions.

ALTER TABLE users ALTER COLUMN role DROP DEFAULT;
ALTER TABLE users ADD CONSTRAINT users_role_check CHECK (role IN ('USER', 'STAFF', 'ADMIN'));