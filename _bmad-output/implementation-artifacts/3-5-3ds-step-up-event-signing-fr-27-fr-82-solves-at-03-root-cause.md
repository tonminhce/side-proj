---
baseline_commit: 6b6d952
---

# Story 3.5: 3DS step-up + event signing (FR-27, FR-82) — solves AT-03 root cause

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a risk-aware payment flow,
I want 3DS step-up only for risk-flagged transactions,
And every event to carry a per-service HMAC-SHA-256 signature per ADR-20.

## Acceptance Criteria

1. **Given** `util/.../events/HmacEventSigner.java` + `JcsCanonicalJson.java` + `EnvelopeSigner.java` already exist as production-ready primitives (verified by 15 existing tests in `util/src/test/java/vn/vnpt/util/events/`), **When** Story 3.5 lands, **Then** the payment outbox publisher `services/payment/.../infrastructure/outbox/PaymentModulithOutboxPublisher.java` (per Story 3.1's `@PostConstruct`-bridged Modulith outbox) signs every envelope before insert. The signature uses `HmacEventSigner.sign(JcsCanonicalJson.serialize(envelopeWithoutSignatures), serviceSecret)` where `serviceSecret` is fetched from Vault at path `secret/events/hmac/payment` (per `architecture-detail.md:182` + `architecture.md:229` ADR-20). The signed envelope carries `signatures.{service:"payment", hmac_sha256:"<base64url>", key_id:"v1"}` per `architecture-detail.md:184-185`.

2. **Given** FR-82 mandates consumer-side verification (`architecture-detail.md:186-190` "Consumer verification on every consumed event"), **When** Story 3.5 lands, **Then** `services/checkout/.../infrastructure/outbox/ModulithOutboxConsumer.java` (or equivalent — verify; the existing checkout-side `@ApplicationModuleListener` for saga advancement) calls `HmacEventSigner.verify(canonicalJson, signature, secret)` on every consumed event and rejects events with mismatched signatures. The reject path emits a security alert (Micrometer counter `security.event.signature.mismatch` + WARN log) and returns from the listener (the event stays in the outbox for manual replay; the saga FSM does NOT advance).

3. **Given** FR-27 mandates "3DS step-up only for risk-flagged transactions; SCA exemption logic per EU/UK PSD2" (`prd.md:123`), **When** Story 3.5 lands, **Then** a new `services/payment/.../application/usecase/Requires3dsUseCase.java` (or an inline check in `RealStripePaymentAdapter`) computes the risk decision: a transaction is `requires_3ds` if `cmd.amountCents() >= 3000_00` (€30 SCA threshold under PSD2) AND (`cmd.country()` is in the EEA / UK list — verify against `local-docs/` for the EU+UK country list; for v1 use a stub `Set.of("GB","DE","FR","IT","ES","NL","BE","AT","IE","PT","FI","GR")`) OR the Stripe radar `risk_level` is `elevated`/`highest`. The decision is made BEFORE `PaymentIntent.create(...)` so the Stripe call includes `payment_method_options.card.request_three_d_secure: "any"` when 3DS is required (forcing the challenge) and `"automatic"` when the risk-score is low (letting Stripe decide). The `cmd.country()` and `cmd.riskLevel()` fields are added to `AuthorizePaymentCommand` (compact constructor validates: country is 2-letter ISO-3166-1 alpha-2 OR null; riskLevel is one of `normal|elevated|highest|unknown`). For VN market (not in EU/UK), the 3DS path is bypassed entirely (Stripe's default flow applies).

4. **Given** the existing `RealStripePaymentAdapter` (Story 3.3) maps Stripe's `requires_action` response to `PaymentResult.Status.REQUIRES_ACTION` (already implemented at `services/payment/.../infrastructure/stripe/RealStripePaymentAdapter.java:81-84`), **When** Story 3.5 lands, **Then** the saga orchestrator advances on `REQUIRES_ACTION` by setting the order state to `PAYMENT_REQUIRES_ACTION` (a new enum value added to `services/checkout/.../domain/OrderStatus.java`) and the checkout frontend receives a 3DS challenge URL in the response. The challenge URL is returned by Stripe via `intent.next_action.redirect_to_url.url` (verify the 28.x field name; may be `intent.getNextAction().getRedirectToUrl().getUrl()`) and surfaced to the storefront via the checkout API.

5. **Given** ADR-20 mandates "the producer FAILS LOUD if Vault is unreachable, refuses to publish events rather than signing with a stale or default key" (`architecture-detail.md:192`), **When** Story 3.5 lands, **Then** `HmacServiceKeyProvider` (new file `services/payment/.../infrastructure/security/HmacServiceKeyProvider.java`) reads the secret from Vault at startup and caches for 5 minutes (`architecture-detail.md:187` cache TTL). If the initial Vault read fails, the bean throws at startup (`@PostConstruct IllegalStateException`) — payment service refuses to start without a key. On subsequent Vault outages, the cached key is used (fail-safe; events continue to be signed with the last-known-good key, the security alert `hmac.vault.unavailable` fires).

6. **Given** the existing `services/payment/.../infrastructure/outbox/PaymentModulithOutboxPublisher.java` is the producer-side seam for the Modulith outbox bridge (Story 3.1's `@PostConstruct` constructor + the `payment_aggregate` JPA write path), **When** Story 3.5 lands, **Then** the publisher's write path becomes: (a) build envelope `Map<String, Object>` (event_id, event_type, occurred_at, payload, tenant_id); (b) `canonicalJson = JcsCanonicalJson.serialize(envelope)`; (c) `signature = HmacEventSigner.sign(canonicalJson, secret)`; (d) `envelope.put("signatures", Map.of("service","payment","hmac_sha256",signature,"key_id","v1"))`; (e) write to the `outbox` table (the existing JPA path). The test asserts a signed envelope in `outbox` carries a 64-char base64url HMAC + the service name.

7. **Given** `dev/.env` does not exist (verified by Story 3.3's smoke + the project memory `runtime-smoke-rule.md`), **When** Story 3.5 lands, **Then** the payment service accepts a `HMAC_SERVICE_SECRET` env var as a Vault substitute in the dev profile (`@Profile("dev")` reads from env via `application-dev.yml`; `@Profile("!dev")` reads from Vault). The dev secret is a 32-byte random hex string printed once at startup so the dev can curl-test signing. The prod path uses Vault per ADR-20.

8. **Given** the runtime smoke rule (project memory `runtime-smoke-rule.md` — every dev-story must runtime-smoke), **When** Story 3.5 completes, **Then** the dev agent runs `bash dev/scripts/smoke-payment-3-5.sh` which: (a) clears stale listener on 8086; (b) starts `services/payment` with `HMAC_SERVICE_SECRET=<32-byte-hex>` env var; (c) waits for `/actuator/health` UP; (d) `curl /actuator/health` and grep the response body for a 64-char base64url string (the signature in the outbox table, surfaced indirectly — or directly via a `/bff/internal/outbox/last-signed` debug endpoint registered this story); (e) asserts the `outbox` table has ≥1 row with non-null `signatures` column; (f) kills the process, exits 0. Per F1 policy: trust the smoke, not just unit tests.

9. **Given** the per-service log redaction from Story 3.3 (`util/.../logging/PanRedactingAppender.java`) is wired via `services/payment/.../logback-spring.xml`, **When** Story 3.5 logs a security alert (`security.event.signature.mismatch` / `hmac.vault.unavailable`), **Then** the alert message itself MUST NOT contain the secret key, the signature bytes, or the canonical JSON (any of which could leak via stdout). The log line carries: alert name + producer service name + event_id (which is a Snowflake, not a secret) + the high-level reason ("signature mismatch" or "vault unavailable"). One-line javadoc cites R-15 + the deny-list baseline.

10. **Given** the existing ArchUnit rules in `util/.../archunit/RequestBodyLoggerDenyListTest.java` (Story 3.3 HIGH-3 fix) + the payment-port-contract rules (`services/payment/.../PaymentPortContractTest.java` Story 3.2 / 3.3), **When** Story 3.5 lands, **Then** the `Requires3dsUseCase` lives in `application/usecase` and respects the existing `application.usecase → infrastructure.stripe` ban (Story 3.1 AC #7). The `HmacServiceKeyProvider` lives in `infrastructure/security` (mirror Story 3.2's `PaymentSecurityConfig.java` placement) and is constructor-injected into `PaymentModulithOutboxPublisher`. No new ArchUnit rules needed (the existing payment-port-contract rules cover the placement).

### Out of scope (do NOT build — deferred, stated to prevent scope creep)

- **PSD2 SCA exemption logic per-traffic-volume** (FR-27 secondary clause) → Story 5.x (auth/identity) when the customer service is built. Story 3.5 ships the country-based + amount-based gate only.
- **Stripe Radar integration** (real `risk_level` from `PaymentIntent.review` or `Charge.risk_score`) → Story 5.x. Story 3.5 ships the threshold scaffold + a stub `riskLevel` field that defaults to `unknown`.
- **Per-service key rotation (`SIGHUP` handling, 7-day overlap window)** → Story 10.x (observability/operations). Story 3.5 ships the cached-key-provider only.
- **Outbox event replay tool for security incidents** → Story 10.x. The security alert + stuck-in-outbox behavior is sufficient for v1.
- **Vault integration in dev (`spring-cloud-starter-vault-config`)** → if util doesn't already have Vault primitives, dev profile uses `HMAC_SERVICE_SECRET` env var (AC #7); prod Vault wiring deferred to Story 3.5 follow-up or ops concern.
- **End-to-end 3DS challenge flow in the storefront** → Epic 8 (storefront delivery). Story 3.5 ships the backend decision + `PAYMENT_REQUIRES_ACTION` state; the BFF + frontend deliver the challenge iframe in a separate story.
- **Saga compensator handling for `PAYMENT_REQUIRES_ACTION` timeout** → Story 4.x (order saga timeout). Story 3.5 ships the state transition; the timeout/cancel is a separate saga concern.
- **Multi-tenant key isolation** (per-tenant HMAC keys) → Story 5.x (auth/identity). v1 uses a single `payment` service key.

## Tasks / Subtasks

- [ ] **Task 1 — `HmacServiceKeyProvider` + dev-mode Vault substitute** (AC: #5, #7)
  - [ ] `services/payment/src/main/java/vn/vnpt/payment/infrastructure/security/HmacServiceKeyProvider.java` — `@Component @Profile("!dev")` reads `secret/events/hmac/payment` from Vault at startup; caches the value for 5 minutes; logs a security alert + uses cached value on subsequent Vault outages (fail-safe). `@PostConstruct` throws on initial failure (fail-loud per ADR-20).
  - [ ] `services/payment/src/main/java/vn/vnpt/payment/infrastructure/security/DevHmacServiceKeyProvider.java` — `@Component @Profile("dev")` reads `HMAC_SERVICE_SECRET` env var; falls back to a 32-byte random hex generated at startup if env var is missing (logs the generated value ONCE so the dev can curl-test). The fallback is for dev only — prod path requires the env var to be set explicitly.
  - [ ] Both providers implement a single `String currentSecret()` method; the publisher constructor-injects the provider.

- [ ] **Task 2 — Wire HMAC signing into `PaymentModulithOutboxPublisher`** (AC: #1, #6)
  - [ ] Modify `services/payment/.../infrastructure/outbox/PaymentModulithOutboxPublisher.java` to take `HmacServiceKeyProvider` in the constructor. Build the envelope `Map<String, Object>` before the JPA write, sign via `HmacEventSigner.sign(JcsCanonicalJson.serialize(envelope), provider.currentSecret())`, and add `envelope.put("signatures", Map.of("service","payment","hmac_sha256",sig,"key_id","v1"))` before persisting.
  - [ ] One-line javadoc citing ADR-20 + AT-03. NO multi-paragraph prose.

- [ ] **Task 3 — 3DS risk-decision logic in `RealStripePaymentAdapter`** (AC: #3, #4)
  - [ ] Extend `AuthorizePaymentCommand` (`services/payment/.../application/port/`) with `country` (String, nullable, 2-letter ISO-3166-1 alpha-2) + `riskLevel` (enum: `NORMAL|ELEVATED|HIGHEST|UNKNOWN`) fields. Compact constructor validates.
  - [ ] In `RealStripePaymentAdapter.authorize(...)`, BEFORE `PaymentIntent.create(...)`: compute `boolean requires3ds = (amount >= 3000_00 && EEA.contains(country)) || riskLevel == ELEVATED || riskLevel == HIGHEST`. If true, add `.setPaymentMethodOptions(PaymentIntentCreateParams.PaymentMethodOptions.builder().setCard(PaymentIntentCreateParams.PaymentMethodOptions.Card.builder().setRequestThreeDSecure(PaymentIntentCreateParams.PaymentMethodOptions.Card.RequestThreeDSecure.ANY).build()).build())` to the params. If false, `.setRequestThreeDSecure(RequestThreeDSecure.AUTOMATIC)` (let Stripe decide).
  - [ ] On `requires_action` response, surface `intent.next_action.redirect_to_url.url` (verify the 28.x API) via a new `PaymentResult.requiresActionUrl` field. The saga advances on this URL.

- [ ] **Task 4 — Consumer-side signature verification** (AC: #2)
  - [ ] Modify the consumer-side outbox listener in `services/checkout/.../infrastructure/outbox/` (or wherever the `@ApplicationModuleListener` for payment events lives — verify). On every received event: parse `signatures.hmac_sha256`, fetch the producer's secret from a per-service cache (mirror the `HmacServiceKeyProvider` pattern; for checkout, the producer is `payment`, so it reads `secret/events/hmac/payment` from Vault), recompute HMAC, and reject if mismatch. Reject path emits a `security.event.signature.mismatch` Micrometer counter + WARN log (alert message MUST NOT contain the secret or signature per AC #9) + skips the saga advance.

- [ ] **Task 5 — Add `OrderStatus.PAYMENT_REQUIRES_ACTION`** (AC: #4)
  - [ ] `services/checkout/.../domain/OrderStatus.java` — add `PAYMENT_REQUIRES_ACTION` enum value + javadoc citing FR-27 + the 3DS challenge handoff.
  - [ ] Wire the saga transition: `PAYMENT_PENDING → PAYMENT_REQUIRES_ACTION` on `REQUIRES_ACTION` response from `RealStripePaymentAdapter`. `PAYMENT_REQUIRES_ACTION → PAID` on successful 3DS completion (out of scope for this story — the storefront delivers the challenge; the saga resumes via the next payment webhook in Story 4.x).

- [ ] **Task 6 — Tests** (AC: #1, #2, #3, #6)
  - [ ] `services/payment/src/test/java/vn/vnpt/payment/infrastructure/security/HmacServiceKeyProviderTest.java` — 3 tests: `currentSecret_returnsValueFromProvider`, `currentSecret_cachesAcrossCallsWithinTtl`, `currentSecret_throwsOnInitialFailure` (mock provider returns null initially).
  - [ ] `services/payment/src/test/java/vn/vnpt/payment/infrastructure/outbox/PaymentModulithOutboxPublisherHmacTest.java` — 3 tests: `publish_signsEnvelopeBeforeInsert` (asserts the outbox row carries a 64-char base64url signature), `publish_signatureVerifiesViaHmacEventSigner` (extract the signature from the row, recompute, assert match), `publish_failsLoudWhenProviderReturnsNull` (asserts the publish method throws when the provider has no secret).
  - [ ] `services/payment/src/test/java/vn/vnpt/payment/application/usecase/Requires3dsUseCaseTest.java` (or add tests to `AuthorizePaymentUseCaseTest`) — 4 tests: `requires3ds_eeaAmountOverThreshold_returnsTrue`, `requires3ds_vietnamAmountAnySize_returnsFalse` (no 3DS for non-EEA), `requires3ds_eeaLowAmount_returnsFalse` (below €30 threshold), `requires3ds_riskLevelElevated_returnsTrueRegardlessOfCountry`.

- [ ] **Task 7 — Runtime smoke script** (AC: #8)
  - [ ] `dev/scripts/smoke-payment-3-5.sh` — bash. Pattern mirrors `dev/scripts/smoke-payment-3-3.sh`:
        - (a) Free port 8086 (lsof fallback).
        - (b) Start `services/payment` with `HMAC_SERVICE_SECRET=$(openssl rand -hex 32)` env var (macOS-compatible; the smoke generates a real 32-byte hex).
        - (c) Wait for `/actuator/health` UP (up to 90s).
        - (d) `docker exec postgres psql -d payment_db -c "SELECT id, signatures FROM outbox ORDER BY id DESC LIMIT 1"` (or `docker exec ecommerce-platform-postgres-1 psql ...`) — assert the latest outbox row's `signatures` column is non-null and parses to `Map.of("service","payment","hmac_sha256","<64-char-base64url>","key_id","v1")`.
        - (e) `docker exec postgres psql -d payment_db -c "SELECT hmac_sha256 FROM outbox ORDER BY id DESC LIMIT 1" | grep -E "^[A-Za-z0-9_-]{43,44}$"` — assert the signature is a valid base64url string.
        - (f) Kill the process, exit 0.

- [ ] **Task 8 — Micrometer counters** (AC: #2, #4)
  - [ ] `services/payment/src/main/java/vn/vnpt/payment/infrastructure/metrics/PaymentMetrics.java` — `@Component`, registers `security.event.signature.mismatch` (counter, tagged `producer=payment`), `hmac.vault.unavailable` (counter), `payment.requires_3ds` (counter, tagged `country=EEA|OTHER`).
  - [ ] Wire into `application.yml`'s `management.endpoints.web.exposure.include` (already exposes `health,info,metrics` from Story 3.3; verify).

## Dev Notes

### Implementation Notes

- **util's HMAC primitives are production-ready.** `HmacEventSigner.sign/verify` + `JcsCanonicalJson.serialize` + `EnvelopeSigner` already exist with 15 unit tests in `util/src/test/java/vn/vnpt/util/events/`. Story 3.5 is purely the wiring: producer-side signing + consumer-side verification + 3DS decision. Do NOT reimplement HMAC — `util` already does the right thing (constant-time compare via `MessageDigest.isEqual`, JCS key sort, base64url-no-padding encoding).
- **3DS threshold scope:** the EUR 30 threshold (PSD2 SCA mandate for low-value transactions is at EUR 30 = 3,000,000 minor units of VND if we follow VND cents — verify against the saga spec). For the v1 stub, `3000_00` in `amountCents()` (EUR cents) is the threshold; the country-set is hardcoded to EEA + UK. Future story wires Stripe Radar + per-customer SCA exemptions.
- **Vault in dev:** Story 3.5 uses an env-var substitute, mirroring Story 3.3's `STRIPE_API_KEY` env-var pattern. The dev-mode provider generates a 32-byte hex secret on startup if `HMAC_SERVICE_SECRET` is unset; the generated value is logged once at startup (logged to stdout — NOT via `PanRedactingAppender` since the dev secret is a 32-byte random and never matches `\d{13,19}`; safe to log). Prod path requires Vault via `spring-cloud-starter-vault-config` (verify util has Vault wiring first; if not, add a minimal `@Profile("!dev")` `VaultConfig` reading `secret/events/hmac/payment` via Vault's `kv` mount).
- **Security alert log discipline:** the `security.event.signature.mismatch` log line MUST NOT contain the signature bytes, the canonical JSON, or the secret key. Just the producer service name + event_id (Snowflake) + a high-level reason. R-15 deny-list baseline.
- **Test counts target** — `≥ 10 new tests` (HmacServiceKeyProvider 3 + PaymentModulithOutboxPublisherHmac 3 + Requires3ds 4). Payment service baseline after Story 3.4: 63 tests; target after Story 3.5: ≥ 73.
- **Module count** — `mvn validate` still reports 19 modules (no new modules; this story adds to `services/payment` only).
- **The `signatures` field on the envelope is OPTIONAL for back-compat with Story 3.1 / 3.2 outbox rows** — existing outbox rows from prior stories lack a `signatures` field. Story 3.5's consumer-side verification MUST treat a missing `signatures` field as `event_age_pre_signed_era` (legacy event, accept without verification). The accept-without-verification path is documented in the consumer's javadoc. Future story may add a backfill migration to sign legacy rows.

### Debug Log References

(none — fresh story; debugs land in Dev Notes / Completion Notes after the dev agent runs)

### Completion Notes List

(empty — populated by dev-story)

### Deviations from literal story text (flagged for review)

(none — fresh story; deviations land here after dev-story)

## Dev Notes (post-dev-story section — populated after implementation)

<!-- The dev agent fills in the Implementation Notes, Debug Log References, Completion Notes List, Deviations from literal story text, and File List sections below. -->

### Project Structure Notes

- **Path placement** (per architecture §6 / `architecture.md:298` + `:924` + `:930`):
  - `services/payment/src/main/java/vn/vnpt/payment/infrastructure/security/HmacServiceKeyProvider.java` ← new (Task 1)
  - `services/payment/src/main/java/vn/vnpt/payment/infrastructure/security/DevHmacServiceKeyProvider.java` ← new (Task 1)
  - `services/payment/src/main/java/vn/vnpt/payment/infrastructure/outbox/PaymentModulithOutboxPublisher.java` ← modify (Task 2)
  - `services/payment/src/main/java/vn/vnpt/payment/infrastructure/metrics/PaymentMetrics.java` ← new (Task 8)
  - `services/payment/src/main/java/vn/vnpt/payment/application/port/AuthorizePaymentCommand.java` ← extend (Task 3)
  - `services/payment/src/main/java/vn/vnpt/payment/infrastructure/stripe/RealStripePaymentAdapter.java` ← modify (Task 3)
  - `services/checkout/src/main/java/vn/vnpt/checkout/infrastructure/outbox/` ← modify consumer (Task 4) — file name TBD; verify path
  - `services/checkout/src/main/java/vn/vnpt/checkout/domain/OrderStatus.java` ← extend with `PAYMENT_REQUIRES_ACTION` (Task 5)
  - `services/payment/src/test/java/vn/vnpt/payment/infrastructure/security/HmacServiceKeyProviderTest.java` ← new (Task 6)
  - `services/payment/src/test/java/vn/vnpt/payment/infrastructure/outbox/PaymentModulithOutboxPublisherHmacTest.java` ← new (Task 6)
  - `services/payment/src/test/java/vn/vnpt/payment/application/usecase/Requires3dsUseCaseTest.java` ← new (Task 6)
  - `dev/scripts/smoke-payment-3-5.sh` ← new (Task 7)

- **Detected conflicts / variances (with rationale):**
  - **Vault vs env-var:** `dev/.env` doesn't exist; the dev profile uses `HMAC_SERVICE_SECRET` env var. The Vault path in prod is deferred to ops (the dev secret is generated at startup if missing).
  - **Legacy outbox rows without `signatures`:** accepted by the consumer with a `event_age_pre_signed_era` flag (back-compat with Story 3.1 / 3.2 rows).
  - **3DS threshold country list:** hardcoded EEA + UK for v1; Stripe Radar integration is a future story.
  - **`requiresActionUrl` field on `PaymentResult`:** added as a new field; existing 4-unit-test cases on `PaymentResult` continue to pass with the default-null URL.

### References

- [Source: `_bmad-output/planning-artifacts/epics.md:689-701` — Story 3.5 AC]
- [Source: `_bmad-output/planning-artifacts/prd.md:123` — FR-27 verbatim: "3DS step-up only for risk-flagged transactions; SCA exemption logic per EU/UK PSD2"]
- [Source: `_bmad-output/planning-artifacts/prd.md:208-211` — FR-79 to FR-82 (Compliance; FR-82 = CDC event injection defense)]
- [Source: `_bmad-output/planning-artifacts/addendum.md:21` — R-06 row; `prd.md:332` — risk register mapping]
- [Source: `_bmad-output/planning-artifacts/architecture.md:229` — ADR-20 verbatim: "CDC event injection defense: mTLS + per-service HMAC headers | **RESOLVED** (per AT-03 root cause) | NFR-SEC-2, FR-82"]
- [Source: `_bmad-output/planning-artifacts/architecture.md:683` — "mTLS + per-service HMAC event headers | ADR-20"]
- [Source: `_bmad-output/planning-artifacts/architecture.md:1088` — "R-15 → services/payment/ FR-29" (sibling binding for this story's `services/payment/` placement)]
- [Source: `_bmad-output/planning-artifacts/architecture-detail.md:177-192` — Detail: ADR-20 (HMAC-SHA-256 event signing scheme) — full algorithm spec]
- [Source: `_bmad-output/planning-artifacts/architecture-detail.md:491` — Event envelope: `signatures.{service, hmac_sha256, key_id}`]
- [Source: `_bmad-output/planning-artifacts/architecture-detail.md:548` — "Outbox event security: HMAC header per event (per AT-03 root cause mitigation)"]
- [Source: `_bmad-output/planning-artifacts/architecture.md:1212` — "HMAC event signing (ADR-20) bound in Story 3.5"]
- [Source: `services/payment/.../infrastructure/outbox/PaymentModulithOutboxPublisher.java` — Story 3.1 producer seam (modify for HMAC signing)]
- [Source: `services/payment/.../infrastructure/stripe/RealStripePaymentAdapter.java:81-84` — Story 3.3 `REQUIRES_ACTION` mapping (extend with 3DS decision + `requiresActionUrl`)])
- [Source: `services/payment/.../infrastructure/security/PaymentSecurityConfig.java` — Story 3.2 security config (mirror placement for HmacServiceKeyProvider)]
- [Source: `util/.../events/HmacEventSigner.java` — `sign(canonicalJson, serviceSecret)` + `verify(canonicalJson, signatureB64Url, serviceSecret)` (verify path uses `MessageDigest.isEqual` for constant-time compare)]
- [Source: `util/.../events/JcsCanonicalJson.java` — RFC 8785 canonical JSON (hand-rolled, JDK-only, deterministic across versions)]
- [Source: `util/.../events/EnvelopeSigner.java` — Helper that composes JCS + HMAC + base64url-no-padding]
- [Source: `util/.../archunit/RequestBodyLoggerDenyListTest.java` — Story 3.3 repo-wide ArchUnit rules (R-15 inheritance)]
- [Source: `services/payment/.../test/.../PaymentPortContractTest.java` — Story 3.1/3.2/3.3 ArchUnit boundary tests (inheritance)]
- [Source: project memory `runtime-smoke-rule.md` — every dev-story must runtime-smoke
- [Source: project memory `deep-review-rules.md` — F1: shared code in util/, 1-sentence javadoc, no single-impl abstractions
- [Source: project memory `dev-agent-personas.md` — adopt both `skills/backend-developer.md` + `skills/spring-boot-engineer.md`

## Dev Agent Record

### Agent Model Used

{{agent_model_name_version}}

### Debug Log References

### Completion Notes List

### File List