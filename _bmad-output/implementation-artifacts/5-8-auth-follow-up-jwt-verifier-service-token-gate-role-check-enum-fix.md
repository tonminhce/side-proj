---
baseline_commit: 0dd26a7
---

# Story 5.8 (follow-up): AuthService hardening — JwtVerifier + service-token gate + role CHECK + email-enumeration fix

Status: review

Closes Epic 5 carry-forward HIGH items 1, 2, 5 from `_bmad-output/backlog/deferred-issues.md`.

## Story

As the auth service,
I want JwtVerifier (HMAC + iss + exp checks) so consumers can validate tokens without trusting HMAC_JWT_SECRET directly,
And I want POST /api/auth/service-token gated by X-Internal-Token so anonymous callers cannot mint service-account JWTs,
And I want users.role CHECK constraint + drop default so direct-SQL writers cannot insert invalid roles,
And I want login + register to return identical bodies for "no such user / wrong password / duplicate email / locked" so callers cannot enumerate registered emails.

## Acceptance Criteria

1. **JwtVerifier bean** — `services/auth/.../infrastructure/security/JwtVerifier.java` parses JWT (header.payload.signature), verifies HMAC via util's `HmacEventSigner.verify(...)`, checks `iss=auth`, checks `exp > Instant.now(clock)`. Throws `IllegalStateException` for signature/issuer/exp failures, `IllegalArgumentException` for malformed. Test: 5 cases (valid, bad-signature, expired, wrong-issuer, malformed) — all green.
2. **/api/auth/service-token gate** — `InternalTokenAuthFilter` registered via `FilterRegistrationBean` at `Ordered.HIGHEST_PRECEDENCE` (runs before Spring Security) on `/api/auth/service-token`. Reads expected token from `AUTH_INTERNAL_TOKEN` env var → `auth.internal-token` config → dev-profile fallback `dev-internal-token-do-not-use-in-prod` → fails loud on missing in non-dev profiles (ADR-20 parity). Constant-time comparison. Without header → 401 `{"error":"unauthorized"}`. With correct header → request passes through. Tests: 6 cases (missing, wrong, correct, other-path passthrough, configured-token precedence, prod-no-token-fail-loud) — all green.
3. **users.role CHECK + drop default** — V002 migration adds `CHECK (role IN ('USER','STAFF','ADMIN'))` and drops the lowercase `'user'` default. Belt-and-braces with `@Enumerated(EnumType.STRING)`.
4. **Email enumeration fix** — `AuthController.register` always returns 200 with `{"status":"registration_submitted","message":"If this email is new, a verification link has been sent."}` regardless of duplicate. `AuthController.login` collapses unknown-email, bad-password, and account-locked into identical 401 `{"error":"invalid_credentials"}`. Login account-locked is still recorded via Micrometer `security.account.locked` counter for ops visibility. Tests: 5 cases (register-duplicate, register-new, login-unknown, login-locked-collapsed, login-success) — all green.
5. **AuthSecurityConfig narrow permitAll** — public chain: `/api/auth/register`, `/api/auth/login`, `/actuator/**`, `/error`. Default chain: denyAll. InternalTokenAuthFilter handles `/api/auth/service-token` upstream.
6. **All 28 auth tests green** (was 12, +16: 5 verifier + 5 controller + 6 filter).
7. **Runtime smoke** — `services/auth` boots cleanly in dev profile; `POST /api/auth/register` returns 200 + generic body; `POST /api/auth/login` returns 200 on valid creds + 401 (same body) on unknown/bad-pwd/locked; `POST /api/auth/service-token` without `X-Internal-Token` returns 401; with correct dev token returns 200 + JWT.

## Out of scope (deferred)

- `PasswordHasher` → util/ migration (architecture story)
- mTLS replacement for X-Internal-Token header check (service-mesh story)
- Customer service eventual password flow (depends on snapshot.user_id migration)
- Vault-backed `HmacServiceKeyProvider` for service-token signing (Story 5.4 follow-up #2 in deferred log)

## Files changed

**New:**
- `services/auth/src/main/java/vn/vnpt/auth/infrastructure/security/JwtVerifier.java`
- `services/auth/src/main/java/vn/vnpt/auth/infrastructure/web/InternalTokenAuthFilter.java`
- `services/auth/src/main/resources/db/migration/auth/V002__users_role_check_and_drop_default.sql`
- `services/auth/src/test/java/vn/vnpt/auth/infrastructure/security/JwtVerifierTest.java`
- `services/auth/src/test/java/vn/vnpt/auth/application/web/AuthControllerTest.java`
- `services/auth/src/test/java/vn/vnpt/auth/infrastructure/web/InternalTokenAuthFilterTest.java`

**Modified:**
- `services/auth/src/main/java/vn/vnpt/auth/infrastructure/security/JwtIssuer.java` (+1 test-seam ctor)
- `services/auth/src/main/java/vn/vnpt/auth/infrastructure/web/AuthSecurityConfig.java` (narrowed permitAll + FilterRegistrationBean)
- `services/auth/src/main/java/vn/vnpt/auth/application/web/AuthController.java` (register/login bodies collapsed)

## ponytail notes

- InternalTokenAuthFilter uses header-token check, not mTLS — `ponytail: upgrade to mTLS or Vault short-lived tokens when service-mesh sidecars land.`
- JwtVerifier `Map.class` deserialise triggers an unchecked warning (Jackson-ergonomics); narrowing to `Map<String,Object>` requires `@SuppressWarnings` — acceptable for now.
- JwtVerifier dev profile uses a separate marker secret (issuer's random + verifier's marker are decoupled in dev); tests inject a shared secret via the test-seam constructor. Production uses `HMAC_JWT_SECRET` env var and both beans read the same value.

## Deferred follow-ups (already in deferred-issues.md, still open)

- PasswordHasher → util/ migration
- Email-verification token issuance (the "verification link" message is generic — actual link generation is a future story)