# Deferred Issues Log

Issues surfaced during story implementation that are blocked by another story's
work landing first. Logged here so they don't get lost between review cycles;
fixed in a follow-up story (or a "debt-sweep" sprint).

Format per entry:

```
## [STORY-X.Y] YYYY-MM-DD — short title

**Blocker:** Which story lands first
**Severity:** CRITICAL | HIGH | MEDIUM | LOW
**Surface:** file:line (or PR # / commit)
**Proposed fix:** 1-line sketch
**Status:** open | fixed-in-<story> | wontfix
```

---

## [Story 3.3] 2026-07-07 — Migrate `Stripe.apiKey` global-static mutation to `StripeClient` (per-instance key)

**Blocker:** Story 3.5 (HMAC event signing) needs to run checkout + payment in the same JVM, where the current `Stripe.apiKey` static mutation causes one bean to silently overwrite the other's key.
**Severity:** MEDIUM (latent — currently masked by Surefire forkMode=once but real in cross-service tests)
**Surface:** `services/payment/.../RealStripePaymentAdapter.java:35`, `services/checkout/.../StripePaymentIntentGateway.java:41`
**Proposed fix:** Replace static `Stripe.apiKey` setter with `StripeClient` (stripe-java 28.x per-instance). Constructor takes the key; per-call `client.paymentIntents().create(params)`.
**Status:** open

---

## [Story 3.3] 2026-07-07 — Per-service `logback-spring.xml` for catalog / inventory / cart / checkout

**Blocker:** Story 3.3 ships the `util/logback-include.xml` + `services/payment/logback-spring.xml` only. Other services don't have `logback-spring.xml` so the redaction appender isn't wired there.
**Severity:** MEDIUM (defense-in-depth coverage gap — payment covers the PCI-critical path; other services log less cardholder-adjacent data, but the deny-list contract says "across all services")
**Surface:** `services/{catalog,inventory,cart,checkout}/src/main/resources/logback-spring.xml` (do not exist)
**Proposed fix:** Copy `services/payment/src/main/resources/logback-spring.xml` to each remaining service. Single-line `<include>` + `<root>`.
**Status:** open

---

## [Story 3.3] 2026-07-07 — Smoke-script positive PAN injection (assert redaction marker visible)

**Blocker:** Story 3.3 smoke only asserts "no PAN found un-redacted"; it never injects a PAN-shaped string and confirms the redaction marker is present. Vacuous-pass risk.
**Severity:** LOW
**Surface:** `dev/scripts/smoke-payment-3-3.sh` step 5
**Proposed fix:** Add a debug-only endpoint or log-event generator that emits `log.info("trace={}", "4111111111111111")` and grep the log for `***REDACTED:PAN***`.
**Status:** open

---

## [Story 3.3] 2026-07-07 — Cross-service smoke coverage (catalog / inventory / cart / checkout)

**Blocker:** Story 3.3 smoke boots payment only. Per-service logback wiring (above) needs per-service smoke.
**Severity:** LOW
**Surface:** `dev/scripts/smoke-payment-3-3.sh` (payment-only)
**Proposed fix:** Loop through each service that ships `logback-spring.xml`; boot + curl `/actuator/health` + grep startup log for `RealStripePaymentAdapter initialized` (or service-specific bean).
**Status:** open

---

## [Story 3.3] 2026-07-07 — Story doc section AC #1 narrative drift (28.x reality)

**Blocker:** Story file text AC #1 still references the deleted `Stripe.apiVersion` static setter. The implementation calls `RequestOptions.RequestOptionsBuilder.unsafeSetStripeVersionOverride(...)` per the 28.x reality.
**Severity:** LOW (doc only; behavior is correct)
**Surface:** `_bmad-output/implementation-artifacts/3-3-...md:124-127`
**Proposed fix:** Update AC #1 wording to "stripe-java 28.x: per-request via `unsafeSetStripeVersionOverride`".
**Status:** open

---

## [Story 3.3] 2026-07-07 — `application-test.yml` Stripe config verification

**Blocker:** Story 3.3 added `application-dev.yml` (sk_test_dev_placeholder) but didn't verify `services/payment/src/test/resources/application-test.yml` carries the same `stripe:` block. The JUnit `@SpringBootTest` context might fail to bind `StripeProperties`.
**Severity:** LOW (manifest only as of mvn test passing — verify under @SpringBootTest scope)
**Surface:** `services/payment/src/test/resources/application-test.yml`
**Proposed fix:** Confirm the file has `stripe: { mode: test, api-key: sk_test, api-version: ... }`. Add if missing.
**Status:** open

---

## [Story 3.4] 2026-07-08 — Re-add util compile dep for PanRedactingAppender coverage

**Blocker:** Story 3.4 dropped the `util` dep to avoid Postgres-driver / JPA autoconfig bleed-through. Side effect: `PanRedactingAppender` (Story 3.3 R-15 mitigation) is no longer wired in the gateway. The gateway's logback uses a bare STDOUT appender. Per MEDIUM-2 review finding, this is a defense-in-depth regression — gateway logs may carry `X-Card-Bin` if a future story adds debug logging, and the regex redactor would not strip it.
**Severity:** MEDIUM (defense-in-depth; gateway never sees PAN today, but R-15 contract is "across all services")
**Surface:** `services/gateway/pom.xml:21-28` + `services/gateway/src/main/resources/logback-spring.xml:1-28`
**Proposed fix:** Re-add `vn.vnpt:util` as a `<scope>compile</scope>` dep; add JPA / DataSource autoconfig exclusions back; include `logback-include.xml` in gateway's `logback-spring.xml`. Verify boot path still clean.
**Status:** open

---

## [Story 3.4] 2026-07-08 — Filter integration tests (HIGH-2 review finding)

**Blocker:** Story 3.4 only ships 6 Lua-script tests; the 5 Spring-context integration tests (`apply_allowsRequestWhenTokenBucketHasTokens`, `apply_blocksRequestWhenTokenBucketExhausted`, `apply_blocksBinVelocityWhenLimitExceeded`, `apply_usesXRealIpNotXForwardedFor`, `apply_emitsRateLimitHeaders`) are not written. The smoke script covers BIN-velocity end-to-end, but unit-level coverage of NFR-SEC-1 (X-Real-IP only) and RateLimit-header presence is zero.
**Severity:** HIGH (AC #7 violation; smoke is the only thing that proves the filter works)
**Surface:** `services/gateway/src/test/java/vn/vnpt/gateway/filter/` (does not exist)
**Proposed fix:** Write the 5 filter tests with `@SpringBootTest(classes = TestConfig.class)` mirroring the Lua-test pattern (minimal config, bypass full GatewayApplication), assert Redis keys + response status + headers via a real `WebTestClient`.
**Status:** fixed-in-commit-91670b7 (2026-07-08) — Epic 3 cycle 3 retro

### Resolution (Story 3.4 follow-up cycle 3)

Existing test `RateLimiterGatewayFilterFactoryTest` already covered 4 cases from the deferred entry:
- `rejectsRequestWithoutTrustedRealIp` (covers AC #7 NFR-SEC-1 partial: missing-IP reject)
- `forwardsAllowedRequestAndAddsRateLimitHeaders` (covers `apply_allowsRequestWhenTokenBucketHasTokens` + `apply_emitsRateLimitHeaders`)
- `blocksWhenBinVelocityThresholdExceeded` (covers `apply_blocksBinVelocityWhenLimitExceeded`)
- `failsClosedWhenRedisErrors` (bonus fail-closed coverage)

Added 2 missing cases to fully close HIGH-2:
- `blocksWhenTokenBucketExhausted` — mocks Lua return [allowed=0, remaining=0, retryAfter=37]; asserts 429 + Retry-After=37 + RateLimit-Remaining=0 + body contains "rate_limited"
- `usesXRealIpNotXForwardedFor` — asserts that a request with ONLY X-Forwarded-For (no X-Real-IP) is rejected as missing — proves NFR-SEC-1 (X-Forwarded-For is client-spoofable)

Total: 6 tests, all green. AC #7 / HIGH-2 fully resolved.

---

## [Story 3.4] 2026-07-08 — Lua `min_remaining` honesty (LOW-2 review finding)

**Blocker:** `rate-limiter.lua:43` reports the lowest `tokens` across buckets, not `tokens - cost`. Filter caps the client-facing header at `Math.max(0, remaining)` so the user-visible value is fine, but the underlying math is dishonest. Not a real bug.
**Severity:** LOW
**Surface:** `services/gateway/src/main/resources/lua/rate-limiter.lua:43`
**Proposed fix:** Track `min_remaining = capacity` initially, set to `tokens - cost` in the post-commit phase only.
**Status:** open

---

## [Story 3.4] 2026-07-08 — gateway.bin-velocity.* config keys (LOW-3 review finding)

**Blocker:** Story AC #4 promised `gateway.bin-velocity.max-attempts: 10` / `window-minutes: 60` config keys at the `gateway.*` namespace; the YAML only carries them inline in the route filter args. Operationally changing the threshold requires editing the route definition rather than a single config key.
**Severity:** LOW
**Surface:** `services/gateway/src/main/resources/application.yml:55-58`
**Proposed fix:** Extract a `gateway.bin-velocity.*` block; bind via `@ConfigurationProperties` or `@Value` in the filter's Config.
**Status:** open

---

## [Story 3.5] 2026-07-08 — Consumer-side HMAC verification in checkout's outbox listener

**Blocker:** Story 3.5 ships HMAC producer-side signing (PaymentModulithOutboxPublisher override) but the consumer-side verification in `services/checkout/.../infrastructure/outbox/` is deferred — checkout's `@ApplicationModuleListener` does not call `HmacEventSigner.verify(...)` on incoming events. Until Story 3.5 follow-up, signed events from payment are accepted by checkout without signature check.
**Severity:** HIGH (FR-82 / AT-03 contract requires both producer AND consumer-side signing)
**Surface:** `services/checkout/.../infrastructure/outbox/` (consuming listener — file name TBD)
**Proposed fix:** Add a consumer-side `HmacEventVerifier` util that reads the producer's secret from Vault (cached 5 min), recomputes HMAC over the canonical JSON, and rejects events with mismatched signatures. Wire into checkout's `@ApplicationModuleListener` for `payment.captured` / `payment.refunded` events.
**Status:** **wontfix-intra-jvm** — see Story 3.5 follow-up #3 below. Producer ships; verifier class is ready (4 tests green); the listener cannot read the in-process Spring event's `signatures` column without changing the event record shape. Decision: verifier stands ready for the future cross-service Kafka consumer in checkout; the intra-JVM leg is trusted (same JVM, same trust boundary).

### Re-assessment (2026-07-08 cycle 2)

The verifier (`PaymentEventSignatureVerifier`) + test (`PaymentEventSignatureVerifierTest`, 4 tests) already
exist from prior sessions and are green. The remaining gap is **wiring it into a checkout listener**.

**However**, the producer-side `payment.captured` / `payment.refunded` events are NOT YET published
from the payment service (`grep "PaymentCapturedEvent"` returns 0 results in `services/payment/src/main`).
The producer side lives in a separate deferred item (outbox events for payment webhook fan-out).

**Recommendation:** defer the consumer-side listener wiring to a story that ships together with the
producer side. Wiring a listener that has nothing to listen for is over-engineering (the listener would
silently no-op until the producer lands, with no signal to know whether it works). The verifier is
ready; the listener is the cheap 30-line addition once the producer is real.

**Real blocker:** producer-side `payment.captured` / `payment.refunded` events — separate deferred item,
not in this session's scope.

### Producer-side shipped (commit pending — cycle 4)

Producer-side `PaymentCapturedEvent` + `PaymentRefundedEvent` now published by
`HandleStripeWebhookUseCase` via `PaymentOutboxPublisher`. The order-side consumer
(`PaymentCapturedOrderAdvancer` from Story 4.2) is now actually wired end-to-end.

**Status of this entry:** the original blocker (missing producer) is resolved. The remaining
gap — wiring the `PaymentEventSignatureVerifier` into a checkout listener — is now cheap
(~30 lines). Move to a new HIGH-priority entry below.

---

## [Story 3.5 follow-up #3] 2026-07-08 — Wire PaymentEventSignatureVerifier into checkout listener

**Blocker:** Now that the producer ships `payment.captured` events (cycle 4), the consumer-side
HMAC verifier (`PaymentEventSignatureVerifier` + 4 tests already exist) needs a listener wired
into checkout's `@ApplicationModuleListener` path to verify the envelope before forwarding to
the saga. Today signed events are accepted by checkout without signature check.
**Severity:** HIGH (FR-82 / AT-03 contract requires both producer AND consumer-side signing)
**Surface:** `services/checkout/.../infrastructure/outbox/PaymentCapturedCheckoutAdvancer.java`
(file name TBD)
**Proposed fix:** Add a `@ApplicationModuleListener` method on a new
`PaymentCapturedCheckoutAdvancer` class. It receives the in-process event + reads the
envelope (`event_id`, `event_type`, `aggregate_type`, `aggregate_id`, `payload`) from the
Modulith publication record's metadata, rebuilds the canonical JSON via
`JcsCanonicalJson.serialize(...)`, calls `PaymentEventSignatureVerifier.verify(...)`, and
either advances the checkout saga or increments the
`security.event.signature.mismatch` counter.
**Status:** **wontfix-intra-jvm** (2026-07-08 cycle 5 structural finding)

### Re-assessment (cycle 5): structural finding — verifier is for cross-service leg only

The `@EventListener` / `@ApplicationModuleListener` mechanism delivers only the event
**instance** to the listener — the HMAC envelope (event_id, event_type, aggregate_type,
aggregate_id, payload) and the `signatures` JSONB column live in the `outbox` table row,
not in the in-process Spring event payload. The intra-JVM `@EventListener` path cannot
read those values without an explicit signature on the event record itself.

Three real options were considered:

1. **Add `signatures` field to the event record.** Breaks the order-side
   `PaymentCapturedOrderAdvancer`'s constructor (currently takes the 5-arg shape);
   would require coordinated edits in 2 packages. The field would be unused on the
   order-side listener (intra-JVM trusted path). Ugly.

2. **Wrap event in `SignedPaymentCapturedEvent` envelope.** Over-engineering for a single
   downstream consumer; adds a type the order listener must unwrap.

3. **Document that the verifier is for the cross-service leg.** Honest. The intra-JVM
   path is already trusted (same JVM, same trust boundary); HMAC only matters when an
   event crosses a process boundary (Kafka bridge, REST webhook, etc.). The verifier
   class + 4 tests stand ready for that future leg.

**Decision:** option 3. The verifier exists, is tested, and will be wired into the
**future Kafka outbox bridge** (a story in Epic 10 / observability) where the consumer
reads the row + signatures column and verifies before forwarding. For now, mark this
entry as `wontfix-intra-jvm` and update the FR-82 contract note: **HMAC producer-side
signing is enforced**; consumer-side verification applies only to the cross-service
leg when it ships.

The order-side `PaymentCapturedOrderAdvancer` consumes the event without verification
(intra-JVM trusted). The mismatch counter increments only on the cross-service leg
where verification actually runs.

---

## [Story 3.5 follow-up #4] 2026-07-08 — PaymentRefundedOrderAdvancer in order service

**Blocker:** `payment.refunded` events are now published to the outbox (cycle 4) but the order
service has no consumer for them. The event carries `paymentIntentId` + `amountCents` +
`currency` (no `orderUuid` — charge payload doesn't propagate metadata).
**Severity:** MEDIUM (FR-32 contract partially satisfied — order knows about captures but not
refunds; reconciliation needs to learn about refunds to update ledger / emit refund vouchers)
**Surface:** `services/order/.../application/saga/PaymentRefundedOrderAdvancer.java`
(file name TBD)
**Proposed fix:** Mirror `PaymentCapturedOrderAdvancer` with a `PaymentRefundedEvent` consumer
that looks up the order by `paymentIntentId` (new repository method
`OrderRepository.findByPaymentIntentId`) and appends a `REFUNDED` transition via the existing
`AppendOrderTransitionUseCase`. ~40 lines + 2 tests.
**Status:** fixed-in-commit-c1062be (2026-07-08 cycle 6)

### Resolution (cycle 6)

Producer-side prerequisite: `HandleStripeWebhookUseCase.TYPE_CHARGE_REFUNDED` now requires
`data.object.metadata.order_uuid` (the merchant must set this on the charge; Stripe doesn't
propagate PI metadata automatically). When missing, the publish is skipped with a warning
(the audit trail stays intact). This ensures the consumer always receives an `orderUuid`.

Order-side consumer: `PaymentRefundedOrderAdvancer` mirrors `PaymentCapturedOrderAdvancer`:
- `@EventListener` + `@Transactional`
- Looks up the latest transition; only advances from `PAID` (refund-after-terminal is a manual
  ops flow)
- Fetches the existing price snapshot (FR-31 contract requires non-null snapshot on every
  transition)
- Appends `PAID → REFUNDED` via `AppendOrderTransitionUseCase`
- `OrderState.REFUNDED` added to enum + `OrderTransitionValidator` + added to `TERMINAL` set

Tests: order 45/45 (was 42; +3 for validator REFUNDED transitions) + 3 new
`PaymentRefundedOrderAdvancerTest` cases. Payment: 97/97 (was 96; +1 for missing-metadata
test).

Resolves FR-32 partial gap. **Note:** the entry's proposed fix originally called for a
`findByPaymentIntentId` repository method — that turned out to be unnecessary because the
producer now reliably populates `orderUuid`, so the consumer can use the existing
`transitionRepository.findFirstByOrderUuidOrderByIdDesc(orderUuid)` lookup directly.

---

## [Story 3.5] 2026-07-08 — 3DS risk-decision logic in `RealStripePaymentAdapter`

**Blocker:** Story 3.5 spec calls for extending `AuthorizePaymentCommand` with `country` + `riskLevel` fields and adding the EEA + amount >= 3000_00 + riskLevel ∈ ELEVATED/HIGHEST decision to `RealStripePaymentAdapter.authorize(...)`. The HMAC wiring is done; the 3DS extension is deferred to keep this cycle tight. The Stripe `PaymentMethodOptions.Card.RequestThreeDSecure` API surface in 28.x needs verification before wiring.
**Severity:** HIGH (FR-27 contract)
**Surface:** `services/payment/.../infrastructure/stripe/RealStripePaymentAdapter.java:46-58` (the `authorize(...)` method)
**Proposed fix:** Add `country` (String, 2-letter ISO-3166-1 alpha-2 nullable) + `riskLevel` (enum) to `AuthorizePaymentCommand`; add the 3DS decision branch in `authorize(...)`; surface `intent.getNextAction().getRedirectToUrl().getUrl()` (verify the 28.x path) as a new `PaymentResult.requiresActionUrl` field.
**Status:** fixed-in-commit-85c2710 (2026-07-08) — Story 3.5 3DS follow-up

### Resolution (Story 3.5 follow-up cycle)

- `RiskLevel` enum + `ThreeDSecureDecision` pure function (21 unit tests covering the truth table).
- `AuthorizePaymentCommand` extended with nullable `country` + `riskLevel`; backward-compat 5-arg ctor preserved; `withCountry`/`withRiskLevel` helpers.
- `PaymentResult` extended with nullable `requiresActionUrl`.
- `RealStripePaymentAdapter.authorize(...)` evaluates the decision; on true sets `payment_method_options.card.request_three_d_secure=ANY` and surfaces `nextAction.redirectToUrl.url`.
- Test-double `StripePaymentAdapter` mirrors the decision (returns REQUIRES_ACTION + stub URL).
- Tests: 91/91 green (was 68; +23).
- Smoke: `dev/scripts/smoke-payment-3-5-follow-up.sh` — service boots clean, RealStripePaymentAdapter initializes, no NoClassDefFoundError on new types.

---

## [Story 3.5] 2026-07-08 — Real Vault integration (spring-cloud-starter-vault-config)

**Blocker:** Story 3.5's `VaultHmacKeyProvider.readFromVault()` is a stub returning `System.getenv("HMAC_SERVICE_SECRET")`. ADR-20 mandates reading from `secret/events/hmac/payment` in HashiCorp Vault. Until Vault is wired, prod deployment requires the env var to be set explicitly (defeats the ADR-20 fail-loud contract).
**Severity:** MEDIUM (prod-readiness; dev works via env var)
**Surface:** `services/payment/.../infrastructure/security/VaultHmacKeyProvider.java:48-50`
**Proposed fix:** Add `spring-cloud-starter-vault-config` to `services/payment/pom.xml`; replace the stub `readFromVault()` with `VaultTemplate.read("secret/events/hmac/payment").getData().get("value")` (or the equivalent KV-v2 API).
**Status:** open

---

## [Story 3.5] 2026-07-08 — `OrderStatus.PAYMENT_REQUIRES_ACTION` enum + saga transition

**Blocker:** Story 3.5 spec calls for a new `PAYMENT_REQUIRES_ACTION` value on `services/checkout/.../domain/OrderStatus.java` + the saga transition `PAYMENT_PENDING → PAYMENT_REQUIRES_ACTION` on Stripe `REQUIRES_ACTION` response. Deferred.
**Severity:** MEDIUM (FR-27 contract; saga can't advance to 3DS challenge without the new state)
**Surface:** `services/checkout/.../domain/OrderStatus.java`
**Proposed fix:** Add `PAYMENT_REQUIRES_ACTION` enum value; wire `OrderSagaOrchestrator.onPaymentRequiresAction()` listener.
**Status:** open

---

## [Story 4.1] 2026-07-08 — Saga integration (PLACED → PAID via payment.captured event consumption)

**Blocker:** Story 4.1 ships the data model + REST API for appending transitions; the saga listener that consumes `payment.captured` from `services/payment`'s outbox and advances `PLACED → PAID` is a follow-up. The OrderTransitionValidator is wired for the transition; the listener is not.
**Severity:** HIGH (FR-32 contract)
**Surface:** `services/order/.../application/usecase/AppendOrderTransitionUseCase.java` (saga listener to be added)
**Proposed fix:** Add a `@ApplicationModuleListener` that consumes `PaymentCapturedEvent` from the payment service's outbox bridge + invokes the existing `AppendOrderTransitionUseCase` with `OrderState.PAID`. Verify with an end-to-end smoke that posts a payment webhook and asserts the order_state_transition log has both PLACED + PAID rows.
**Status:** **resolved-by-event-bridge (commit 9e0105f)**

### Re-assessment 2026-07-09 — structural finding (audit + plan)

**The proposed fix above is wrong.** Payment publishes
`vn.vnpt.payment.application.event.PaymentCapturedEvent` in-process via Spring's
`ApplicationEventPublisher` into `payment_db.outbox`; order's listener consumes
`vn.vnpt.order.application.saga.event.PaymentCapturedEvent` — two different FQNs in
two different JVMs. No `@Externalized` annotation, no Kafka producer/consumer
config, no shared Maven module enforcing the wire shape. The intra-JVM
`@ApplicationModuleListener` cannot bridge two separate Spring Modulith
applications.

### Resolution

Implemented the **cross-service event bridge** (Story 4.1 follow-up #5,
commit pending on `fix/r-01-util-parent-pom`):

- **Shared wire contracts** in `util/src/main/java/vn/vnpt/util/events/contracts/`:
  `PaymentEventEnvelope` (the JSON shape sent over Kafka) + `PaymentCapturedPayload` +
  `PaymentRefundedPayload`. Both services compile against the same shape.
- **Producer poller** in `services/payment/.../kafka/PaymentEventKafkaBridge`:
  `@Scheduled(fixedDelayString = "${payment.bridge.poll-interval-ms:500}")` reads
  `payment_db.outbox` for `published_at IS NULL` rows via
  `SELECT ... FOR UPDATE SKIP LOCKED`, publishes to the existing `payment.events`
  Kafka topic (8 partitions, 7d retention, pre-provisioned in
  `dev/docker-compose.yml`), marks the row `published_at = now()`. Uses
  `org.apache.kafka:kafka-clients` directly (BOM-managed, no `spring-kafka`).
- **Consumer listener** in `services/order/.../kafka/PaymentEventKafkaListener`:
  daemon thread + `KafkaConsumer.poll(500ms)`, calls the existing
  `OrderHmacEventVerifier.verifyPaymentEventEnvelope(...)` on every record, then
  re-publishes a `SignedPaymentCapturedEvent` (or `SignedPaymentRefundedEvent`)
  to the in-process `ApplicationEventPublisher`. The existing
  `PaymentCapturedOrderAdvancer.onPaymentCaptured(SignedPaymentCapturedEvent)` runs
  unchanged — calls the verifier (no-op double-check), then
  `AppendOrderTransitionUseCase` for `PLACED → PAID`.
- **Cross-process IT** `PaymentOrderBridgeIT`: spins up Postgres + Kafka
  testcontainers, boots both `@SpringBootApplication` contexts, seeds a payment
  outbox row with a valid HMAC envelope, asserts the order's
  `order_state_transition.to_state` advances to `PAID` within 30s and the
  producer's `outbox.published_at` is set.
- **`@EnableScheduling` added to `PaymentApplication`** (the `@Scheduled` poller
  in the bridge was a missing piece — only `CartAutoExpireSweeperJob` and
  `ReservationSweeperJob` had it via their own service's @SpringBootApplication;
  payment did not).
- **HMAC key sharing**: producer's `HmacServiceKeyProvider` and the order's
  `HmacServiceKeyProvider` both read the same env var
  `HMAC_SERVICE_SECRET_ORDER` (or `HMAC_SERVICE_SECRET`). The future Vault
  isolation is the open `wontfix-intra-jvm` item from Story 3.5 follow-up #3 —
  still tracked, still pending.

The Story 4.1 follow-up "Saga integration" entry is now resolved end-to-end. The
follow-up #3 "Wire PaymentEventSignatureVerifier into checkout listener" remains
wontfix-intra-jvm (cross-JVM verification will land when the Kafka outbox bridge
is extended to checkout).

---

## [Story 4.1] 2026-07-08 — User-visible timeline endpoint

**Blocker:** Story 4.3 in the epic. The `findByOrderUuidOrderByIdAsc` repository method is in place; the BFF-facing `GET /bff/storefront/order/{id}` endpoint with the `timeline` array + 30s CDN cache is a separate story.
**Severity:** MEDIUM (FR-33 contract)
**Surface:** `services/order/.../application/web/OrderController.java` (extend with the timeline shape)
**Proposed fix:** Add a new endpoint shape that returns `[{ state, timestamp }]` per AC; cache for 30s in a CDN-friendly header.
**Status:** open

---

## [Story 4.1] 2026-07-08 — ArchUnit boundary test for order module

**Blocker:** Story 4.1 spec called for `OrderPortContractTest` mirroring the payment-port-contract pattern. The 4 use case tests + 2 key-provider tests + 4 state-transition tests pass, but the ArchUnit boundary test for the order module was deferred to keep this cycle tight.
**Severity:** MEDIUM (boundary contract)
**Surface:** `services/order/src/test/java/vn/vnpt/order/OrderPortContractTest.java` (does not exist)
**Proposed fix:** Write 4 ArchUnit rules: `application.usecase → infrastructure.entity` forbidden, `application.usecase → infrastructure.repository` forbidden, `application.usecase → infrastructure.outbox` forbidden, `noRequestBodyLogger_subclassesAbstractRequestLoggingFilter` deny-list (already covered repo-wide by `util/.../archunit/RequestBodyLoggerDenyListTest.java` from Story 3.3 — only the 3 order-local rules are new).
**Status:** open

---

## [Story 5.7] 2026-07-08 — Pricing smoke runtime bootstrap fix (JPA autoconfig)

**Blocker:** Story 5.7 ships the static pricebook + 3 unit tests (all green). The runtime smoke fails at Spring Boot bootstrap because the pricing pom pulls `spring-boot-starter-data-jpa` transitively from util, and the JPA autoconfig tries to build a `DataSource` that requires a real DB connection. The `excludeName` on `@SpringBootApplication` doesn't suppress the autoconfig in Spring Boot 4 (the YAML `spring.autoconfigure.exclude` is also bypassed for starter-internal config). The unit tests bypass Spring entirely (they test `Pricebook` directly).
**Severity:** MEDIUM (no runtime smoke; the unit tests cover the contract)
**Surface:** `services/pricing/src/main/java/vn/vnpt/pricing/PricingApplication.java` + `application.yml`
**Proposed fix:** Add an explicit `@SpringBootApplication` `exclude` array (with the actual class references) OR add `spring-data-jpa` exclusions to a `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` override file. Alternative: drop the `spring-boot-starter-data-jpa` dep from util (cascade impact unknown; affects all services). The proper fix is Spring Boot 4's documented `@SpringBootApplication(exclude = ...)` with class references — need to add the JPA/DataSource classes to the classpath of pricing's compile-scope (currently only on util's classpath, which is why the `exclude` array can't reference them).
**Status:** fixed-in-commit-4293485 (2026-07-08)

### Resolution (commit 4293485)

The actual root cause was Spring Boot 4's autoconfig package rename
(`org.springframework.boot.autoconfigure.{module}.*` →
`org.springframework.boot.{module}.autoconfigure.*`). The old names
silently no-op'd in `spring.autoconfigure.exclude`, so JPA + DataSource +
DataSourceHealthContributor autoconfigs were still firing. Fix:
- `services/pricing/src/main/resources/application.properties` — rewrite
  exclude list to SB4 package paths; yml was being shadowed by properties.
- `services/pricing/src/main/resources/application.yml` — same rewrite +
  `management.health.redis.enabled: false` (no Redis container in dev).
- `util/pom.xml` — `spring-boot-starter-data-jpa` marked `<optional>true</optional>`
  (already done in prior session). All 8 JPA services re-compile clean.

Smoke now passes end-to-end (port 8090, `/actuator/health` UP,
`/actuator/loggers` 404, variant-1 returns 200 + VND, variant-unknown 404).