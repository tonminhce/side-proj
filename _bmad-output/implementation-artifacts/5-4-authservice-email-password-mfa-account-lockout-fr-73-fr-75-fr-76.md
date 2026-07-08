---
baseline_commit: 6b6d952
---

# Story 5.4: AuthService — Email + password, MFA, account lockout (FR-73, FR-75, FR-76) — solves AT-02

Status: review

## Story

As the auth flow,
I want email + password primary auth with MFA TOTP for staff+admin,
And account lockout after N failed logins per credential-stuffing defense.

## Acceptance Criteria

1. **Given** FR-73 mandates "Email + password auth as the primary mechanism" (`prd.md:111`), **When** Story 5.4 lands, **Then** a new `services/auth/` Maven module is bootstrapped with: User aggregate (id, email, passwordHash, role, mfaEnrolled, mfaSecret, failedAttempts, lockedUntil, createdAt, lastLoginAt), `password_hash` column storing Argon2id (PBKDF2 fallback in v1; Argon2 native lib in Spring Boot 4 is experimental), and a `POST /api/auth/login` endpoint that returns HTTP 200 with `{userId, email, role, mfaRequired, sessionToken}` on success, or HTTP 401 with `{error:"invalid_credentials"}` on bad password. The `sessionToken` is a JWT signed with HS256 (per util's `HmacEventSigner` + a 32-byte dev secret loaded from `HMAC_JWT_SECRET` env var; production loads from Vault per ADR-20).

2. **Given** the User aggregate must be **separate from the Customer** (FR-45, `prd.md:104`), **When** Story 5.4 lands, **Then** the auth service owns the `auth_db` (per-service DB, ADR-03) and the User table has a 1:1 optional link to Customer via `customer_id BIGINT` (no FK at the DB level — the cross-service link is by convention). The `POST /api/auth/register` endpoint accepts `{email, password}` and creates a new User + (via cross-service event, in v1 just a returned `customerId` that the customer service uses) Customer. **MFA + customer-link events are deferred** — Story 5.4 ships the email/password foundation; MFA enrollment lands in a follow-up story.

3. **Given** AC #1 mandates account lockout after N failed logins (per `prd.md:114` "5 failed login attempts within 15 minutes"), **When** Story 5.4 lands, **Then** the `POST /api/auth/login` endpoint increments `failed_attempts` on each bad password; if `failed_attempts >= 5` within a 15-min rolling window, the user is locked (`lockedUntil = now + 1h`) and the response is HTTP 423 Locked with `{error:"account_locked","lockedUntil":"<ISO-8601>"}`. The endpoint emits a `security.account.locked` Micrometer counter on each lockout (the actual event to a Kafka topic lands with the Modulith outbox wiring in a follow-up story — the counter is the in-process observability seam).

4. **Given** FR-76 mandates CAPTCHA on suspicious burst, **When** Story 5.4 lands, **Then** the v1 stub is: a `captcha_required` boolean in the login response (computed by the rate-limiter integration from Story 3.4 — when a single IP makes >50 login attempts in 5 min, the response includes `captcha_required: true`). The actual CAPTCHA widget integration (e.g. reCAPTCHA, hCaptcha) is deferred — Story 5.4 ships the boolean + the rate-limiter hook; the widget lands with a future story. The smoke asserts that the field is present in the response (true OR false depending on the rate-limiter state).

5. **Given** the runtime smoke rule (project memory `runtime-smoke-rule.md`), **When** Story 5.4 completes, **Then** the dev agent runs `bash dev/scripts/smoke-auth-5-4.sh` which: (a) starts `services/auth`; (b) `POST /api/auth/register` with `{email, password}` — assert HTTP 201; (c) `POST /api/auth/login` with the registered credentials — assert HTTP 200 + `sessionToken` is non-empty; (d) `POST /api/auth/login` with a wrong password 5 times — assert HTTP 423 on the 5th attempt + `account_locked` error + `lockedUntil` timestamp; (e) `POST /api/auth/login` with the correct password — assert HTTP 423 (account still locked); (f) `psql -c "SELECT count(*) FROM users WHERE email = ..."` — assert 1 row; (g) kill the process, exit 0.

6. **Given** the per-service log redaction from Story 3.3 is wired via `util/.../logging/PanRedactingAppender`, **When** Story 5.4 logs a security alert, **Then** the alert message MUST NOT contain the password (or password hash) or the JWT secret. The log line carries: alert name + email (or userId) + the high-level reason. R-15 deny-list baseline.

### Out of scope (do NOT build — deferred, stated to prevent scope creep)

- **MFA TOTP enrollment** (TOTP secret generation + QR code + verify endpoint) → Story 5.4 follow-up. v1 ships email + password only; `mfaEnrolled=false` and `mfaRequired=false` for all roles.
- **Email verification flow** (send verification email + verify token endpoint) → Story 5.4 follow-up. v1 sets `emailVerified=false` on register; the verify endpoint is a follow-up.
- **Cross-service event** (auth emits `user.registered` → customer consumes + creates Customer row) → Modulith outbox follow-up. v1 returns `customerId=null` in the register response.
- **CAPTCHA widget integration** (reCAPTCHA, hCaptcha) → Future story. v1 returns the `captcha_required` boolean only.
- **Refresh tokens + access token TTL** → Future story. v1 JWT has a 1h expiry (HS256, configured via env var).
- **Password reset flow** (forgot-password email + reset token) → Future story.
- **Rate-limiter integration with Story 3.4's gateway** → The gateway's BIN-velocity filter is per-IP; Story 5.4 has its own per-user `failed_attempts` counter. Cross-service rate limiting is deferred.
- **Soft-delete for users** (FR-77 hint) → Out of scope.

## Tasks / Subtasks

- [ ] **Task 1 — Bootstrap `services/auth` Maven module** (AC: #1)
  - [ ] Modify `services/auth/pom.xml`: change packaging to `jar`; add deps (util, spring-boot-starter-web, spring-boot-starter-data-jpa, spring-boot-flyway, Postgres driver, spring-modulith-starter-core, spring-boot-starter-actuator, Lombok, Testcontainers).
  - [ ] Create `services/auth/src/main/java/vn/vnpt/auth/AuthApplication.java` with `@SpringBootApplication(scanBasePackages = "vn.vnpt.auth") @ApplicationModule(displayName = "auth")`.
  - [ ] Create `services/auth/src/main/resources/application.yml` (port 8089, auth_db datasource, logback-spring.xml).
  - [ ] Create `services/auth/src/main/resources/logback-spring.xml` (include util's logback-include.xml).

- [ ] **Task 2 — V001 Flyway migration: `users` table** (AC: #1, #2, #3)
  - [ ] `services/auth/src/main/resources/db/migration/auth/V001__create_users_table.sql` — `users` table with `id BIGSERIAL PK, email VARCHAR(255) UNIQUE NOT NULL, password_hash VARCHAR(255) NOT NULL, role VARCHAR(16) NOT NULL DEFAULT 'user', mfa_enrolled BOOLEAN NOT NULL DEFAULT false, mfa_secret VARCHAR(64), failed_attempts INT NOT NULL DEFAULT 0, locked_until TIMESTAMP, customer_id BIGINT, created_at TIMESTAMP NOT NULL DEFAULT now(), last_login_at TIMESTAMP, email_verified BOOLEAN NOT NULL DEFAULT false`.

- [ ] **Task 3 — JPA entity + repository** (AC: #1, #3)
  - [ ] `services/auth/.../infrastructure/entity/UserEntity.java`.
  - [ ] `services/auth/.../infrastructure/repository/UserRepository.java extends JpaRepository<UserEntity, Long>` with `Optional<UserEntity> findByEmail(String email)`.

- [ ] **Task 4 — Domain types + use cases** (AC: #1, #2, #3, #4)
  - [ ] `services/auth/.../domain/User.java` — record.
  - [ ] `services/auth/.../domain/Role.java` — enum `USER, STAFF, ADMIN`.
  - [ ] `services/auth/.../application/port/RegisterCommand.java` + `LoginCommand.java` (trust-boundary validated).
  - [ ] `services/auth/.../application/port/AuthResult.java` — record `(long userId, String email, Role role, boolean mfaRequired, String sessionToken, boolean captchaRequired, String error)`.
  - [ ] `services/auth/.../application/usecase/RegisterUseCase.java` — hashes password (PBKDF2 in v1 — `PBKDF2WithHmacSHA256`, 65536 iterations, 16-byte salt base64-encoded with the hash; `spring-security-crypto` dep).
  - [ ] `services/auth/.../application/usecase/LoginUseCase.java` — verifies password; checks `lockedUntil > now`; increments `failed_attempts` on bad password; locks account on 5th failure within 15 min; issues JWT (`util/.../events/HmacEventSigner` for the signature; HS256).
  - [ ] `services/auth/.../infrastructure/security/PasswordHasher.java` + `JwtIssuer.java` helpers (1-line, isolated for testability).

- [ ] **Task 5 — REST endpoints** (AC: #1, #3, #4)
  - [ ] `services/auth/.../application/web/AuthController.java` — `POST /api/auth/register` + `POST /api/auth/login`.
  - [ ] `services/auth/.../infrastructure/web/AuthSecurityConfig.java` — permits all (mirror Story 5.1 / 5.2 pattern).

- [ ] **Task 6 — Tests** (AC: #1, #3, #4)
  - [ ] `RegisterUseCaseTest` — 3 tests: `execute_persistsUserWithHashedPassword`, `execute_throwsOnNullEmail`, `execute_throwsOnShortPassword` (< 8 chars).
  - [ ] `LoginUseCaseTest` — 4 tests: `execute_returnsSessionTokenOnCorrectPassword`, `execute_incrementsFailedAttemptsOnBadPassword`, `execute_locksAccountAfter5FailedAttempts`, `execute_throwsAccountLockedWhenLockedUntilFuture`.
  - [ ] `PasswordHasherTest` — 2 tests: `hash_returnsDifferentOutputForSameInput` (salt randomness), `verify_returnsTrueForCorrectPassword`.

- [ ] **Task 7 — Runtime smoke script** (AC: #5)
  - [ ] `dev/scripts/smoke-auth-5-4.sh` — bash. Pattern mirrors `smoke-customer-5-3.sh`.

## Dev Notes

### Implementation Notes

- **`services/auth/` is freshly bootstrapped** (current `pom.xml` is a packaging-pom stub per `services/auth/pom.xml`). The dev agent must change packaging to `jar` + add all the dependencies per AC #1.
- **Password hashing: PBKDF2 with HMAC-SHA256** (v1 — `spring-security-crypto` is already a transitive dep via util's spring-boot-starter-security). 65536 iterations + 16-byte random salt + base64-encode `<iterations>:<saltB64>:<hashB64>`. Future story swaps to Argon2id when the native lib is stable on Spring Boot 4.
- **JWT issuance uses util's `HmacEventSigner` with a separate key** (`HMAC_JWT_SECRET` env var). The JWT payload is a `Map.of("sub", userId, "email", email, "role", role, "iat", now, "exp", now+1h)` serialized via JCS + signed. The token is base64url(header) + "." + base64url(payload) + "." + base64url(sig).
- **Account lockout window** is 15 min (rolling) for the `failed_attempts` counter. The `lockedUntil` timestamp is `now + 1h` on lockout. The smoke doesn't simulate time travel; it just hits the endpoint 5 times and asserts the 5th returns 423.
- **CAPTCHA hook** is a boolean the rate-limiter can set. v1 returns `captchaRequired: false` always (the rate-limiter is a future wiring).
- **Test counts target** — `≥ 9 new tests` (RegisterUseCase 3 + LoginUseCase 4 + PasswordHasher 2). Auth service baseline: 0; target: ≥ 9.

### Debug Log References

(none — fresh story; debugs land in Dev Notes / Completion Notes after the dev agent runs)

### Completion Notes List

(empty — populated by dev-story)

### Deviations from literal story text (flagged for review)

(none — fresh story; deviations land here after dev-story)

## Dev Notes (post-dev-story section — populated after implementation)

### Project Structure Notes

- **Path placement** (per architecture §6):
  - `services/auth/pom.xml` ← change to jar (Task 1)
  - `services/auth/src/main/java/vn/vnpt/auth/AuthApplication.java` ← new (Task 1)
  - `services/auth/src/main/resources/application.yml` + `logback-spring.xml` ← new (Task 1)
  - `services/auth/src/main/resources/db/migration/auth/V001__create_users_table.sql` ← new (Task 2)
  - `services/auth/src/main/java/vn/vnpt/auth/infrastructure/entity/UserEntity.java` + `repository/UserRepository.java` ← new (Task 3)
  - `services/auth/src/main/java/vn/vnpt/auth/domain/User.java` + `Role.java` ← new (Task 4)
  - `services/auth/src/main/java/vn/vnpt/auth/application/port/RegisterCommand.java` + `LoginCommand.java` + `AuthResult.java` ← new (Task 4)
  - `services/auth/src/main/java/vn/vnpt/auth/application/usecase/RegisterUseCase.java` + `LoginUseCase.java` ← new (Task 4)
  - `services/auth/src/main/java/vn/vnpt/auth/infrastructure/security/PasswordHasher.java` + `JwtIssuer.java` ← new (Task 4)
  - `services/auth/src/main/java/vn/vnpt/auth/application/web/AuthController.java` ← new (Task 5)
  - `services/auth/src/main/java/vn/vnpt/auth/infrastructure/web/AuthSecurityConfig.java` ← new (Task 5)
  - `services/auth/src/test/java/vn/vnpt/auth/...` ← new tests (Task 6)
  - `dev/scripts/smoke-auth-5-4.sh` ← new (Task 7)

- **Detected conflicts / variances (with rationale):**
  - **PBKDF2 instead of Argon2id** — Spring Boot 4's Argon2 native lib is experimental; PBKDF2 is the v1 contract; future story swaps to Argon2id.
  - **`customerId` is `BIGINT` without FK** — the auth service doesn't have visibility into `customer_db`; the link is by convention. Future story adds a migration with the FK.
  - **No Modulith outbox wiring** — the `security.account.locked` counter is the in-process observability seam; the cross-service event lands with the Modulith outbox follow-up.

### References

- [Source: `_bmad-output/planning-artifacts/epics.md:832-845` — Story 5.4 AC]
- [Source: `_bmad-output/planning-artifacts/prd.md:111-114` — FR-73, FR-75, FR-76]
- [Source: `_bmad-output/planning-artifacts/architecture.md:235` — Sprint 5: "Customer + Auth | customer, auth"]
- [Source: `services/customer/.../infrastructure/entity/CustomerEntity.java` — Story 5.1 entity (mirror for user shape; the cross-service `customerId` is the seam)
- [Source: `services/customer/.../infrastructure/web/CustomerSecurityConfig.java` — Spring Security config pattern (mirror for auth)
- [Source: `util/.../events/HmacEventSigner.java` — HMAC primitive for JWT signing (Story 3.5)
- [Source: project memory `runtime-smoke-rule.md` — every dev-story must runtime-smoke
- [Source: project memory `deep-review-rules.md` — F1: shared code in util/, 1-sentence javadoc, no single-impl abstractions

## Dev Agent Record

### Agent Model Used

{{agent_model_name_version}}

### Debug Log References

### Completion Notes List

### File List