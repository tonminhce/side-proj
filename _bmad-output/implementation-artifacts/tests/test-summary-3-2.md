# Test Automation Summary — Story 3.2 Stripe Webhook Dedup (FR-26, ADR-21, R-03)

## Story Under Test

- **Story file:** `_bmad-output/implementation-artifacts/3-2-stripe-webhook-dedup-fr-26-solves-r-03.md`
- **Coverage scope:** `services/payment/` — Stripe webhook dedup (`/webhooks/stripe`) + Postgres `webhook_dedup` + `webhook_delivery_log`
- **Test framework:** JUnit 5 + AssertJ + Mockito + Spring Boot Test + Testcontainers Postgres + MockMvc (existing project convention)
- **Runtime smoke (AC #10):** `dev/scripts/smoke-payment-3-2.sh`

## Generated / Augmented Tests

### New E2E tests (this QA pass)

| File | Tests | Purpose |
|------|-------|---------|
| `services/payment/src/test/java/vn/vnpt/payment/application/webhook/StripeWebhookEndToEndIT.java` | **8** | Full HTTP→controller→use case→dedup→Postgres pipeline, no `@MockitoBean` |

The new file fills the canonical FR-26 / NFR-IDEM-1 gap that the existing tests left open:
the existing `StripeWebhookControllerTest` mocks the use case, so the byte-for-byte replay dedup
contract was never exercised through the JPA `INSERT ... ON CONFLICT DO NOTHING` round-trip. The
new class wires real beans end-to-end:

| # | Test | What it proves |
|---|------|----------------|
| 1 | `firstDelivery_writesDedupAndDeliveryLog_andReturnsDedupFalse` | AC #8 happy path — HTTP 200 + `webhook_dedup` row + `webhook_delivery_log` row + `dedup:false` |
| 2 | `replaySamePayload_returnsDedupTrue_andNeitherTableGetsAnotherRow` | AC #3 + AC #8 + FR-26 canonical — byte-for-byte replay → `dedup:true`, dedup count = 1 (Postgres UNIQUE), delivery_log count = 1 (side-effect skipped) |
| 3 | `livemodeFromPayload_propagatesToDedupRow` | AC #6 observability — `livemode` column reflects the JSON payload's flag |
| 4 | `differentEventIds_eachInsertedIndependently` | Dedup is keyed on `event.id` alone; distinct ids each insert |
| 5 | `blankId_returns400_andNoDedupRowWritten` | AC #7 trust boundary — blank `id` → 400, no DB row written |
| 6 | `blankType_returns400` | AC #7 trust boundary — blank `type` → 400 |
| 7 | `idOver128_returns400` | AC #7 + AC #2 — PK ceiling enforced |
| 8 | `malformedJson_returns400` | AC #7 — malformed must NOT be silently swallowed; on-call must see it |

Mechanics:
- `@SpringBootTest(MOCK)` + `Testcontainers` Postgres (mirrors existing `StripeWebhookControllerTest`)
- `@Transactional @Rollback` so each test starts with empty tables (mirrors `WebhookDedupRepositoryTest`)
- `EntityManager.flush()` before each JDBC count — JdbcTemplate does not auto-flush Hibernate's
  persistence context, so `WebhookDeliveryLogRepository.save()` rows are invisible to raw SQL until
  flushed (this would have hidden a real bug if `save()` was ever swapped for a non-managed write)

### Existing tests (untouched, all green)

| File | Tests | Layer |
|------|-------|-------|
| `application/webhook/StripeWebhookEventTest.java` | 10 | Record validation (id/type/livemode/created/data) |
| `application/usecase/HandleStripeWebhookUseCaseTest.java` | 4 | Use case (Mockito) — first delivery, duplicate, paranoid type, null event |
| `application/webhook/StripeWebhookControllerTest.java` | 3 | Controller (use case mocked) — 200 dedup:false, 200 dedup:true, 400 missing id |
| `infrastructure/entity/WebhookDedupRepositoryTest.java` | 3 | Repository IT — append insert, append duplicate, existsByEventId |
| `PaymentPortContractTest.java` | 3 | ArchUnit — `application → infrastructure.{stripe,entity,repository}` boundary |

Other pre-existing tests not owned by this story (`IdempotencyKeyTest`, `AuthorizePaymentUseCaseTest`,
`AuthorizePaymentCommandTest`, `StripePaymentAdapterTest`) also green.

## Coverage Matrix

| Acceptance Criterion | Tests |
|---|---|
| **#1** — Flyway V002 `webhook_dedup` table in `payment_db` | Verified by V002 migration applying during Testcontainers boot + Smoke `smoke-payment-3-2.sh` Flyway assertion |
| **#2** — PK on `event_id VARCHAR(128)` + `event_type` + `received_at` + `livemode` + `processed_at` | `WebhookDedupRepositoryTest` (insert path) + `StripeWebhookEventTest` (length ceiling) |
| **#3** — `event.id` is byte-for-byte dedup key, no transformation | `StripeWebhookEndToEndIT.replaySamePayload...` (byte-for-byte replay) + `StripeWebhookEventTest` (id length & content captured) |
| **#4** — JPA entity + port + repository mirroring catalog `ProcessedEvent` | `WebhookDedupRepositoryTest` + `PaymentPortContractTest.application_usecase_mayNotImportJpaRepositories` |
| **#5** — Dedup on second insert returns existing `received_at`, skips side-effects | `StripeWebhookEndToEndIT.replaySamePayload...` (canonical; delivery_log count = 1) + `HandleStripeWebhookUseCaseTest.execute_duplicateDelivery_skipsDedupAndSideEffect` |
| **#6** — `livemode` observability only (not dedup) | `StripeWebhookEndToEndIT.livemodeFromPayload_propagatesToDedupRow` (new) + V002 schema + entity javadoc |
| **#7** — Controller extracts fields, returns 200 with body shape; 400 on malformed/missing | `StripeWebhookEndToEndIT` (5 path-coverage tests including 400 + malformed JSON); `StripeWebhookControllerTest` (mocked use case) |
| **#8** — `@Transactional` use case; first call → 1 insertion + 1 side-effect; second call → 0 | `StripeWebhookEndToEndIT` firstDelivery + replay tests (real @Transactional + DB row counts) |
| **#9** — Reusable `StripeWebhookHandler` port + canonical existsBy/append pattern | `HandleStripeWebhookUseCaseTest` + `StripeWebhookEndToEndIT.replaySamePayload...` |
| **#10** — Runtime smoke `smoke-payment-3-2.sh` end-to-end | `dev/scripts/smoke-payment-3-2.sh` (5 checks: UP, Flyway V002, dedup:false, dedup:true, count=1) |
| **#11** — Package placement + ArchUnit boundaries | `PaymentPortContractTest` (3 rules) |

## Test Results

```
[INFO] Tests run: 58, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Breakdown:
- 50 pre-existing tests (Story 3.1 baseline + Story 3.2 unit/IT/controller tests)
- **8 new tests** added by this QA pass (`StripeWebhookEndToEndIT`)

Total in `services/payment`: **58 tests / 0 failures**.

## Files Changed

- **New:** `services/payment/src/test/java/vn/vnpt/payment/application/webhook/StripeWebhookEndToEndIT.java`
- **Modified:** none (the gap-fill test is additive; existing tests remain as the unit/MockMvc/unit-IA boundary)

## Next Steps

1. Run `bash dev/scripts/smoke-payment-3-2.sh` after `docker compose up` to confirm runtime smoke green.
2. Story 3.3 (Elements iframe) and Story 3.5 (HMAC signing + saga) will reuse
   `HandleStripeWebhookUseCase`; re-run this E2E with the new handler in place to prove the
   reusable-dedup pattern works for additional event types.
3. Consider moving the new E2E from `application/webhook/` to a top-level
   `application/webhook/e2e/` package if/when more cross-cutting E2E tests land.
