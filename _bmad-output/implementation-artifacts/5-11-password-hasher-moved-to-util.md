---
baseline_commit: 6f3bfa9
---

# Story 5.11 (follow-up): PasswordHasher → util/ migration (F1 deep-review rule)

Status: review

Closes Epic 5 closeout MEDIUM "PasswordHasher should live in util/" from `_bmad-output/backlog/deferred-issues.md`.

## Story

As the auth + future customer password flows,
I want the password hasher to live in util/ (shared code, no auth-specific deps),
So that the F1 deep-review rule is satisfied and customer service's eventual password flow gets the helper for free.

## Acceptance Criteria

1. **`util/src/main/java/vn/vnpt/util/security/PasswordHasher.java`** — class moved verbatim from `services/auth/.../infrastructure/security/`. `@Component` retained so Spring DI works across modules (auth pom depends on util at compile scope). Javadoc updated to note "shared code, no auth-specific deps" + future customer-service use.
2. **`util/src/test/java/vn/vnpt/util/security/PasswordHasherTest.java`** — 4 tests (was 2): the original 2 (salt-randomness + verify-correct) + 2 new (verify-returns-false-on-null-encoded, verify-returns-false-on-malformed-encoded).
3. **Auth callers re-wired** — `LoginUseCase`, `RegisterUseCase`, `LoginUseCaseTest`, `RegisterUseCaseTest` import from `vn.vnpt.util.security.PasswordHasher`. Old `services/auth/.../security/PasswordHasher.java` and `PasswordHasherTest.java` deleted.
4. **No behavioral change** — same format (`<iterations>:<saltB64>:<hashB64>`), same 600_000 iterations, same PBKDF2WithHmacSHA256. Existing auth tests still pass without modification beyond the import path.
5. **All tests green**: util 78/78 (was 74, +4 PasswordHasherTest), auth 28/28 (unchanged). Order baseline preserved: 68/68.

## Out of scope (deferred, still open)

- Argon2id swap (future story; current PBKDF2-HMAC-SHA256 600_000 meets OWASP 2024 floor).
- Password rotation + breach-check flow.

## Files changed

**New:**
- `util/src/main/java/vn/vnpt/util/security/PasswordHasher.java`
- `util/src/test/java/vn/vnpt/util/security/PasswordHasherTest.java`

**Deleted:**
- `services/auth/src/main/java/vn/vnpt/auth/infrastructure/security/PasswordHasher.java`
- `services/auth/src/test/java/vn/vnpt/auth/infrastructure/security/PasswordHasherTest.java`

**Modified:**
- `services/auth/src/main/java/vn/vnpt/auth/application/usecase/LoginUseCase.java` (import path)
- `services/auth/src/main/java/vn/vnpt/auth/application/usecase/RegisterUseCase.java` (import path)
- `services/auth/src/test/java/vn/vnpt/auth/application/usecase/LoginUseCaseTest.java` (import path)
- `services/auth/src/test/java/vn/vnpt/auth/application/usecase/RegisterUseCaseTest.java` (import path)

## ponytail notes

- Kept `@Component` rather than dropping to a plain utility class — auth uses constructor injection via Spring, and dropping the stereotype would require adding a `@Bean` in `UtilsAutoConfiguration` (overhead for no benefit). The class is still textbook shared utility; the stereotype is purely a DI hint.