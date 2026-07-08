---
baseline_commit: 6b6d952
---

# Story 4.2: OrderService — post-payment lifecycle (FR-32)

Status: review

## Story

As the saga,
I want OrderService to own allocation → packing → shipping → delivery,
so that the saga has 2 participants (Payment → Order) not 6.

## Acceptance Criteria

1. **Given** Story 4.1 bootstrapped `services/order/` with the append-only `order_state_transition` log + `OrderState` enum (`PLACED, PAID, ALLOCATED, PACKING, PACKED, SHIPPED, DELIVERED`) + `OrderTransitionValidator` + `AppendOrderTransitionUseCase` + the HMAC-signed outbox, **and** the saga is intra-process per ADR-12 (`architecture.md:221`), **When** Story 4.2 lands, **Then** order-side saga listeners consume `payment.captured` from `services/payment`'s outbox + advance the order state per the state machine. The listeners use Spring Modulith's `@ApplicationModuleListener` annotation (per the codebase pattern from Story 2.5's checkout-side orchestrator) and run inside the consuming transaction. The first transition to fire is `PLACED → PAID` on `payment.captured`; subsequent transitions `PAID → ALLOCATED → PACKING → PACKED` are exercised by Story 4.2's smoke (each via a separate `order.allocated` / `order.packing` / `order.packed` event).

2. **Given** the consumer-side HMAC verification deferred from Story 3.5 (`_bmad-output/backlog/deferred-issues.md` "Consumer-side HMAC verification"), **When** Story 4.2 lands, **Then** the order-side listeners call `HmacEventSigner.verify(canonicalJson, signature, secret)` on every received `payment.*` event and reject events with mismatched signatures. The reject path emits a `security.event.signature.mismatch` Micrometer counter + WARN log (alert message MUST NOT contain the secret or signature per R-15) + skips the state advance (the event stays in the outbox for manual replay; the order does NOT advance).

3. **Given** the `@ApplicationModuleListener` is the Spring Modulith idiom for cross-module in-process events (per Story 2.5's pattern at `services/checkout/.../infrastructure/saga/OrderSagaOrchestrator.java:24-26`), **When** Story 4.2 lands, **Then** a new `services/order/.../application/saga/PaymentCapturedOrderAdvancer.java` is the saga listener. It listens for `PaymentCapturedEvent` (a new event class the order service subscribes to — verified: the payment service already emits `payment.captured` in its outbox per `architecture.md:124` "FR-28. PaymentService emits `payment.captured`, `payment.refunded`, `payment.failed`, `payment.disputed` events"; the cross-module event record lives in the consumer's package as is the codebase pattern from `services/checkout/.../domain/event/CheckoutStartedEvent.java`). The listener calls `AppendOrderTransitionUseCase.execute(new AppendOrderTransitionCommand(orderUuid, OrderState.PAID, "payment.captured", snapshot))` — the snapshot is the EXISTING `OrderPriceSnapshot` for the order (FR-31 immutability: subsequent transitions keep the snapshot).

4. **Given** `OrderPriceSnapshotRepository` is INSERT-only at the use case layer (no setter for `total_cents`), **When** the saga advances `PLACED → PAID`, **Then** the existing snapshot is preserved (the use case's `if (fromState == null)` branch is false, so `priceSnapshotRepository.save(...)` is NOT called). The test asserts `order_price_snapshot` row count remains 1 after the second transition.

5. **Given** the saga is intra-process and the listener runs in the consuming transaction, **When** the listener fails (signature mismatch, illegal transition, DB error), **Then** the `@Transactional` boundary rolls back the in-process event delivery; the event stays in the publisher's outbox for replay. The order's `order_state_transition` log is unchanged. The Micrometer counter `order.saga.payment_captured.error` increments.

6. **Given** the order service has no inventory / packing / shipping logic yet (those land in Stories 4.5 / 4.6 / 5.x), **When** Story 4.2 lands, **Then** the state advances `PLACED → PAID → ALLOCATED → PACKING → PACKED` via four separate `order.*` events (the smoke fires each one in turn; the order's `order_state_transition` log ends with 5 rows). The `SHIPPED` and `DELIVERED` states are forward-compatible in the enum (Story 4.6 / 5.x wire the actual carrier integration). No real inventory or shipping calls — the smoke just exercises the state machine wiring.

7. **Given** the existing `OrderTransitionValidator` (`services/order/.../domain/OrderTransitionValidator.java`) is forward-compatible, **When** Story 4.2 lands, **Then** the validator's `ALLOWED` map covers the full state machine: `PLACED → PAID, PAID → ALLOCATED, ALLOCATED → PACKING, PACKING → PACKED` (the saga uses these; `SHIPPED` and `DELIVERED` are forward-compat for Story 4.6). A test asserts the validator rejects every other transition (e.g. `PLACED → ALLOCATED` is rejected — must go through `PAID`).

8. **Given** the runtime smoke rule (project memory `runtime-smoke-rule.md`), **When** Story 4.2 completes, **Then** the dev agent runs `bash dev/scripts/smoke-order-4-2.sh` which: (a) starts `services/order` with `SPRING_PROFILES_ACTIVE=dev` + `HMAC_SERVICE_SECRET_ORDER=<32-byte-hex>`; (b) waits for `/actuator/health` UP; (c) POSTs the genesis `PLACED` transition (Story 4.1 contract); (d) fires 4 in-process `payment.captured` / `order.allocated` / `order.packing` / `order.packed` events via a debug endpoint `POST /api/orders/{orderUuid}/advance` (registered this story for smoke testing only; the real consumer path is the `@ApplicationModuleListener`); (e) asserts the order has 5 transitions in the log + the outbox has 5 signed envelopes; (f) kills the process, exits 0.

9. **Given** the consumer-side HMAC verification is now required (FR-82), **When** Story 4.2 lands, **Then** a new `services/order/.../infrastructure/security/OrderHmacEventVerifier.java` reads the producer's secret from a per-service cache (mirrors `HmacServiceKeyProvider` — `secret/events/hmac/payment` for payment events; the cache TTL is 5 min per ADR-20). The verifier exposes `boolean verify(String canonicalJson, String signatureB64Url)` returning `MessageDigest.isEqual(...)` for constant-time compare (per `util/.../events/HmacEventSigner.java:23-25` constant-time contract).

10. **Given** the per-service log redaction from Story 3.3 (`util/.../logging/PanRedactingAppender`) is wired via `services/order/.../logback-spring.xml`, **When** the order service logs a security alert, **Then** the alert message MUST NOT contain the secret, the signature bytes, or the canonical JSON. The log line carries: alert name + producer service name + event_id (Snowflake) + the high-level reason. R-15 deny-list baseline.

### Out of scope (do NOT build — deferred, stated to prevent scope creep)

- **Real Saga integration with inventory / carrier / shipping** → Stories 4.5 / 4.6. Story 4.2 ships the data model + state machine + listener wiring; the real external integrations land in dedicated stories.
- **Saga timeout / auto-cancel of stuck `PAYMENT_PENDING` orders** → Epic 10. Not in scope.
- **`@ApplicationModuleListener` for `payment.refunded` / `payment.failed` / `payment.disputed`** → Future story. Story 4.2 ships only the `payment.captured` listener.
- **BFF-facing `GET /bff/storefront/order/{id}` with timeline + 30s cache** → Story 4.3. Story 4.2 ships the internal `GET /api/orders/{orderUuid}` (already in place from Story 4.1).
- **Cross-service HMAC key isolation via Vault** (per-service Vault paths) → ops concern; the dev profile uses `HMAC_SERVICE_SECRET_ORDER` + `HMAC_SERVICE_SECRET` env vars; the order service reads the payment service's secret via the same `HMAC_SERVICE_SECRET` env var (per-service Vault paths are a follow-up).
- **Saga compensator / rollback on signature mismatch** → Out of scope. The listener is fire-and-forget; failed events stay in the publisher's outbox for manual replay.

## Tasks / Subtasks

- [ ] **Task 1 — `PaymentCapturedEvent` cross-module event record** (AC: #3)
  - [ ] `services/order/.../application/saga/event/PaymentCapturedEvent.java` — `record PaymentCapturedEvent(long orderUuid, String paymentIntentId, long amountCents, String currency, LocalDateTime occurredAt)`. Lives in the order service's `application.saga.event` package (consumer-side; the producer is the payment service — verify the payment service emits a compatible record under a parallel name and adjust if needed).
  - [ ] `services/order/.../application/saga/event/OrderAllocatedEvent.java` + `OrderPackingEvent.java` + `OrderPackedEvent.java` — local events fired by the smoke endpoint to drive the state machine (forward-compat; real producers land with their respective stories).

- [ ] **Task 2 — `OrderHmacEventVerifier`** (AC: #9)
  - [ ] `services/order/.../infrastructure/security/OrderHmacEventVerifier.java` — `@Component` with constructor-injected `HmacServiceKeyProvider` (already in place from Story 4.1). Method `boolean verify(String canonicalJson, String signatureB64Url)` calls `HmacEventSigner.verify(...)`. Cache the resolved key for 5 min (per ADR-20).

- [ ] **Task 3 — `PaymentCapturedOrderAdvancer` listener** (AC: #3, #4, #5)
  - [ ] `services/order/.../application/saga/PaymentCapturedOrderAdvancer.java` — `@ApplicationModuleListener` on `PaymentCapturedEvent`. Loads the existing `OrderPriceSnapshot` via `priceSnapshotRepository.findById(event.orderUuid())` (throws `IllegalStateException` if missing — order was created elsewhere, this listener expects the price snapshot to be in place); verifies the event's `signatures` field via `OrderHmacEventVerifier`; calls `AppendOrderTransitionUseCase.execute(new AppendOrderTransitionCommand(orderUuid, OrderState.PAID, "payment.captured", existingSnapshot))`. The listener is `@Transactional` (in-process; the outbox bridge handles the cross-service fan-out via Spring Modulith's `EventPublicationRegistry`).

- [ ] **Task 4 — `AdvanceOrderStateUseCase` (smoke driver)** (AC: #6, #8)
  - [ ] `services/order/.../application/usecase/AdvanceOrderStateUseCase.java` — drives the saga past PLACED (PAUSED → PAID via the real listener; PAID → ALLOCATED → PACKING → PACKED via the smoke endpoint). The smoke endpoint `POST /api/orders/{orderUuid}/advance` calls this with `targetState=ALLOCATED` etc. and the saga appends the transition. The endpoint is registered in `OrderController` as `@PostMapping("/{orderUuid}/advance")`.

- [ ] **Task 5 — Extend `OrderTransitionValidator` for the full state machine** (AC: #7)
  - [ ] `services/order/.../domain/OrderTransitionValidator.java` — extend the `ALLOWED` map per AC #7.

- [ ] **Task 6 — Tests** (AC: #1, #3, #4, #5, #7, #9)
  - [ ] `services/order/src/test/java/vn/vnpt/order/domain/OrderTransitionValidatorTest.java` — 6 tests covering the full state machine: `PLACED_to_PAID_allowed`, `PAID_to_ALLOCATED_allowed`, `ALLOCATED_to_PACKING_allowed`, `PACKING_to_PACKED_allowed`, `PLACED_to_ALLOCATED_rejected_mustGoThroughPaid`, `PAID_to_PACKING_rejected_mustGoThroughAllocated`.
  - [ ] `services/order/src/test/java/vn/vnpt/order/application/saga/PaymentCapturedOrderAdvancerTest.java` — 4 tests: `handle_appendsPaidTransitionWhenSignatureValid`, `handle_skipsTransitionWhenSignatureInvalid`, `handle_throwsWhenSnapshotMissing`, `handle_skipsTransitionWhenOrderNotFound` (event for an order that was never placed).
  - [ ] `services/order/src/test/java/vn/vnpt/order/infrastructure/security/OrderHmacEventVerifierTest.java` — 3 tests: `verify_returnsTrueOnValidSignature`, `verify_returnsFalseOnTamperedCanonicalJson`, `verify_returnsFalseOnTamperedSignature`.

- [ ] **Task 7 — Runtime smoke script** (AC: #8)
  - [ ] `dev/scripts/smoke-order-4-2.sh` — bash. Mirrors `smoke-order-4-1.sh`:
        - (a) Free port 8087.
        - (b) Start `services/order` with `SPRING_PROFILES_ACTIVE=dev` + `HMAC_SERVICE_SECRET_ORDER=<32-byte-hex>`.
        - (c) Wait for `/actuator/health` UP (up to 90s).
        - (d) POST the genesis `PLACED` transition (Story 4.1 contract).
        - (e) Verify the in-process `payment.captured` event has been published by the saga orchestrator and consumed by the order service. The smoke doesn't need to assert the cross-service path explicitly (it requires both services running); instead, the smoke uses the `POST /api/orders/{orderUuid}/advance?targetState=ALLOCATED` debug endpoint to drive the rest of the state machine.
        - (f) Assert the order has 5 transitions in `order_state_transition` (PLACED, PAID, ALLOCATED, PACKING, PACKED).
        - (g) Verify the outbox has 5 signed envelopes.
        - (h) Kill the process, exit 0.

- [ ] **Task 8 — Micrometer counters** (AC: #2, #5)
  - [ ] `services/order/src/main/java/vn/vnpt/order/infrastructure/metrics/OrderMetrics.java` — `@Component` registers: `security.event.signature.mismatch` (counter, tagged `producer=payment`), `order.saga.payment_captured.error` (counter).

## Dev Notes

### Implementation Notes

- **The saga is intra-process per ADR-12** — payment's outbox bridge publishes events via Spring Modulith's `EventPublicationRegistry`; the order service consumes them via `@ApplicationModuleListener`. No Kafka bridge in v1 (that's Epic 10's CDC work).
- **The `payment.captured` event shape is canonical** — `architecture.md:124` row FR-28 specifies the events payment emits. The order-side `PaymentCapturedEvent` is the consumer-side mirror; the producer's exact record is in `services/payment/.../domain/event/` — verify and align field names if needed.
- **The `POST /api/orders/{orderUuid}/advance` debug endpoint is for smoke testing only** — it lets the smoke drive the state machine without spinning up a Kafka bridge or a real payment flow. The real consumer path is the `@ApplicationModuleListener`. Future stories may remove the debug endpoint when the real consumer path is wired end-to-end (Kafka bridge in Epic 10).
- **The HMAC signature verification uses the same env var as Story 3.5** (`HMAC_SERVICE_SECRET` for the payment service, `HMAC_SERVICE_SECRET_ORDER` for the order service). Cross-service verification requires both services to share the secret key OR use the same Vault path. v1 uses a shared `HMAC_SERVICE_SECRET` env var (the order service reads it directly via a side-loaded provider). Per-service Vault paths land with Story 5.x ops hardening.
- **`OrderTransitionValidator` is forward-compatible** — the `ALLOWED` map covers the full state machine; Story 4.2 extends the test cases.
- **The `OrderHmacEventVerifier` cache TTL is 5 min** per ADR-20. The key is read from `HmacServiceKeyProvider.currentSecret()` (already in place from Story 4.1) — but Story 4.2 needs to read the PAYMENT service's secret, not the order's. v1: the order service reads the payment service's secret via a new `PaymentServiceKeyProvider` (mirror of `HmacServiceKeyProvider` but reading the `payment` service's secret). Future story: cross-service Vault path isolation.
- **Test counts target** — `≥ 13 new tests` (validator 6 + advancer 4 + verifier 3). Order service baseline after Story 4.1: 10 tests; target after Story 4.2: ≥ 23.

### Debug Log References

(none — fresh story; debugs land in Dev Notes / Completion Notes after the dev agent runs)

### Completion Notes List

(empty — populated by dev-story)

### Deviations from literal story text (flagged for review)

(none — fresh story; deviations land here after dev-story)

## Dev Notes (post-dev-story section — populated after implementation)

### Project Structure Notes

- **Path placement** (per architecture §6):
  - `services/order/src/main/java/vn/vnpt/order/application/saga/event/PaymentCapturedEvent.java` + `OrderAllocatedEvent.java` + `OrderPackingEvent.java` + `OrderPackedEvent.java` ← new (Task 1)
  - `services/order/src/main/java/vn/vnpt/order/infrastructure/security/OrderHmacEventVerifier.java` ← new (Task 2)
  - `services/order/src/main/java/vn/vnpt/order/application/saga/PaymentCapturedOrderAdvancer.java` ← new (Task 3)
  - `services/order/src/main/java/vn/vnpt/order/application/usecase/AdvanceOrderStateUseCase.java` ← new (Task 4)
  - `services/order/src/main/java/vn/vnpt/order/domain/OrderTransitionValidator.java` ← modify (Task 5)
  - `services/order/src/main/java/vn/vnpt/order/infrastructure/metrics/OrderMetrics.java` ← new (Task 8)
  - `services/order/src/main/java/vn/vnpt/order/application/web/OrderController.java` ← extend with `/advance` debug endpoint
  - `services/order/src/test/java/vn/vnpt/order/...` ← new tests (Task 6)
  - `dev/scripts/smoke-order-4-2.sh` ← new (Task 7)

- **Detected conflicts / variances (with rationale):**
  - **Cross-service HMAC key isolation is not enforced in v1** — the order service reads the payment service's secret from a shared env var. Per-service Vault paths land in Story 5.x.
  - **The `POST /api/orders/{orderUuid}/advance` debug endpoint is smoke-only** — the real consumer path is the `@ApplicationModuleListener`. Future stories may remove the debug endpoint when Kafka bridge lands.
  - **`OrderHmacEventVerifier` cache TTL is 5 min** — matches ADR-20.

### References

- [Source: `_bmad-output/planning-artifacts/epics.md:720-731` — Story 4.2 AC]
- [Source: `_bmad-output/planning-artifacts/prd.md:124` — FR-28: "PaymentService emits `payment.captured`, `payment.refunded`, `payment.failed`, `payment.disputed` events"]
- [Source: `_bmad-output/planning-artifacts/architecture.md:221` — ADR-12: "Saga = single Modulith module; saga is intra-process, NOT network"]
- [Source: `_bmad-output/planning-artifacts/architecture.md:299` — "Order state transition log: id, order_uuid, from_state, to_state, saga_step, event_id, created_at"]
- [Source: `_bmad-output/planning-artifacts/architecture.md:675` — "Saga = intra-process state machine on aggregate | ADR-12"]
- [Source: `services/order/.../infrastructure/outbox/OrderModulithOutboxPublisher.java` — Story 4.1 producer (HMAC-signed outbox)]
- [Source: `services/checkout/.../application/saga/OrderSagaOrchestrator.java` — Story 2.5 saga pattern (mirror for order)]
- [Source: `services/order/.../domain/OrderTransitionValidator.java` — Story 4.1 validator (extend in Task 5)]
- [Source: `services/order/.../application/usecase/AppendOrderTransitionUseCase.java` — Story 4.1 use case (consume from listener)]
- [Source: `util/.../events/HmacEventSigner.java` — `verify(canonicalJson, signatureB64Url, secret)` (constant-time compare)]
- [Source: `util/.../events/JcsCanonicalJson.java` — RFC 8785 canonical JSON (reused for verification)]
- [Source: `services/payment/.../domain/event/` — verify the producer-side PaymentCapturedEvent shape
- [Source: project memory `runtime-smoke-rule.md` — every dev-story must runtime-smoke
- [Source: project memory `deep-review-rules.md` — F1: shared code in util/, 1-sentence javadoc, no single-impl abstractions

## Dev Agent Record

### Agent Model Used

{{agent_model_name_version}}

### Debug Log References

### Completion Notes List

### File List