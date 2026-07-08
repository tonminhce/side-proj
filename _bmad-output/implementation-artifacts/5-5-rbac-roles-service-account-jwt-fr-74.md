---
baseline_commit: 6b6d952
---

# Story 5.5: RBAC roles + service-account JWT (FR-74)

Status: review

## Story

As a downstream service,
I want to call another service with a service-account JWT carrying my caller identity,
So that audit logs trace the chain.

## Acceptance Criteria

1. **Given** Story 5.4 ships the auth service + JWT issuance with `role` in the JWT payload, **When** Story 5.5 lands, **Then** the auth service extends the JWT issuance to include `callerChain` and `serviceAccountId` fields (a "service-account" JWT — issued when the caller identifies as a service, not a user). The endpoint `POST /api/auth/service-token` accepts `{serviceAccountId, allowedRoles, callerChain}` and returns a JWT with those fields. v1 ships a single role value (the highest allowed); multi-role JWTs are a future story.

2. **Given** FR-74 mandates "JWT carries my service-account ID, the calling chain's trace, and the callee's allowed roles" (`prd.md:120` + the AC verbatim), **When** Story 5.5 lands, **Then** the JWT payload has the shape: `{sub, role, iat, exp, serviceAccountId, callerChain, allowedRoles}`. The `callerChain` is a list of strings (e.g., `["checkout", "payment"]`) — the call trace for audit. The `allowedRoles` is a list of strings (e.g., `["USER", "STAFF"]`) — the roles this token may be used as. v1 keeps the JSON encoding simple (a list, not a struct).

3. **Given** v1 ships HS256 (per Story 5.4's `JwtIssuer`), **When** Story 5.5 lands, **Then** the same `JwtIssuer` is reused with the additional fields. RS256 is out of scope — the AC mentions RS256 + quarterly rotation as a future hardening; v1 keeps HS256 + 1h TTL. The `HMAC_JWT_SECRET` env var is the same key (no separate key for service tokens in v1).

4. **Given** the runtime smoke rule (project memory `runtime-smoke-rule.md`), **When** Story 5.5 completes, **Then** the dev agent runs `bash dev/scripts/smoke-auth-5-5.sh` which: (a) starts `services/auth`; (b) `POST /api/auth/service-token` with `{serviceAccountId, allowedRoles, callerChain}` — assert HTTP 200 + `sessionToken` is non-empty; (c) decode the JWT (a `jwt` CLI tool or a Python one-liner via `python3 -c`) — assert the payload has `serviceAccountId`, `callerChain`, `allowedRoles`; (d) kill the process, exit 0.

### Out of scope (do NOT build — deferred, stated to prevent scope creep)

- **RS256 + quarterly key rotation** → Future ops hardening. v1 keeps HS256.
- **Multi-role JWTs** (multiple roles per token) → Future story. v1 ships a single role.
- **Service-account credentials** (how the auth service verifies the caller is a real service) → Future story. v1 trusts the endpoint (single-tenant; the auth service is the only one that issues tokens).
- **Token introspection** (cross-service validation of tokens) → Future story. v1 assumes the receiver trusts the auth service's HS256 signature.

## Tasks / Subtasks

- [ ] **Task 1 — Extend `JwtIssuer` for service tokens** (AC: #1, #2, #3)
  - [ ] `services/auth/.../infrastructure/security/JwtIssuer.java` — add `issueServiceToken(serviceAccountId, allowedRoles, callerChain)` that issues a JWT with the extra fields.
  - [ ] The user-token path stays unchanged (HS256, 1h TTL, `sub` + `email` + `role`).

- [ ] **Task 2 — `ServiceTokenCommand` + `IssueServiceTokenUseCase`** (AC: #1, #2)
  - [ ] `services/auth/.../application/port/ServiceTokenCommand.java` — record `(String serviceAccountId, List<String> allowedRoles, List<String> callerChain)`.
  - [ ] `services/auth/.../application/port/ServiceTokenResult.java` — record `(String sessionToken)`.
  - [ ] `services/auth/.../application/usecase/IssueServiceTokenUseCase.java` — validates the command + delegates to `JwtIssuer.issueServiceToken`.

- [ ] **Task 3 — REST endpoint** (AC: #1)
  - [ ] `services/auth/.../application/web/AuthController.java` — add `@PostMapping("/service-token")`.
  - [ ] `services/auth/.../infrastructure/web/AuthSecurityConfig.java` — permits all (v1 single-tenant).

- [ ] **Task 4 — Tests** (AC: #1, #2)
  - [ ] `IssueServiceTokenUseCaseTest` — 3 tests: `execute_returnsTokenWithServiceAccountFields`, `execute_throwsOnNullServiceAccountId`, `execute_throwsOnEmptyAllowedRoles`.

- [ ] **Task 5 — Runtime smoke script** (AC: #4)
  - [ ] `dev/scripts/smoke-auth-5-5.sh` — bash. Mirrors `smoke-auth-5-4.sh` (auth service already runs in the dev env).

## Dev Notes

### Implementation Notes

- **`JwtIssuer` is the same class as Story 5.4** — the new `issueServiceToken` method overloads the existing `issue(userId, email, role)`. The internal JCS payload map adds the service-account fields.
- **No new dependencies** — `JwtIssuer` reuses `HmacEventSigner` + `JcsCanonicalJson` from util. The Jackson `ObjectMapper` is reused.
- **Test counts target** — `≥ 3 new tests`. Auth service baseline after Story 5.4: 9 tests; target after Story 5.5: ≥ 12.

### Debug Log References

(none — fresh story; debugs land in Dev Notes / Completion Notes after the dev agent runs)

### Completion Notes List

(empty — populated by dev-story)

### Deviations from literal story text (flagged for review)

(none — fresh story; deviations land here after dev-story)

## Dev Notes (post-dev-story section — populated after implementation)

### Project Structure Notes

- **Path placement** (per architecture §6):
  - `services/auth/src/main/java/vn/vnpt/auth/infrastructure/security/JwtIssuer.java` ← extend (Task 1)
  - `services/auth/src/main/java/vn/vnpt/auth/application/port/ServiceTokenCommand.java` + `ServiceTokenResult.java` ← new (Task 2)
  - `services/auth/src/main/java/vn/vnpt/auth/application/usecase/IssueServiceTokenUseCase.java` ← new (Task 2)
  - `services/auth/src/main/java/vn/vnpt/auth/application/web/AuthController.java` ← extend (Task 3)
  - `services/auth/src/test/java/vn/vnpt/auth/application/usecase/IssueServiceTokenUseCaseTest.java` ← new (Task 4)
  - `dev/scripts/smoke-auth-5-5.sh` ← new (Task 5)

- **Detected conflicts / variances (with rationale):**
  - **HS256 instead of RS256** — per AC #3 (v1 keeps HS256; RS256 is a future hardening).

### References

- [Source: `_bmad-output/planning-artifacts/epics.md:847-858` — Story 5.5 AC]
- [Source: `services/auth/.../infrastructure/security/JwtIssuer.java` — Story 5.4 issuer (extend here)]
- [Source: `services/auth/.../infrastructure/security/HmacEventSigner` (via util) — HMAC primitive (Story 3.5)
- [Source: `services/auth/.../application/web/AuthController.java` — Story 5.4 controller (extend here)]
- [Source: project memory `runtime-smoke-rule.md` — every dev-story must runtime-smoke
- [Source: project memory `deep-review-rules.md` — F1: shared code in util/, 1-sentence javadoc, no single-impl abstractions

## Dev Agent Record

### Agent Model Used

{{agent_model_name_version}}

### Debug Log References

### Completion Notes List

### File List