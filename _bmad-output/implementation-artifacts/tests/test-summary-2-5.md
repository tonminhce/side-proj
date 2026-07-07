# Test Automation Summary — Story 2.5 (FR-22 + FR-23)

## Scope

Saga orchestrator (Spring Modulith outbox + intra-process state machine) on the `Order` aggregate,
crash-recovery runner, intra-Modulith inventory port adapter, `GET /api/orders/by-checkout/{uuid}`
polling endpoint, and ADR-20 producer HMAC over JCS.

## Generated / Auto-Applied Tests

### Pre-existing tests (Story 2.5 dev-story commit)

| Class | Tests | Coverage |
|---|---|---|
| `domain/OrderTest` | 12 | FSM allowed-matrix (parameterized) + self-loop + skip-state + terminal-blocked + failure-reason + isInFlight/isTerminal helpers |
| `domain/OrderStateTransitionAppendOnlyTest` | 1 | DB-level UNIQUE on `event_id`; repository exposes only append |
| `application/saga/OrderSagaOrchestratorTest` | 3 (baseline) | Happy-path (3 transitions + 3 outbox publishes), duplicate-delivery no-op, insufficient-stock failure branch |
| `application/saga/OrderSagaRecoveryRunnerTest` | 5 | Stuck CREATED / STOCK_RESERVED / PAYMENT_PENDING orders are resumed; terminal/PAID orders are ignored; empty list is no-op |
| `api/OrderControllerTest` | 3 | 200 happy-path, 404 missing, 400 non-numeric uuid (Testcontainers + MockMvc) |
| `CheckoutEventOutboxE2ETest` (extended) | 2 | E2E over HTTP asserts 1 `checkout.started` + 3 `order.*` outbox rows + 3 transition-log rows; HMAC non-blank |
| `StartCheckoutUseCaseAtomicityTest` (extended) | 3 | stripe-throws rolls back checkout + saga (no order/transition rows) |

### Gap-fill tests (auto-applied by QA workflow)

| Class | Tests | AC gap closed |
|---|---|---|
| `OrderSagaOrchestratorTest` (extended) | +6 | AC #3 paymentIntentId denormalized; AC #2 idempotency key tuple `orderUuid + ":stock.reserve"`; handle null-check guard; AC #5 resumeFromCreated happy-path; resumeFromCreated skip-when-past-CREATED; resumeFromStockReserved; resumeFromPaymentPending no-op |
| `infrastructure/outbox/OrderEventPublisherTest` (new) | 6 | AC #7 HMAC verified over JCS-canonical payload (not just non-blank); topic strings distinct; payload shape for all 4 events |
| `infrastructure/inventory/RegionResolverTest` (new) | 7 | Province → Region mapping; null address / null province / unknown province → null |
| `infrastructure/inventory/JavaDirectInventoryReservationAdapterTest` (new) | 5 | InsufficientStockException → InsufficientStockDomainException wrapping (AC #6 boundary); idempotency-key passthrough; empty/null cartLines fails closed; unresolvable region fails closed |

## Coverage by Acceptance Criterion

| AC | Test class(es) |
|---|---|
| #1 — `Order` INSERT + transition row + `OrderCreatedEvent` outbox, all in same tx | `OrderSagaOrchestratorTest.happyPath_*` + `CheckoutEventOutboxE2ETest.startCheckout_overHttp_emitsCheckoutStartedRowInOutbox` |
| #2 — `InventoryReservationPort.reserve(orderUuid, cartLines, idemKey)` + transition + `OrderStockReservedEvent` | `OrderSagaOrchestratorTest.happyPath_*` + `JavaDirectInventoryReservationAdapterTest.reserve_successful_returnsResultAndPassesIdempotencyKeyUnchanged` + `OrderEventPublisherTest.publishOrderStockReserved_includesReservationUuidAndTopic` |
| #3 — `paymentIntentId` denormalized + `PAYMENT_PENDING` + `OrderPaymentPendingEvent` | `OrderSagaOrchestratorTest.happyPath_copiesPaymentIntentIdFromEventOntoOrder` + `OrderEventPublisherTest.publishOrderPaymentPending_carriesPaymentIntentId` |
| #4 — `@Version` race-condition rollback | **Not directly tested** — `@Version` is Spring Data JPA framework-tested code. The wiring is verified by `domain/Order.java`'s `@Version` annotation + the `@PostPersist/@PostLoad` `isNewFlag` lifecycle (mirroring `Checkout`). Ponytail: skip speculative parallel-execution Testcontainer for marginal value |
| #5 — Recovery runner replays stuck in-flight orders | `OrderSagaRecoveryRunnerTest` (5 tests) + `OrderSagaOrchestratorTest.resumeFrom*` (4 tests) |
| #6 — InsufficientStockException → FAILED branch + `OrderFailedEvent` + GET endpoint 404 | `OrderSagaOrchestratorTest.insufficientStock_marksOrderFailed_emitsFailedEvent` + `JavaDirectInventoryReservationAdapterTest.reserve_inventoryThrowsInsufficientStock_wrappedAsInsufficientStockDomainException` + `OrderControllerTest.findByCheckout_returns404WhenOrderMissing` + `OrderEventPublisherTest.publishOrderFailed_carriesReasonAndSagaStep` |
| #7 — Producer HMAC over JCS-canonical payload | `OrderEventPublisherTest.publishOrderCreated_hmacVerifiesOverJcsCanonicalPayload` (verifies the math, not just non-blank) + `CheckoutEventOutboxE2ETest` (HMAC non-blank on every row) |
| #8 — 44+ tests green, 18 modules | **69 tests** in checkout (baseline 44 + 25 gap-fill). Pre-existing failure `CheckoutControllerTest.postStart_invalidRequest_returns400` is c1b9827 fallout (handleValidation moved to util; not Story 2.5 scope — documented in dev notes) |
| #9 — Saga intra-JVM sync, before-commit, same tx as producer | `CheckoutEventOutboxE2ETest.startCheckout_overHttp_emitsCheckoutStartedRowInOutbox` (asserts 3 order.* rows + 3 transition rows visible after a single HTTP call) |

## Run Results

```
mvn test -Dtest='OrderTest,OrderStateTransitionAppendOnlyTest,
  OrderSagaOrchestratorTest,OrderSagaRecoveryRunnerTest,OrderControllerTest,
  CheckoutEventOutboxE2ETest,StartCheckoutUseCaseAtomicityTest,
  CheckoutPackageBoundaryTest,CheckoutControllerExceptionHandlerTest,
  CheckoutControllerTest,CheckoutApplicationContextTest,
  OrderEventPublisherTest,RegionResolverTest,
  JavaDirectInventoryReservationAdapterTest'
```

```
Tests run: 69, Failures: 1, Errors: 0, Skipped: 0
```

The 1 failure is `CheckoutControllerTest.postStart_invalidRequest_returns400` — pre-existing
`CheckoutControllerExceptionHandler` bug from c1b9827 (handleValidation moved to
`util.web.RestExceptionHandler`; `application-test.yml` excludes `UtilsAutoConfiguration` so the
util handler isn't loaded for tests). Per the story's dev notes, this is **not** Story 2.5 scope
and will be fixed in a separate cleanup PR.

## Next Steps

- Fix the pre-existing `CheckoutControllerExceptionHandler` test fallout (re-include `util.web.RestExceptionHandler` in `application-test.yml` or restore the local handler methods). Out of scope for Story 2.5.
- Epic 3 + Epic 4 add terminal-state tests (PAID / CANCELLED / EXPIRED transitions land in their stories).
- Story 10.1 (Grafana dashboards) consumes the `saga.transition.count` and `saga.stuck_orders.processed` Micrometer counters emitted by this story's code.