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
**Status:** open — **re-classified at Epic 4 MEDIUM sweep 2026-07-09 as a refactor story, not a sweep item.** Touches both `RealStripePaymentAdapter` + `StripePaymentIntentGateway` + every test that mocks `Stripe.apiKey`. Not safe in a sweep; promote to a story with its own design pass (StripeClient constructor injection, profile-keyed bean wiring, test fixture migration).

**Blocker:** Story 3.3 ships the `util/logback-include.xml` + `services/payment/logback-spring.xml` only. Other services don't have `logback-spring.xml` so the redaction appender isn't wired there.
**Severity:** MEDIUM (defense-in-depth coverage gap — payment covers the PCI-critical path; other services log less cardholder-adjacent data, but the deny-list contract says "across all services")
**Surface:** `services/{catalog,inventory,cart,checkout}/src/main/resources/logback-spring.xml` (do not exist)
**Proposed fix:** Copy `services/payment/src/main/resources/logback-spring.xml` to each remaining service. Single-line `<include>` + `<root>`.
**Status:** fixed-in-commit-<pending> (2026-07-09) — Epic 4 MEDIUM sweep. 4 new files mirroring `services/payment/src/main/resources/logback-spring.xml` (catalog, inventory, cart, checkout). Each one is a 12-line `<configuration>` block that includes `logback-include.xml` and references `REDACTING_CONSOLE`.

---

## [Story 3.3] 2026-07-07 — Smoke-script positive PAN injection (assert redaction marker visible)

**Blocker:** Story 3.3 smoke only asserts "no PAN found un-redacted"; it never injects a PAN-shaped string and confirms the redaction marker is present. Vacuous-pass risk.
**Severity:** LOW
**Surface:** `dev/scripts/smoke-payment-3-3.sh` step 5
**Proposed fix:** Add a debug-only endpoint or log-event generator that emits `log.info("trace={}", "4111111111111111")` and grep the log for `***REDACTED:PAN***`.
**Status:** fixed-in-commit-<pending> (2026-07-09) — Epic 4 LOW sweep. New step 5b in `smoke-payment-3-3.sh` posts a webhook payload with an embedded PAN-shaped string (`4111111111111111` in the `description` field), then greps the log for `REDACTED:PAN` to prove the redactor is active (not just vacuously absent).

---

## [Story 3.3] 2026-07-07 — Cross-service smoke coverage (catalog / inventory / cart / checkout)

**Blocker:** Story 3.3 smoke boots payment only. Per-service logback wiring (above) needs per-service smoke.
**Severity:** LOW
**Surface:** `dev/scripts/smoke-payment-3-3.sh` (payment-only)
**Proposed fix:** Loop through each service that ships `logback-spring.xml`; boot + curl `/actuator/health` + grep startup log for `RealStripePaymentAdapter initialized` (or service-specific bean).
**Status:** fixed-in-commit-<pending> (2026-07-09) — Epic 4 LOW sweep. New `dev/scripts/smoke-cross-service-3-3.sh` iterates over catalog/inventory/cart/checkout (each one has a `logback-spring.xml` after the Epic 4 MEDIUM sweep), boots each in dev profile, asserts `/actuator/health = UP`, and warns if `PanRedactingAppender` / `REDACTING_CONSOLE` is missing from the startup log.

---

## [Story 3.3] 2026-07-07 — Story doc section AC #1 narrative drift (28.x reality)

**Blocker:** Story file text AC #1 still references the deleted `Stripe.apiVersion` static setter. The implementation calls `RequestOptions.RequestOptionsBuilder.unsafeSetStripeVersionOverride(...)` per the 28.x reality.
**Severity:** LOW (doc only; behavior is correct)
**Surface:** `_bmad-output/implementation-artifacts/3-3-...md:124-127`
**Proposed fix:** Update AC #1 wording to "stripe-java 28.x: per-request via `unsafeSetStripeVersionOverride`".
**Status:** fixed-in-commit-<pending> (2026-07-09) — Epic 4 LOW sweep. AC #1 narrative + the "com.stripe:stripe-java 28.x API surface" Dev Notes section both updated to reference `RequestOptions.RequestOptionsBuilder.unsafeSetStripeVersionOverride(...)` per-request pinning. The `Stripe.apiKey` static setter (line ~119) is still correct.

---

## [Story 3.3] 2026-07-07 — `application-test.yml` Stripe config verification

**Blocker:** Story 3.3 added `application-dev.yml` (sk_test_dev_placeholder) but didn't verify `services/payment/src/test/resources/application-test.yml` carries the same `stripe:` block. The JUnit `@SpringBootTest` context might fail to bind `StripeProperties`.
**Severity:** LOW (manifest only as of mvn test passing — verify under @SpringBootTest scope)
**Surface:** `services/payment/src/test/resources/application-test.yml`
**Proposed fix:** Confirm the file has `stripe: { mode: test, api-key: sk_test, api-version: ... }`. Add if missing.
**Status:** fixed-in-commit-<pending> (2026-07-09) — Epic 4 LOW sweep. `stripe: { mode, api-key, api-version, webhook-signing-secret }` block added to `application-test.yml`. The existing `webhook-signing-secret` was there; `mode` / `api-key` / `api-version` were missing and would have triggered a binding warning under `@SpringBootTest`.

---

## [Story 3.4] 2026-07-08 — Re-add util compile dep for PanRedactingAppender coverage

**Blocker:** Story 3.4 dropped the `util` dep to avoid Postgres-driver / JPA autoconfig bleed-through. Side effect: `PanRedactingAppender` (Story 3.3 R-15 mitigation) is no longer wired in the gateway. The gateway's logback uses a bare STDOUT appender. Per MEDIUM-2 review finding, this is a defense-in-depth regression — gateway logs may carry `X-Card-Bin` if a future story adds debug logging, and the regex redactor would not strip it.
**Severity:** MEDIUM (defense-in-depth; gateway never sees PAN today, but R-15 contract is "across all services")
**Surface:** `services/gateway/pom.xml:21-28` + `services/gateway/src/main/resources/logback-spring.xml:1-28`
**Proposed fix:** Re-add `vn.vnpt:util` as a `<scope>compile</scope>` dep; add JPA / DataSource autoconfig exclusions back; include `logback-include.xml` in gateway's `logback-spring.xml`. Verify boot path still clean.
**Status:** fixed-in-commit-<pending> (2026-07-09) — Epic 4 MEDIUM sweep. `vn.vnpt:util` re-added as compile dep (Story 3.4's drop was overcautious — the `@EnableAutoConfiguration(excludeName=...)` on `GatewayApplication` already excludes `DataSourceAutoConfiguration` + `HibernateJpaAutoConfiguration` + `DataSourceTransactionManagerAutoConfiguration`, and util's JPA dep is `<optional>true</optional>`). Gateway `logback-spring.xml` rewritten to include the shared `logback-include.xml` and reference `REDACTING_CONSOLE`.

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
**Status:** fixed-in-commit-<pending> (2026-07-09) — Epic 4 LOW sweep. `min_remaining` is now computed in the post-decision phase: for blocked buckets the post-call state is the (unchanged) current `tokens`; for allowed requests it's `tokens - cost` (post-commit). The user-facing `RateLimit-Remaining` header (capped at `Math.max(0, ...)` in the filter) is now honest.

---

## [Story 3.4] 2026-07-08 — gateway.bin-velocity.* config keys (LOW-3 review finding)

**Blocker:** Story AC #4 promised `gateway.bin-velocity.max-attempts: 10` / `window-minutes: 60` config keys at the `gateway.*` namespace; the YAML only carries them inline in the route filter args. Operationally changing the threshold requires editing the route definition rather than a single config key.
**Severity:** LOW
**Surface:** `services/gateway/src/main/resources/application.yml:55-58`
**Proposed fix:** Extract a `gateway.bin-velocity.*` block; bind via `@ConfigurationProperties` or `@Value` in the filter's Config.
**Status:** fixed-in-commit-<pending> (2026-07-09) — Epic 4 LOW sweep. New `GatewayBinVelocityProperties` (`@ConfigurationProperties(prefix="gateway.bin-velocity")`) bound via `GatewayApplication`'s `@EnableConfigurationProperties`. The filter's `Config` has new `effectiveWindowMinutes()` / `effectiveBinVelocityMaxAttempts()` helpers — route-arg overrides still win (for per-route tuning); absent overrides, the config block is the source of truth. YAML now carries `gateway.bin-velocity.{max-attempts, window-minutes}` at the top level.

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
**Status:** open — **re-confirmed at Epic 4 MEDIUM sweep 2026-07-09 as prod-readiness, not dev-blocker.** The dev path works (env var); the prod path needs the `spring-cloud-starter-vault-config` integration. Schedule for the ops hardening story (1 day) when Vault is in scope; no action needed until then.

---

## [Story 3.5] 2026-07-08 — `OrderStatus.PAYMENT_REQUIRES_ACTION` enum + saga transition

**Blocker:** Story 3.5 spec calls for a new `PAYMENT_REQUIRES_ACTION` value on `services/checkout/.../domain/OrderStatus.java` + the saga transition `PAYMENT_PENDING → PAYMENT_REQUIRES_ACTION` on Stripe `REQUIRES_ACTION` response. Deferred.
**Severity:** MEDIUM (FR-27 contract; saga can't advance to 3DS challenge without the new state)
**Surface:** `services/checkout/.../domain/OrderStatus.java`
**Proposed fix:** Add `PAYMENT_REQUIRES_ACTION` enum value; wire `OrderSagaOrchestrator.onPaymentRequiresAction()` listener.
**Status:** open — **re-confirmed at Epic 4 MEDIUM sweep 2026-07-09 as needs-UX-input.** The 3DS challenge handoff shape (where does the user land after a REQUIRES_ACTION response? what does the BFF show?) needs a UX decision before the saga transition is designed. Schedule after the BFF team ships the storefront-side 3DS placeholder.

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
**Status:** fixed-in-commit-baa5aae (Epic 3 sprint 1) — Story 4.3 ships the exact endpoint: `GET /api/orders/{orderUuid}/timeline` with `Cache-Control: max-age=30,public` + `Vary: Accept-Encoding`, returns `{orderUuid, timeline:[{state,timestamp}]}`, 200 with empty array for unknown orders. Closed 2026-07-09.

---

## [Story 4.1] 2026-07-08 — ArchUnit boundary test for order module

**Blocker:** Story 4.1 spec called for `OrderPortContractTest` mirroring the payment-port-contract pattern. The 4 use case tests + 2 key-provider tests + 4 state-transition tests pass, but the ArchUnit boundary test for the order module was deferred to keep this cycle tight.
**Severity:** MEDIUM (boundary contract)
**Surface:** `services/order/src/test/java/vn/vnpt/order/OrderPortContractTest.java` (does not exist)
**Proposed fix:** Write 4 ArchUnit rules: `application.usecase → infrastructure.entity` forbidden, `application.usecase → infrastructure.repository` forbidden, `application.usecase → infrastructure.outbox` forbidden, `noRequestBodyLogger_subclassesAbstractRequestLoggingFilter` deny-list (already covered repo-wide by `util/.../archunit/RequestBodyLoggerDenyListTest.java` from Story 3.3 — only the 3 order-local rules are new).
**Status:** fixed-in-commit-<pending> (2026-07-09) — Epic 4 MEDIUM sweep. New `OrderPortContractTest` at `services/order/src/test/java/vn/vnpt/order/OrderPortContractTest.java` covers the 3 order-local rules (entity / repository / outbox forbidden). The `noRequestBodyLogger` deny-list is already covered repo-wide by util's `RequestBodyLoggerDenyListTest` (Story 3.3), so it is intentionally NOT duplicated here.

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

---

## [Epic 5 closeout] 2026-07-09 — Customer module domain-layer leakage (Story 5.1)

**Blocker:** Customer domain record imports JPA entity.
**Severity:** MEDIUM (F1 deep-review rule violation; not a runtime bug today)
**Surface:** `services/customer/.../domain/Customer.java:13` (`List<AddressEntity> addresses`)
**Proposed fix:** Introduce a `domain/Address` record (mirror of `Customer`) and map `CustomerEntity → Customer` (with `AddressEntity → Address`) at the use-case boundary. Use cases return domain records; controllers map domain → DTOs. This is the lazy version of the full port-seam refactor (see "OrderPortContractTest" entry below).
**Status:** open — Epic 5 closure

## [Epic 5 closeout] 2026-07-09 — VnAddressCatalog in domain layer (Story 5.3)

**Blocker:** Domain layer should not depend on Jackson + Spring ClassPathResource + filesystem.
**Severity:** MEDIUM (F1 deep-review rule; the filesystem fallback silently swallows IOException so a misconfigured resource bundle makes the catalog appear empty — see also next entry)
**Surface:** `services/customer/.../domain/VnAddressCatalog.java:24-65`
**Proposed fix:** Move to `infrastructure/catalog/VnAddressCatalog` (or `infrastructure/repository/`); introduce `domain/AddressCatalog` interface that the infrastructure impl satisfies. Lazy: just relocate the class.
**Status:** open — Epic 5 closure

## [Epic 5 closeout] 2026-07-09 — VnAddressCatalog filesystem fallback swallows IOException (Story 5.3)

**Blocker:** Same as above — debug-only filesystem path, silently returns [] on failure.
**Severity:** MEDIUM (production-affecting when seed/vn-addresses.json is missing from packaged jar)
**Surface:** `services/customer/.../domain/VnAddressCatalog.java:51-64`
**Proposed fix:** Delete the filesystem fallback block. Fail-fast on missing classpath resource at startup (throw, not log+continue).
**Status:** open — Epic 5 closure

## [Epic 5 closeout] 2026-07-09 — `domain/Customer` record leaks to controller via listAddresses return type (Story 5.1)

**Blocker:** Depends on the domain-layer fix above.
**Severity:** LOW (works today because AddressEntity has @JsonIgnore on lazy customer; but the controller return type is wrong)
**Surface:** `services/customer/.../application/web/CustomerController.java:101`
**Proposed fix:** After introducing domain/Address, change `listAddresses` to return `List<AddressDto>` (already done in this cycle) and ensure the use-case returns domain addresses.
**Status:** partially-fixed — `listAddresses` now returns `List<AddressDto>`. The deeper domain/Address record work is open.

## [Epic 5 closeout] 2026-07-09 — PasswordHasher should live in util/ (Story 5.4)

**Blocker:** F1 deep-review rule; shared code in util/.
**Severity:** MEDIUM (architectural — the hasher has no auth-specific deps and is a textbook shared utility)
**Surface:** `services/auth/.../infrastructure/security/PasswordHasher.java`
**Proposed fix:** Move to `util/src/main/java/vn/vnpt/util/security/PasswordHasher.java`; inject from there. Customer service's eventual password flow (story out of scope) gets the same helper for free.
**Status:** open — Epic 5 closure

## [Epic 5 closeout] 2026-07-09 — `/api/auth/service-token` endpoint is publicly callable (Story 5.5)

**Blocker:** Endpoint mints credentials; current `AuthSecurityConfig` permitAll means anyone can hit it.
**Severity:** HIGH (security — anonymous caller can mint a service-account JWT with arbitrary allowedRoles)
**Surface:** `services/auth/.../infrastructure/web/AuthSecurityConfig.java:14-27`
**Proposed fix:** Either (a) delete the config entirely and let Spring Security reject unauthenticated requests, then permit `/api/auth/register + /api/auth/login` only; or (b) keep it but protect `/api/auth/service-token` with mTLS or an `X-Internal-Token` header validated against Vault.
**Status:** fixed-in-commit-<pending> (2026-07-09) — Story 5.8. `InternalTokenAuthFilter` registered at `Ordered.HIGHEST_PRECEDENCE` via `FilterRegistrationBean` runs BEFORE Spring Security's denyAll chain. Validates `X-Internal-Token` header against `AUTH_INTERNAL_TOKEN` env (dev fallback `dev-internal-token-do-not-use-in-prod`, prod fails loud). AuthSecurityConfig narrowed to permitAll on register+login+actuator only. 6 InternalTokenAuthFilterTest cases green.

## [Epic 5 closeout] 2026-07-09 — JWT verifier bean absent (Story 5.4 / 5.5)

**Blocker:** JwtIssuer can sign but no consumer-side gate.
**Severity:** HIGH (no enforcement today; the only thing preventing forgery is HMAC_JWT_SECRET secrecy)
**Surface:** `services/auth/.../infrastructure/security/JwtIssuer.java` (missing pair)
**Proposed fix:** Add a `JwtVerifier` bean (HMAC + `iss` + `exp` checks) parallel to `JwtIssuer`. Consumers across services should inject it for `@PreAuthorize`-style gating.
**Status:** fixed-in-commit-<pending> (2026-07-09) — Story 5.8. `JwtVerifier` bean (HMAC via `HmacEventSigner.verify`, `iss=auth`, `exp > now(clock)`). 5 JwtVerifierTest cases green. Test-seam constructor for unit-test shared-secret injection.

## [Epic 5 closeout] 2026-07-09 — Email enumeration via 401/423/409 differentiation (Story 5.4)

**Blocker:** Trivial mass-enumeration of registered emails.
**Severity:** MEDIUM (defense in depth)
**Surface:** `services/auth/.../application/web/AuthController.java:34-67`
**Proposed fix:** Return identical status + body for unknown-email vs bad-password login; same for register (always return 200 with a generic "check your email" message).
**Status:** fixed-in-commit-<pending> (2026-07-09) — Story 5.8. AuthController.register always returns 200 + `{status:"registration_submitted", message:"..."}`. AuthController.login collapses unknown-email + bad-password + account-locked into identical 401 + `{error:"invalid_credentials"}`. Account-lock detail still recorded via Micrometer `security.account.locked` counter for ops visibility. 5 AuthControllerTest cases green.

## [Epic 5 closeout] 2026-07-09 — `users.role` default `'user'` vs enum `USER` case mismatch (Story 5.4)

**Blocker:** Direct SQL writes will silently corrupt role values.
**Severity:** MEDIUM (latent — Hibernate path always writes 'USER'; direct SQL is the exposure)
**Surface:** `services/auth/src/main/resources/db/migration/auth/V001__create_users_table.sql:10`
**Proposed fix:** Add `CHECK (role IN ('USER','STAFF','ADMIN'))` to the table; drop the `'user'` default (any non-JPA write must specify role explicitly).
**Status:** fixed-in-commit-<pending> (2026-07-09) — Story 5.8. V002 migration: `ALTER TABLE users ALTER COLUMN role DROP DEFAULT; ALTER TABLE users ADD CONSTRAINT users_role_check CHECK (role IN ('USER','STAFF','ADMIN'));`. Belt-and-braces with the `@Enumerated(EnumType.STRING)` enum.

## [Epic 5 closeout] 2026-07-09 — OrderPriceSnapshot has no userId column (Story 5.6)

**Blocker:** Loyalty accrual from the saga needs a userId→customerId mapping; the snapshot has neither. The current saga advancer calls `accrueLoyaltyPointsUseCase.execute(orderUuid, 0L, totalCents)` as a placeholder (no-op accrual). The accrual endpoint remains the v1 entry point.
**Severity:** HIGH (Story 5.6 AC #1 unmet: loyalty should fire on PAID)
**Surface:** `services/order/.../infrastructure/entity/OrderPriceSnapshot.java` + V001 migration (genesis PLACED path)
**Proposed fix:** Add `user_id BIGINT` column to V001 + capture userId on order placement. The saga advancer then resolves customerId via a local `user→customer` table or a customer-service HTTP lookup. Drop the `customerId=0` placeholder once the lookup lands.
**Status:** partially-fixed (saga wired; accrual is a no-op until snapshot.userId lands) — Epic 5 closure

## [Epic 5 closeout] 2026-07-09 — Loyalty race: missing @Version on LoyaltyAccountEntity + accrual endpoint trusts caller (Story 5.6)

**Blocker:** Two concurrent accruals race on the read-modify-write of `points`. Debug endpoint accepts arbitrary `customerId` + `totalCents` with no auth.
**Severity:** HIGH (correctness + integrity)
**Surface:** `services/order/.../infrastructure/entity/LoyaltyAccountEntity.java` (no @Version); `services/order/.../application/web/OrderController.java:157-166` (`accrue-loyalty` endpoint)
**Proposed fix:** Add `@Version` to `LoyaltyAccountEntity` OR switch accrual to atomic SQL (`UPDATE loyalty_account SET points = points + ? WHERE customer_id = ?`). For the endpoint, either remove it (saga is the only entry once snapshot.userId lands) or guard with `@PreAuthorize("hasRole('INTERNAL')")` + read `totalCents` from the order's price snapshot (not the request).
**Status:** fixed-in-commit-<pending> (2026-07-09) — Story 5.9 (Move B). V006 migration adds `version BIGINT NOT NULL DEFAULT 0` to loyalty_account. @Version on LoyaltyAccountEntity. AccrueLoyaltyPointsUseCase catches ObjectOptimisticLockingFailureException and retries once. POST /api/orders/{orderUuid}/accrue-loyalty now sources totalCents from OrderPriceSnapshot (FR-31 immutability) — unknown orders → 404. Saga path (PaymentCapturedOrderAdvancer) unaffected (use case signature unchanged). 66/66 order tests green (was 61, +5).

## [Epic 5 closeout] 2026-07-09 — LoyaltyAccrualEntity.points INT column caps ~21M points per order (Story 5.6)

**Blocker:** 32-bit int overflow on large orders.
**Severity:** LOW (theoretical; realistic order totals are < 10M VND)
**Surface:** `services/order/.../infrastructure/entity/LoyaltyAccrualEntity.java:39` + `V004__create_loyalty_tables.sql`
**Proposed fix:** Change column to BIGINT in a V005 migration. Update `AccrueLoyaltyPointsUseCase` to compute `points` as `long` instead of `(int)`.
**Status:** open — Epic 5 closure

## [Epic 5 closeout] 2026-07-09 — Loyalty: missing AC #2 `pointsApplied` field (Story 5.6)

**Blocker:** Story AC #2 requires `AppendOrderTransitionCommand.pointsApplied` so order effective total = totalCents - pointsApplied. Currently absent.
**Severity:** MEDIUM (AC gap; redemption flow not implemented)
**Surface:** `services/order/.../application/port/AppendOrderTransitionCommand.java:10-26`
**Proposed fix:** Add `pointsApplied` field; wire to a future `RedeemLoyaltyPointsUseCase`; update `OrderPriceSnapshot` to capture the applied discount.
**Status:** open — Epic 5 closure

## [Epic 5 closeout] 2026-07-09 — Pricebook.load() swallows exceptions (Story 5.7)

**Blocker:** A missing/malformed `pricebook.json` makes the service boot with zero entries; every GET returns 404 with no startup warning.
**Severity:** LOW (works today because the JSON is committed)
**Surface:** `services/pricing/.../domain/Pricebook.java:40-50`
**Proposed fix:** Throw on parse failure (fail-fast) instead of swallowing.
**Status:** open — Epic 5 closure

## [Epic 5 closeout] 2026-07-09 — Pricing has no controller-level test (Story 5.7)

**Blocker:** Smoke covers the HTTP contract but no automated unit check.
**Severity:** LOW (test coverage gap)
**Surface:** `services/pricing/src/test/java/.../PricebookTest.java` (domain-only)
**Proposed fix:** Add a `@WebMvcTest(PricingController.class)` test asserting 200/404 + JSON keys (`variantId`, `listPriceCents`, `currency`).
**Status:** open — Epic 5 closure

## [Epic 4 follow-up] 2026-07-09 — `OrderPortContractTest` removed; broader port-seam refactor deferred

**Blocker:** The ArchUnit test added at 3c96f80 (Epic 4 MEDIUM sweep) was over-strict — it banned `application.usecase.. → infrastructure.repository..` for the entire order module even though order's use cases use Spring Data repos directly (mockable via Mockito, which the tests do). Payment enforces ports because payment has external service deps; order's repos are abstractions already. The test was never actually run by the orchestrator (only `mvn validate` was, which checks pom structure).
**Severity:** MEDIUM (architectural debt; the rule was added at 3c96f80 but never enforced; 45 violations + 35 entity leaks existed at HEAD)
**Surface:** `services/order/src/test/java/vn/vnpt/order/OrderPortContractTest.java` (deleted in Epic 5 closeout)
**Proposed fix:** When order grows external service deps (e.g. cross-service customer lookup for loyalty), introduce proper `application/port/` interfaces for those — the strict rule will be useful then. The deleted test can be re-added with `@AllowExternalDependencies = "JpaRepository"` carve-out for now.
**Status:** open — reclassify as a refactor story

Smoke now passes end-to-end (port 8090, `/actuator/health` UP,
`/actuator/loggers` 404, variant-1 returns 200 + VND, variant-unknown 404).