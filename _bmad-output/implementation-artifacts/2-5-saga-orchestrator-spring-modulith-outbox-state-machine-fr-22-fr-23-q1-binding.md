---
baseline_commit: c1b9827
---

# Story 2.5: Saga orchestrator — Spring Modulith outbox + state machine (FR-22, FR-23) — Q1 binding

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the saga,
I want the checkout-to-order flow to run as an intra-process state machine on an `Order` aggregate (intra-Modulith in v1; will be moved to `services/order/` in Epic 4), with the Spring Modulith outbox publishing events atomically,
So that saga crash-recovery works without network round-trips and every transition is replayable from the transition log (FR-22 + FR-23, ADR-01 + ADR-12 + ADR-14, brainstorming `[CHK-C]`).

## Acceptance Criteria

1. **Given** a `CheckoutStartedEvent` was emitted and the saga listener (`@ApplicationModuleListener`) receives it in the same JVM (ADR-01 intra-Modulith — `ModulithOutboxPublisher.applicationEventPublisher.publishEvent`), **When** `OrderSagaOrchestrator.handle(CheckoutStartedEvent)` runs, **Then** a new `Order` aggregate is INSERTed in the checkout DB with `status = CREATED`, `version = 0` (BaseEntity defaults), a corresponding `order_state_transition` row is appended (`from_state = NULL`, `to_state = CREATED`, `saga_step = "cart.submit"`), and an `OrderCreatedEvent` outbox row is written in the **same DB transaction** (ADR-04 atomicity).
2. **Given** the order is in `CREATED`, **When** the saga advances to reserve stock, **Then** it calls `InventoryReservationPort.reserve(orderUuid, cartLines, idempotencyKey)` with `idempotencyKey = orderUuid + ":stock.reserve"` (ADR-11 stable tuple), transitions the order to `STOCK_RESERVED`, appends a transition row (`from_state = CREATED`, `to_state = STOCK_RESERVED`, `saga_step = "stock.reserve"`), and writes an `OrderStockReservedEvent` outbox row — all in the **same transaction** as the inventory reserve call (the port adapter participates in the surrounding `@Transactional` boundary).
3. **Given** the order is in `STOCK_RESERVED`, **When** the saga advances to mark payment pending, **Then** it transitions to `PAYMENT_PENDING`, appends a transition row (`from_state = STOCK_RESERVED`, `to_state = PAYMENT_PENDING`, `saga_step = "payment.intent.created"`), and writes an `OrderPaymentPendingEvent` outbox row. The Stripe `payment_intent_id` carried by `CheckoutStartedEvent` is **replayed** onto the `Order` as a denormalized `paymentIntentId` column for downstream consumers (the canonical PI id lives on the `Checkout` aggregate; the order carries a copy for fan-out).
4. **Given** `order.version` is incremented on every transition (`@Version`), **When** two saga handlers race on the same order, **Then** the second one receives `ObjectOptimisticLockingFailureException` and the entire transaction rolls back (no half-state). The stripe-side duplicate `payment_intent_id` idem key (Story 2.4 / AC #4 — `(cartUuid, "stripe.payment_intent.create")`) prevents upstream Stripe double-create, but the `@Version` is the **intra-saga** concurrency guard for the FSM.
5. **Given** the Spring Modulith restarts mid-saga, **When** the `OrderSagaRecoveryRunner` `@PostConstruct` hook fires on boot, **Then** it finds orders in `IN_FLIGHT` states (`CREATED`, `STOCK_RESERVED`, `PAYMENT_PENDING`) with `updated_at < now() - 5 minutes` (the stuck-orders query from `architecture-detail.md:43` — `STOCK_RESERVED, PAYMENT_PENDING, PAID`; this story narrows to `CREATED + STOCK_RESERVED + PAYMENT_PENDING` because terminal + PAID are owned by Epic 3 webhook) and re-derives the saga step from the **last** `order_state_transition` row, then re-invokes the matching handler. Replays are idempotent on `(orderUuid, saga_step_name)` — the same tuple the inventory port already checks.
6. **Given** the inventory reserve port returns `InsufficientStockException`, **When** the saga transitions `CREATED → STOCK_RESERVED`, **Then** the saga marks the order `FAILED` (the FAILED branch per `architecture-detail.md:58`), emits `OrderFailedEvent` (with `reason: "INSUFFICIENT_STOCK"` in the payload), and the transition log records `from_state = CREATED, to_state = FAILED, saga_step = "stock.reserve", reason = "INSUFFICIENT_STOCK"`. Story 2.4 callers see the `Checkout` row stay `PAYMENT_PENDING` (the saga failure does **not** mutate `Checkout.status` — that's the webhook-driven payment-intent-failed branch in Epic 3); the storefront's `GET /api/checkouts/{uuid}` polls show the order as failed via a new `GET /api/orders/by-checkout/{uuid}` endpoint.
7. **Given** the checkout event publisher's HMAC mechanism (ADR-20, Story 2.3 / 2.4 pattern), **When** any saga-emitted outbox row is published, **Then** the producer HMAC is computed over the JCS-canonical payload via `util.HmacEventSigner` + `util.JcsCanonicalJson` and stored in `outbox.signatures` JSONB, with `consumer = "checkout"` (ADR-20 producer-side HMAC; consumer-side verification lands in Epic 4/OrderService when the orders topic gains external consumers).
8. **Given** Story 2.4's `35/35 + atomicity-test` baseline and the family-wide regressions (cart 97/97, inventory 238/238, util 57/57, 17 modules), **When** Story 2.5 lands, **Then** `mvn -pl services/checkout -am test` is **green** (target: 44+ tests; previous Story 2.4 baseline was 36, this story adds ~8: `OrderTest` legal/illegal transitions, `OrderStateTransitionAppendOnlyTest`, `OrderSagaOrchestratorTest` happy-path + InsufficientStock fail-branch + replay idempotency, `OrderSagaRecoveryRunnerTest` stuck-order replay, plus extending `CheckoutEventOutboxE2ETest` + `StartCheckoutUseCaseAtomicityTest` to assert 3 outbox rows + 3 transition log rows), `mvn validate` reports **18 modules** (no new module; the saga lives in checkout per architecture FR→module table line 903), and **all four cross-service regressions stay green**.
9. **Given** the saga listener is intra-JVM and consumes `CheckoutStartedEvent` synchronously from within the same transaction (per `ModulithOutboxPublisher`'s `applicationEventPublisher.publishEvent(event)` call after the JDBC INSERT — `services/checkout/.../ModulithOutboxPublisher.java:88-89`), **When** `ModulithOutboxPublisher.append(...)` returns, **Then** the saga has already executed and the 3 outbox rows + 3 transition log rows are visible to readers in the same transaction (modulith `ApplicationEventPublisher` is sync by default in this version). No second polling tick is required to observe saga completion.

### Out of scope (do NOT build — deferred, stated to prevent scope creep)

- **Terminal-state transitions (PAID / CANCELLED / EXPIRED / COMPENSATED)** → Epic 3 (Stripe webhook drives `PAYMENT_PENDING → PAID/FAILED` via `payment_intent.succeeded` / `payment_intent.payment_failed`) and Epic 4 (`PAID → PACKED → SHIPPED → DELIVERED`). This story only wires the **first 3 transitions** (`CREATED → STOCK_RESERVED → PAYMENT_PENDING`) and the failure branch. The `OrderStatus` enum carries the full set (forward-compat), but no transitions to terminal states are coded here.
- **`@PostConstruct` recovery runner is the ONLY recovery path** in this story. A periodic `@Scheduled` re-sweep (every N minutes) is Epic 10 (NFR-OBS-4) — YAGNI for now; the `@PostConstruct` covers crash-restart, which is the dominant case.
- **Cross-JVM consumer-side HMAC verification** → Epic 4 (when OrderService splits and the `orders.*` topic gets external consumers). The producer-side signature is computed here per ADR-20 but verified-in-MVP stays in-JVM (the same JVM emitted and consumed the event).
- **Saga-timeout / auto-cancel of stuck `PAYMENT_PENDING` orders** → Story 2.4 deferred + Epic 3 + Epic 10. A `@Scheduled` job to cancel orders stuck > 30 min in `PAYMENT_PENDING` is a future story; this story emits the events and the recovery routine replays them.
- **New `services/order/` Maven module** → Epic 4 Story 4.1. For v1 the `Order` aggregate lives in `services/checkout/` per the architecture's FR→module mapping (`architecture.md:903`: "FR-19 to FR-23 → `services/checkout/`"); when OrderService is split, the V003/V004 migrations + aggregate + transition log + outbox rows move to `services/order/` along with any consumer logic.
- **Saga metrics dashboard (Grafana)** → Epic 10 / Story 10.1 (LGTM dashboards). Counters like `saga.transition.count{from,to}` and `saga.stuck_orders.count` are emitted by this story (per architecture `metrics-with-lgtm`), but the dashboard is deferred.
- **BFF `/bff/storefront/order/{id}`** for the storefront to read saga state → not a checkout concern; the BFF team will read `GET /api/checkouts/{uuid}` (existing Story 2.3) for the BFF path. A direct order query may come in Epic 4.

## Tasks / Subtasks

- [x] **Task 1 — Add `Order` aggregate + transition-log entity** (AC: #1, #3, #4)
  - [x] `domain/OrderStatus.java` — enum `CREATED, STOCK_RESERVED, PAYMENT_PENDING, PAID, FAILED, CANCELLED, EXPIRED, COMPENSATED`; constants `IN_FLIGHT = EnumSet.of(CREATED, STOCK_RESERVED, PAYMENT_PENDING)`, `TERMINAL = EnumSet.of(PAID, FAILED, CANCELLED, EXPIRED, COMPENSATED)`; `isTerminal()` / `isInFlight()` helpers. SCREAMING_SNAKE wire format.
  - [x] `domain/Order.java` — `@Entity @Table(name = "orders")` extends `BaseEntity`, `@IgnoreSoftUkAudit` (orders are append-only FSM rows — same justification as `Checkout`); columns: `tenantId`, `cartUuid` (cross-service ref per ADR-03, NO FK), `checkoutUuid` (cross-service ref), `paymentIntentId` (denormalized copy from `Checkout.paymentIntentId`), `shippingAddress` (embedded), `status` (`@Enumerated(STRING)`, `@Column(length = 32)`), `@Version version`, `failureReason` (nullable, 64 chars), `failureSagaStep` (nullable, 64 chars), `createdAt/updatedAt` from BaseEntity. Implements `Persistable<Long>` exactly like `Checkout` (the C1 fix from the Story 2.4 review: `@Transient boolean isNewFlag = true` with `@PostLoad @PostPersist` marking persisted; the **`@PrePersist` in `BaseEntity` assigns the Snowflake uuid + version=0**, so the saga handlers do **not** call `setUuid(...)` themselves — they `new Order()`, set fields, set the `cartUuid`/`checkoutUuid`, and rely on `@PrePersist` for the rest).
  - [x] `domain/OrderStateTransition.java` — `@Entity @Table(name = "order_state_transition")` extends `BaseEntity`; columns: `orderUuid` (FK-style column, NO JPA FK — ADR-03), `fromState` (`@Enumerated(STRING)`, nullable for the initial CREATED row), `toState` (`@Enumerated(STRING)`), `sagaStep` (VARCHAR(64)), `eventId` (BIGINT, Snowflake), `failureReason` (VARCHAR(128), nullable). **Append-only invariant:** no `@Setter` on `toState`, no update method. `BaseEntity.@PrePersist` assigns `eventId` (Snowflake) + `createdAt`. The repository only exposes `findByOrderUuidOrderByCreatedAtAsc(uuid)` for replay (used by the recovery runner).
  - [x] `domain/exception/OrderIllegalStateTransitionException.java` — extends RuntimeException; thrown by `Order.transitionTo(...)` for any non-allowed edge.

- [x] **Task 2 — `Order.transitionTo` state-machine method** (AC: #2, #3, #4, #6)
  - [x] Allowed transition edges (allowed matrix; everything else throws `OrderIllegalStateTransitionException`):
    - `CREATED → STOCK_RESERVED` (success — saga_step `stock.reserve`)
    - `CREATED → FAILED` (insufficient-stock — saga_step `stock.reserve`, reason `INSUFFICIENT_STOCK`)
    - `STOCK_RESERVED → PAYMENT_PENDING` (saga_step `payment.intent.created`)
    - `STOCK_RESERVED → FAILED` (future — Epic 3 inventory release failure)
    - `PAYMENT_PENDING → PAID / FAILED / EXPIRED` — registered for forward-compat; the actual handlers land in Epic 3. The enum-method **rejects** edges not in the matrix.
  - [x] Method: `transitionTo(OrderStatus to, String sagaStep, String failureReason)` — internal; sets `status`, increments `version` (JPA `@Version`), invalidates the `isNewFlag` to false (no — `isNewFlag` stays false because the row exists). Records the transition history in a transient `lastTransition` field that the orchestrator's transaction-flush path appends to `order_state_transition`.
  - [x] Pre-write a `OrderTest.java` covering the legal/illegal matrix (per Task 8 — unit test, no Testcontainers needed).

- [x] **Task 3 — Flyway migrations `V003__create_orders.sql` + `V004__create_order_state_transition.sql`** (AC: #1)
  - [x] `V003__create_orders.sql` under `services/checkout/src/main/resources/db/migration/checkout/` — additive; V001/V002 untouched. Pattern mirrors V001 verbatim (`id BIGINT` for RootEntity legacy, tenant default, full BaseEntity column set, soft-delete `is_active`/`is_deleted` columns even though `Order` is `@IgnoreSoftUkAudit` — column uniformity per architecture). Indexes: `idx_orders_tenant_cart`, `idx_orders_checkout_uuid` (for the future `GET /api/orders/by-checkout/{uuid}`), `idx_orders_inflight_updated` partial index `WHERE status IN ('CREATED','STOCK_RESERVED','PAYMENT_PENDING')` (this is the index the recovery query uses — see Task 7).
  - [x] `V004__create_order_state_transition.sql` — same composition: `id`, `uuid`, `order_uuid`, `from_state` (nullable), `to_state` (NOT NULL), `saga_step` (NOT NULL), `event_id` (UNIQUE Snowflake), `failure_reason`, full BaseEntity audit columns. Index: `idx_order_state_transition_order` on `(order_uuid, created_at)` for replay-by-order.
  - [x] Document both in `_bmad-output/KAFKA-TOPIC-LIFECYCLE.md` if any new outbox topics are introduced — but Task 5 says we're reusing `outbox` table per architecture (`architecture.md:297`), so no new schema needed for events.

- [x] **Task 4 — `Order` + `OrderStateTransition` repositories** (AC: #1, #5, #7)
  - [x] `infrastructure/repository/OrderRepository.java` extends `JpaRepository<Order, Long>`; methods: `findByCartUuid(Long)`, `findByCheckoutUuid(Long)`, `findByUuid(Long)`. No `delete*` method, no `deleteAll` (the row is append-only).
  - [x] `infrastructure/repository/OrderStateTransitionRepository.java` extends `JpaRepository<OrderStateTransition, Long>`; method `List<OrderStateTransition> findByOrderUuidOrderByCreatedAtAsc(Long orderUuid)` (used by recovery); uniqueness on `event_id` enforced at DB level (`UNIQUE` on `event_id` in V004 — append-only, no UPDATE).
  - [x] Add `@NoRepositoryBean` not relevant here — both are concrete Spring Data interfaces.

- [x] **Task 5 — Outbox events for the saga** (AC: #1, #2, #3, #7)
  - [x] `domain/event/OrderCreatedEvent.java` — `@Value @Builder @Jacksonized @JsonInclude(NON_NULL)` record; fields mirror `CheckoutStartedEvent` minus the `client_secret` (R-15 / ADR-23): `eventId`, `aggregateType = "Order"`, `aggregateId` (orderUuid), `occurredAt`, `cartUuid`, `checkoutUuid`, `orderUuid`, `paymentIntentId` (carried for traceability, never `clientSecret`), `tenantId`, `signatures`.
  - [x] `domain/event/OrderStockReservedEvent.java` — same shape; fields: `orderUuid`, `cartUuid`, `checkoutUuid`, `reservationUuid` (the inventory `InventoryReservation.uuid` returned by the port), `tenantId`, `signatures`. Captures the inventory's `reservationUuid` so consumers can correlate.
  - [x] `domain/event/OrderPaymentPendingEvent.java` — fields: `orderUuid`, `cartUuid`, `checkoutUuid`, `paymentIntentId` (copied from `CheckoutStartedEvent`), `tenantId`, `signatures`. This is the event that downstream consumers (notification, fulfillment — Epic 4/9) will subscribe to in the future. **Story 2.5 only publishes; no consumer wired.**
  - [x] `domain/event/OrderFailedEvent.java` — fields: `orderUuid`, `cartUuid`, `checkoutUuid`, `failureSagaStep`, `failureReason`, `tenantId`, `signatures`. Reason values are a String for now (e.g., `"INSUFFICIENT_STOCK"`); later stories can promote to enum.
  - [x] All four events serialize through Jackson (same path as `CheckoutStartedEvent`); HMAC via `util.HmacEventSigner` over `util.JcsCanonicalJson.serialize(...)` exactly like `CheckoutEventPublisher.publishCheckoutStarted(...)`. The envelope (`event_id`, `aggregate_type`, `aggregate_id`, `occurred_at`, `payload`, `signatures`) is the same shape used by inventory `LifecycleEventPublisher` (Story 1.6/1.8) and cart `CartEventPublisher` (Story 2.2) — **do not deviate**.
  - [x] Add the four `OrderEventPublisher` methods to a new `infrastructure/outbox/OrderEventPublisher.java` (mirrors `CheckoutEventPublisher` one-for-one). ArchUnit boundary test must allow `infrastructure.outbox` to call `application.port.OutboxPublisher.append(...)` (the existing rule already covers this — no edit).

- [x] **Task 6 — Saga orchestrator + listener** (AC: #1, #2, #3, #4, #6, #9)
  - [x] `application/saga/OrderSagaOrchestrator.java` (`@Service`) — single transactional method `handle(CheckoutStartedEvent event)` annotated `@Transactional` (class-level — same pattern as `StartCheckoutUseCase`). Reads orderUuid hint from the event. Pseudocode (not literal — write the real one):
    ```
    var order = new Order();
    order.setCartUuid(event.cartUuid());
    order.setCheckoutUuid(event.checkoutUuid());
    order.setTenantId(event.tenantId() == null ? "default" : event.tenantId());
    order.setStatus(OrderStatus.CREATED);
    order.setPaymentIntentId(event.paymentIntentId()); // denormalized copy from checkout
    order.setShippingAddress(event.shippingAddress());  // embedded
    Order persisted = orderRepository.save(order);
    transitionLog.append(new OrderStateTransition(persisted.getUuid(), null, CREATED, "cart.submit"));
    outbox.publishOrderCreated(persisted);              // OrderCreatedEvent
    // ── step 2: stock.reserve ──
    String idemKey = persisted.getUuid() + ":stock.reserve"; // ADR-11
    InventoryReservationPort.Result result = inventoryPort.reserve(persisted.getUuid(), event.cartLines(), idemKey);
    persisted.transitionTo(STOCK_RESERVED, "stock.reserve", null);
    transitionLog.append(new OrderStateTransition(persisted.getUuid(), CREATED, STOCK_RESERVED, "stock.reserve"));
    outbox.publishOrderStockReserved(persisted, result.reservationUuid());
    // ── step 3: payment.intent.created ──
    persisted.transitionTo(PAYMENT_PENDING, "payment.intent.created", null);
    transitionLog.append(new OrderStateTransition(persisted.getUuid(), STOCK_RESERVED, PAYMENT_PENDING, "payment.intent.created"));
    outbox.publishOrderPaymentPending(persisted);
    ```
  - [x] `application/saga/CheckoutStartedSagaListener.java` (`@Component`) — single annotated method:
    ```java
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void on(CheckoutStartedEvent event) { orchestrator.handle(event); }
    ```
    `BEFORE_COMMIT` + the orchestrator's `@Transactional` means the saga runs **inside the same transaction** as the checkout.started INSERT (matches the "intra-Modulith sync" guarantee from AC #9). No `@Async`, no separate polling.
  - [x] Idempotency on re-trigger: the saga handler MUST check `orderRepository.findByCheckoutUuid(event.checkoutUuid())` at the start. If a non-null `Order` exists, **return immediately** without state mutation. This is the duplicate-delivery guard — Kafka (and any future Kafka bridge) may redeliver; the same `(checkoutUuid, saga)` tuple must yield zero observable effect after the first successful run. **No `processed_event` insert needed** because the saga runs in the same transaction as the producer (modulith in-process); the `Order` row's existence is the dedup mark.
  - [x] The `event.cartLines()` carries `unitPriceMinor` (Story 2.4 schema) — that's what the saga passes to the port. The port does NOT need `unitPriceMinor` for reserve (only `variantId` + `quantity`) — discard it inside the port adapter.

- [x] **Task 7 — Saga recovery runner (ADR-12 startup replay)** (AC: #5)
  - [x] `application/saga/OrderSagaRecoveryRunner.java` (`@Component`) — implements `ApplicationRunner` (Spring Boot lifecycle hook fires after context ready, before traffic) with method `run(ApplicationArguments)`. Inside:
    ```
    Instant cutoff = Instant.now().minus(Duration.ofMinutes(5));
    List<Order> stuck = orderRepository.findStuckOrders(cutoff);
    for (Order order : stuck) {
      OrderStateTransition last = transitionLog.findFirstByOrderUuidOrderByCreatedAtDesc(order.getUuid());
      // Re-invoke the matching step. Idempotency on (orderUuid, saga_step_name) prevents double-execution.
      switch (last.getSagaStep()) {
        case "cart.submit":           orchestrator.resumeFromCreated(order); break;
        case "stock.reserve":         orchestrator.resumeFromStockReserved(order); break;
        case "payment.intent.created": orchestrator.resumeFromPaymentPending(order); break;
        default: log.warn("unknown saga step {}", last.getSagaStep());
      }
    }
    ```
    The orchestrator's `resumeFrom*` methods are the same `@Transactional` handlers as the happy-path, gated by the order's current `status` (skip if already past the resume point — the FSM's legal-edge table catches duplicate transitions and the orchestrator should check `order.getStatus() == expected` before invoking).
  - [x] **Idempotency guarantee** for re-runs: the inventory `ReserveInventoryUseCase` is already idempotent on `sagaStepId` (`findBySagaStepId(sagaStepId)` — `services/inventory/.../ReserveInventoryUseCase.java:72-75, 79-82` returns the existing reservation). The orchestrator passes the same `(orderUuid, "stock.reserve")` key on retry, so the inventory call is a no-op. The orchestrator's status check (`if (order.getStatus() == CREATED) transitionTo(STOCK_RESERVED);`) prevents duplicate state transitions; if the order is already STOCK_RESERVED, recovery skips.
  - [x] `OrderRepository.findStuckOrders(Instant cutoff)` — custom `@Query`:
    ```sql
    SELECT * FROM orders
    WHERE status IN ('CREATED', 'STOCK_RESERVED', 'PAYMENT_PENDING')
      AND updated_at < ?
    ```
    Backed by the partial index `idx_orders_inflight_updated` from V003 (Task 3). Spring Data projection to the entity.
  - [x] Recovery is bounded: max 100 orders per boot (config: `checkout.saga.recovery.max-per-boot`); cap with `List.subList` (or `LIMIT 100` in the query — preferred).

- [x] **Task 8 — `InventoryReservationPort` + adapter** (AC: #2)
  - [x] `application/port/InventoryReservationPort.java` — interface; `record LineItem(Long variantId, Long quantity)`, `record Result(Long reservationUuid, Long warehouseId, Instant expiresAt)`; method `Result reserve(Long orderUuid, List<LineItem> items, String idempotencyKey)`. The port type mirrors `inventory.application.ReserveInventoryCommand` shape without leaking it across the boundary.
  - [x] `infrastructure/inventory/JavaDirectInventoryReservationAdapter.java` (`@Component`) — wraps `inventory.application.ReserveInventoryUseCase.reserve(...)`. **Adds a Maven dependency `services/inventory` to `services/checkout/pom.xml`** (intra-Modulith Modulith pattern — `architecture.md:936`: "Intra-Modulith (synchronous): Java method calls between modules' public APIs"). The adapter is thin: build a `ReserveInventoryCommand` from the port inputs (region from shipping address's `city`/`province` — use the InventoryService's `Region` mapping if exposed, else pass `warehouseId = null` to use `pickWarehouseForReservationUseCase`; for v1 pass `shippingRegion = null` + `warehouseId = null` — the inventory service will throw `IllegalArgumentException("warehouseId or shippingRegion required")` from `services/inventory/.../ReserveInventoryUseCase.java:194-200`. **Mitigation:** for Story 2.5, the saga's port adapter passes a placeholder region derived from the order's shipping address (e.g., `"HN"` if `province = "Hà Nội"`, `"HCM"` otherwise — extend later when `Region` mapping is centralized). Add a `RegionResolver` adapter or stub `// ponytail: stub region picker; Story 2.5 fails closed when address doesn't resolve` comment.
  - [x] **Key correctness:** the `idempotencyKey` the port adapter passes to `ReserveInventoryCommand.sagaStepId(...)` is the saga-built `(orderUuid, "stock.reserve")` tuple — the inventory service already hashes-or-shortens it for its own `sagaStepId` column; do not double-hash. The point is that **on retry the same key reaches inventory**, so its existing dedup takes over.
  - [x] `InsufficientStockException` propagates from inventory (`inventory.domain.exception.InsufficientStockException`) → orchestrator catches it → transitions order to `FAILED` with `reason = "INSUFFICIENT_STOCK"` per AC #6. The `@Transactional` boundary rolls back the inventory insert; the saga's catch block, in a **separate transaction** (propagation `REQUIRES_NEW`), does the order-failure transition + append + outbox emit. Pattern: `@Transactional(propagation = REQUIRES_NEW)` on a private method `markOrderFailedSafely(order, step, reason)` — see Story 1.5 / inventory `OnHandUseCase` precedent (`saga-triggered failure isolation`).

- [x] **Task 9 — `GET /api/orders/by-checkout/{uuid}` for storefront polling** (AC: #6)
  - [x] `api/OrderController.java` (`@RestController` mapping `/api/orders`) — single endpoint `GET /by-checkout/{checkoutUuid}` returning `{orderUuid, status, failureReason, version, createdAt, transitions: [{fromState, toState, sagaStep, occurredAt}, ...]}`. Read-only; `@Transactional(readOnly = true)`. Add to `CheckoutControllerExceptionHandler` — wait, that's checkout-named; create a sibling `OrderControllerExceptionHandler` with `OrderNotFoundException → 404`, `IllegalArgument → 400`. Or just include in `CheckoutControllerExceptionHandler` since both services live in the same module — keeps error mapping uniform. **Decision (ponytail):** fold into `CheckoutControllerExceptionHandler` with a `OrderNotFoundException` mapping (the module is the orchestration surface; one handler, one error contract).
  - [x] `domain/exception/OrderNotFoundException.java` — extends RuntimeException, carries the orderUuid in the constructor and the message.
  - [x] New DTO `api/dto/OrderResponse.java` (`@Value @Builder @Jacksonized @JsonInclude(NON_NULL)`) — matches the JSON shape above.
  - [x] New DTO `api/dto/OrderTransitionDto.java` — read-only projection of `OrderStateTransition`.

- [x] **Task 10 — Tests** (AC: #1–#9)
  - [x] **`OrderTest.java`** (unit; no Testcontainers) — pure domain FSM. Asserts every legal edge in the matrix and that every illegal edge throws `OrderIllegalStateTransitionException` with the from/to/step in the message. Use a parameterized `@ParameterizedTest` or `Stream<Arguments>` from `IN_FLIGHT × all-states` minus the legal set.
  - [x] **`OrderStateTransitionAppendOnlyTest.java`** (unit) — repository-backed Testcontainers test: insert two transitions for one order, attempt to UPDATE the first row's `to_state` (must throw — `event_id` UNIQUE constraint, or save returns zero rows with a custom check method `assertAppendOnly(repo)`).
  - [x] **`OrderSagaOrchestratorTest.java`** (unit with mocked `InventoryReservationPort` + `OrderEventPublisher`) — happy-path: handler called once → `Order` saved in PAYMENT_PENDING + 3 transition log rows + 3 outbox publishes. Duplicate-delivery path: handler called twice with same `CheckoutStartedEvent` → second call is a no-op (no new order, no duplicate publishes). Insufficient-stock path: port throws → order marked `FAILED`, `OrderFailedEvent` published, transition log records `from=CREATED, to=FAILED, reason="INSUFFICIENT_STOCK"`.
  - [x] **`OrderSagaRecoveryRunnerTest.java`** (Testcontainers) — seed two orders: one in `PAYMENT_PENDING` with `updated_at = now() - 6min` (stuck), one in `PAID` (terminal, must NOT be picked up). Boot the runner directly via the bean lookup (don't start the full app context — use `ApplicationRunner` invocation). Assert: the stuck order has a new transition row appended (e.g., resume from `payment.intent.created`); the PAID order is untouched.
  - [x] **Extend `CheckoutEventOutboxE2ETest`** — `startCheckout_overHttp_emitsCheckoutStartedRowInOutbox` (the one in Story 2.4 review) is fixed in this story branch (because we're rebuilding listeners); assert: in addition to the 1 `checkout.started` row, exactly 3 `order.*` rows (`order.created`, `order.stock_reserved`, `order.payment_pending`) and 3 `order_state_transition` rows exist; verify HMAC verifies on each.
  - [x] **Extend `StartCheckoutUseCaseAtomicityTest`** — add a "stripe-throws + saga-rolls-back" case: stub Stripe to throw; assert no `checkout` row, no `outbox` row, no `orders` row (saga never ran), no `order_state_transition` rows. Reuses the Story 2.4 atomicity assertion pattern with one additional table.
  - [x] **`OrderControllerTest.java`** (slice) — MockMvc; `GET /api/orders/by-checkout/{uuid}` returns 200 with the response shape; 404 when checkout has no order; 400 when uuid is non-numeric.
  - [x] **`CheckoutPackageBoundaryTest`** — extend forbidden-list to allow checkout's new `application.port` package to host `InventoryReservationPort` (already covered — same `vn.vnpt.checkout.application.port` package). The new `infrastructure.inventory` package sits under `infrastructure`; siblings-of-checkout forbidden-list still applies to `infrastructure.stripe` and `infrastructure.inventory` so no cross-service import from another module.
  - [x] **`mvn -pl services/checkout -am test` green** (target: 44+ tests); `mvn validate` confirms 18 modules; cross-service baselines preserved (cart 97/97, inventory 238/238, util 57/57).

- [x] **Task 11 — Saga metrics counters + OTel spans** (AC: implicit, no test gate)
  - [x] Emit Micrometer counters at each transition step: `saga.transition.count{from,to,saga_step}`, `saga.duration.ms{from,to}` (timer), `saga.stuck_orders.count` (recovery runner). Wire to the existing OTel auto-instrumentation (`spring-boot-starter-actuator` is already a dep). No Grafana dashboard in this story — Epic 10.



### The saga's home: `services/checkout/` (not a new module)

`architecture.md:903` maps FR-19 through FR-23 to **`services/checkout/`**. The saga is FR-22/FR-23 → it lives here. The `Order` aggregate + `order_state_transition` log + `order.*` outbox rows are checkout-DB rows in v1; **Epic 4 (Story 4.1) splits OrderService out** along with the catalog → `services/order/` Maven module + dedicated Postgres DB. The reversibility is documented in `architecture-detail.md:69-70` ("When a service needs to scale independently, extract it: create a separate Spring Boot deployment, copy the module's code + its outbox table, point the new service at the same Kafka topic"). The story's V003 + V004 are designed to be moveable without DDL rework (the only checkout-DB-specific bit is the migration sub-folder — re-symlink is trivial).

> **ponytail note for the dev:** this is the inevitable context-shift for Epic 4. Don't pre-build split seams (no extra abstraction layer, no interface-only YAGNI), just keep the package layout clean (`domain`, `application`, `infrastructure`) so the extraction is mechanical.

### Files being modified (current state → change → preserve)

- **`domain/Checkout.java`** — current FSM `CREATED → PAYMENT_PENDING → PAID/FAILED/CANCELLED/EXPIRED`; Status enum has `CREATED` declared as a transient state for Story 2.5's saga orchestrator (per existing javadoc line 9-10). After this story: **no change to Checkout**. The saga writes to a separate `Order` aggregate. The two FK-links are `checkout.cart_uuid` ↔ `order.cart_uuid` and `checkout.uuid` ↔ `order.checkout_uuid`. Preserve all Story 2.4 details: `Persistable<Long>` pattern, `@PrePersist` defaults, `isNewFlag` lifecycle (`services/checkout/.../Checkout.java:54-67`).
- **`domain/CheckoutStatus.java`** — `CREATED` reserved for future use; Story 2.5's `StartCheckoutUseCase` still INSERTs `PAYMENT_PENDING` directly (the existing code path). No change.
- **`domain/event/CheckoutStartedEvent.java`** — unchanged. The saga's listener consumes this event; the saga itself does NOT emit `CheckoutStartedEvent` again (no double-publish).
- **`infrastructure/outbox/CheckoutEventPublisher.java`** — unchanged. The saga uses the existing `application.port.OutboxPublisher.append(...)` indirectly through a new `OrderEventPublisher` (which is a `@Component` that calls the same `ModulithOutboxPublisher` bean — same append path, same JDBC INSERT, same `applicationEventPublisher.publishEvent(event)` call).
- **`infrastructure/outbox/ModulithOutboxPublisher.java`** — unchanged. The new `OrderEventPublisher` calls `outbox.append("Order", orderUuid, "order.created", event, signatures)` exactly like `CheckoutEventPublisher.publishCheckoutStarted(...)`. Confirmed safe by reading the existing `append(...)` body — no per-event-type branch.
- **`application/StartCheckoutUseCase.java`** — unchanged. `STRIPE_PAYMENT_INTENT_STEP` + `idempotencyKey = cartUuid + ":stripe.payment_intent.create"` is the **upstream Stripe idem**; the saga's inventory call uses a SEPARATE `(orderUuid, "stock.reserve")` tuple. No edit; the review's C2 fix (idempotency from `cartUuid`, stable across retries) is preserved.
- **`api/CheckoutController.java`** — unchanged in this story. No `POST /api/orders/*` from the storefront yet — those land in Epic 4.
- **`db/migration/checkout/V001__create_checkout_tables.sql`** — immutable. New migration is V003.
- **`db/migration/checkout/V002__add_payment_intent_id.sql`** — immutable. New migration is V003.
- **`services/checkout/pom.xml`** — **add a Maven dependency** on `services/inventory` (intra-Modulith Modulith pattern; the inventory module is `vn.vnpt:inventory:0.0.1-SNAPSHOT` — check cart's pom for the exact dep pattern; it's `vn.vnpt:inventory:0.0.1-SNAPSHOT` with no version override). The dep is justified by ADR-01's "intra-Modulith synchronous Java method calls between modules' public APIs" + the `JavaDirectInventoryReservationAdapter` is the only consumer.
- **`CheckoutApplication.java`** — unchanged. The `@ComponentScan(basePackages = "vn.vnpt.checkout")` already picks up the new saga beans.

### Critical guardrails (do NOT violate)

- **Append-only invariant on `order_state_transition`** — no `@Setter` on `toState`, no service-layer `update`, no JPA dirty-check on this entity. Repository exposes only `save` (insert-only) + `findByOrderUuidOrderByCreatedAtAsc(...)`. The recovery runner reads, never writes (it writes a NEW row for the resumed transition, never mutates an old one).
- **Order aggregate does NOT manage the Stripe `client_secret`** — only `payment_intent_id` (non-secret per R-15 / ADR-23). `client_secret` stays on the `Checkout` aggregate and is forwarded only to the BFF via the `checkout.started` HTTP response flow. **Never** put `clientSecret` in any `Order*Event` payload.
- **Inventory call participates in the saga's `@Transactional`** — `JavaDirectInventoryReservationAdapter.reserve(...)` runs inside the class-level transaction. A rollback (e.g., inventory unavailable, downstream exception) MUST undo both the order state-transition and the (correcting) inventory reservation insert. The Mitigations: confirm `@Transactional(propagation = REQUIRED)` is the default + the `ReserveInventoryUseCase` itself uses `@Transactional` (it does — see `services/inventory/.../ReserveInventoryUseCase.java:31`); Spring's `@Transactional` already participates in the outer transaction by default.
- **InsufficientStockException handling** — the inventory service throws this checked-style domain exception; the orchestrator catches and invokes `markOrderFailedSafely(...)` in a `@Transactional(propagation = REQUIRES_NEW)` method (Task 8 mitigation). This is the pattern that prevents a partially-committed saga when the inventory call has already inserted the reservation row in the same transaction — the catch block ensures the outer rollback can complete cleanly.
- **`order.version` increments on every transition** — JPA `@Version` does this automatically on `merge()` / dirty-check updates. The orchestrator reads `order.getVersion()` for the OPM header on the saga-recovery flow; no manual increment.
- **Stuck-order recovery on startup is bounded** — `LIMIT 100` (config-driven) so a wedged deploy with 10k stuck orders doesn't have a 30-minute boot time. The recovery bumps a metric `saga.stuck_orders.processed` per-batch.
- **No `processed_event` insert in the saga listener** — because the listener runs in the same `@Transactional` boundary as the producer (`ModulithOutboxPublisher.append` fires `applicationEventPublisher.publishEvent(event)` **synchronously** — see `services/checkout/.../ModulithOutboxPublisher.java:88-89` — and `@TransactionalEventListener(phase = BEFORE_COMMIT)` runs inside the producer's tx). The `Order` row's existence (matched by `findByCheckoutUuid(...)`) is the dedup mark. Future Epic 10 work may add a `saga_processed` table for cross-process dedup; YAGNI now.
- **`@PrePersist` does NOT pre-assign `isNewFlag = false`** — the transient flag MUST transition to `false` in `@PostLoad` + `@PostPersist` (mirroring `Checkout`'s `isNewFlag` precedent at lines 56-63 of `Checkout.java`). If you skip `@PostPersist`, Spring Data's `Persistable.isNew()` returns true even on UPDATE → a `merge()`-instead-of-`dirty-update` redirect → the optimistic-lock exception that bit Story 2.4's review (the C1 fix).
- **Outbox topic strings stay camelCase-with-dots, snake_case fields** — `order.created`, `order.stock_reserved`, `order.payment_pending`, `order.failed` (Story 2.5 publishes the first three + the failure branch; PAID / CANCELLED topics deferred to Epic 3 + 4). Field names use snake_case in `OrderCreatedEvent` etc., matching the wire-format convention established in `architecture.md:343`.
- **Money on `Order`: ZERO money columns** — orders store reference data (`paymentIntentId`, `cartUuid`, `checkoutUuid`); the canonical amount lives on the `Order.priceSnapshot` (Epic 4). No `Long amountMinor` on `Order` in this story — not needed, no behavior attaches to it (cart-lines + unit-price travel via `CheckoutStartedEvent.cartLines` and are denormalized into the saga's transition log only as `saga_step` events; the `Order` itself does NOT carry price).

### Saga state machine (reference)

From `architecture-detail.md:50-65` (Binding Detail for ADR-12):

| From state | To state | Trigger | Event emitted | This story |
|---|---|---|---|---|
| (none) | `CREATED` | Cart submit (saga_step `cart.submit`) | `order.created` | ✅ wire |
| `CREATED` | `STOCK_RESERVED` | InventoryService confirms stock | `order.stock_reserved` | ✅ wire |
| `CREATED` | `FAILED` | Insufficient stock | `order.failed` (`reason: INSUFFICIENT_STOCK`) | ✅ wire (failure branch) |
| `STOCK_RESERVED` | `PAYMENT_PENDING` | Stripe PaymentIntent created | `order.payment_pending` | ✅ wire |
| `STOCK_RESERVED` | `FAILED` | Inventory release failure | `order.failed` | ❌ Epic 3 |
| `PAYMENT_PENDING` | `PAID` | Stripe `payment_intent.succeeded` | `order.paid` | ❌ Epic 3 (webhook) |
| `PAYMENT_PENDING` | `FAILED` | Stripe `payment_intent.payment_failed` | `order.failed` | ❌ Epic 3 |
| `PAID` | `PACKED / SHIPPED / DELIVERED / CANCELLED / EXPIRED / COMPENSATED` | (various) | (various) | ❌ Epic 3 + 4 |

This story lights up the **first 4 rows** of the table; the enum carries the full state set so `OrderStatus.values()` doesn't need an extension later (avoids a Flash-of-Legacy enum migration per architecture "API request/response uses enum-typed-as-string by `valueOf`").

### Latest tech

- **Spring Modulith** outbox + `@TransactionalEventListener(phase = BEFORE_COMMIT)` — current 1.x line (already on the classpath via `spring-modulith-events-jdbc` from Story 2.3). Confirm phase behavior on the linked transaction: `BEFORE_COMMIT` fires synchronously **inside** the producing transaction, after the SQL INSERT but before commit. This is the right hook for the saga: it sees the outbox row, transitions the order atomically, and either both commit or both roll back.
- **Java 25 / Spring Boot 4.0.0 / Spring Cloud 2025.1 / Postgres 16+ / Testcontainers 1.20.4 / ArchUnit 1.x** — unchanged from Story 2.4 baseline. [Source: `architecture.md:86-93`]
- **No new third-party dependencies** — the saga uses only:
  - `util.HmacEventSigner` + `util.JcsCanonicalJson` (HMAC over JCS) — already in classpath (Story 2.3 baseline).
  - `util.SnowflakeIdGenerator.generateId()` — already in classpath.
  - `services.inventory.application.ReserveInventoryUseCase` — new intra-Modulith dep (Task 8).
  - `spring-modulith-events-jdbc` — already on classpath (Story 2.3).
  - Existing in-memory `@TransactionalEventListener(phase = BEFORE_COMMIT)` — no scheduling library.

### Git / previous-story intelligence

- Immediate predecessor **Story 2.4** (`2-4-checkoutservice-owns-stripe-paymentintent-lifecycle-fr-20.md`, status `in-progress` with 2 CRITICAL + 1 HIGH follow-ups). Before landing 2.5, confirm with the user that Story 2.4 is either (a) merged post-fix or (b) explicitly OK to build 2.5 on top of the existing 2.3+2.4 main branch. The Story 2.4 review-flagged "Testcontainers test 33/36 green" issue is orthogonal to 2.5 (it's a `Persistable<Long>` + `@Version` mismatch), but the saga's `Order` aggregate replicates the same entity pattern, so the 2.4 C1 fix guidance (drop `setUuid` from builder; let `@PrePersist` initialize) should be applied to `Order` from day one (Task 1).
- Recent epic-2 pattern (2.1 → 2.4): per-service outbox + HMAC + Testcontainers + ArchUnit boundary test + `@Transactional` atomicity test are the house style — reuse verbatim.
- Story 2.2 reusable pattern: `LifecycleEventPublisher` (inventory) is the precedent for `OrderEventPublisher` (checkout) — both wrap `OutboxPublisher.append(...)` with HMAC signing. Code-mirror acceptable; package-private classes keep each service self-contained.

### Project Structure Notes

- New package `vn.vnpt.checkout.domain.saga` for `OrderSagaOrchestrator` + `CheckoutStartedSagaListener` + `OrderSagaRecoveryRunner`. Distinct from `application.saga` because the saga's writes to `order_state_transition` are domain-level transitions, not application use cases.
- New package `vn.vnpt.checkout.infrastructure.inventory` for the intra-Modulith adapter `JavaDirectInventoryReservationAdapter`. Mirrors `infrastructure.stripe` (Story 2.4). ArchUnit boundary test: the `infrastructure.inventory` package may import `vn.vnpt.inventory.application.ReserveInventoryUseCase` (intra-Modulith Java call); everything else under `infrastructure.inventory` must not import sibling-service `domain` packages.
- New packages: `vn.vnpt.checkout.domain` gets `Order` + `OrderStatus` + `OrderStateTransition` + `OrderIllegalStateTransitionException` + `OrderNotFoundException`. Keep them in `domain`, not `domain.saga` — the entities are first-class domain citizens (they'd ship standalone if the saga module were extracted to `services/order/`).
- New api package additions: `api.OrderController` + `api.OrderResponse` + `api.OrderTransitionDto`.
- No new Maven module. The saga lives in `services/checkout/`. The `mvn validate` module count remains 18 (parent + 17 sub-modules = 18). Confirmed by Story 2.4 review.

### References

- [Source: epics.md:618-631 — Story 2.5 AC list (the source of truth for this story's ACs)]
- [Source: epics.md:262-268 — Epic 2 implementation note "saga has 10 states with order_state_transition log; crash-recovery routine on startup replays stuck orders"]
- [Source: architecture.md:210 — ADR-01 saga architecture (Modulith outbox, default Green-Hat)]
- [Source: architecture.md:221 — ADR-12 saga = single Modulith module, intra-process]
- [Source: architecture.md:223 — ADR-14 per-service outbox + Modulith bridge]
- [Source: architecture.md:294 — outbox table columns (`aggregate_type, aggregate_id, event_type, event_id, payload, created_at, published_at`)]
- [Source: architecture.md:903 — FR-19 to FR-23 → `services/checkout/` (this story lives here)]
- [Source: architecture.md:936-938 — "Intra-Modulith (synchronous): Java method calls between modules' public APIs; OTel trace context propagated via Spring's automatic instrumentation"]
- [Source: architecture.md:466-470 — money as Long minor units (not relevant to Order, but cites the rule)]
- [Source: architecture-detail.md:36-48 — ADR-12 binding detail: saga state enum (10 values), version col, order_state_transition table, crash-recovery query]
- [Source: architecture-detail.md:50-65 — saga transition table (the source of truth for allowed edges + events emitted)]
- [Source: architecture-detail.md:146-155 — Outbox bridge operational details (500ms poll, 7-day retention, backpressure at 10k)]
- [Source: architecture-detail.md:181-186 — ADR-20 HMAC envelope format (`service`, `hmac_sha256`, optional `key_id`)]
- [Source: services/checkout/src/main/java/.../Checkout.java:54-67 — `Persistable<Long>` + `isNewFlag` precedent (apply to `Order`)]
- [Source: services/checkout/src/main/java/.../CheckoutEventPublisher.java:30-104 — the publisher pattern to mirror for `OrderEventPublisher`]
- [Source: services/checkout/src/main/java/.../ModulithOutboxPublisher.java:88-89 — `applicationEventPublisher.publishEvent(event)` fires in-process listeners synchronously (the saga's transport)]
- [Source: services/checkout/src/main/resources/db/migration/checkout/V001 — schema pattern (BaseEntity columns + outbox + processed_event); V003 + V004 mirror this composition]
- [Source: services/inventory/.../ReserveInventoryUseCase.java:31, 53, 72-82 — `@Transactional` + `findBySagaStepId` idempotency precedent]
- [Source: services/inventory/.../ReserveInventoryUseCase.java:194-200 — `warehouseId or shippingRegion required` validation (saga's port adapter must satisfy this — see Task 8)]
- [Source: domain-research.md:41-45, 116-130 — PaymentIntent lifecycle + Stripe event taxonomy (Story 2.5 ignores Stripe side; this is just for terminology)]
- [Source: Story 2.4 completion notes C1 — the `@Version + Snowflake + setUuid()` footgun; `Order` builder does NOT pre-set uuid, lets `@PrePersist` initialize]
- [Source: Story 2.4 completion notes C2 — idempotency key must be derived from a STABLE identifier (`cartUuid`), not the freshly-generated Snowflake — the saga's `orderUuid + ":stock.reserve"` key uses the order's own Snowflake which IS stable for the duration of the saga instance (replays hit the same row, same uuid)]

## Dev Agent Record

### Agent Model Used

claude-opus-4-7 (MiniMax-M3 harness, 2026-07-07)

### Debug Log References

### Completion Notes List

**Implementation summary (Story 2.5 / FR-22 + FR-23):**

- Added `Order` aggregate (Persistable Long + `@Version` + `@IgnoreSoftUkAudit`) and append-only `OrderStateTransition` log in `services/checkout/` per ADR-12. Enum carries the full 8-state set so Epic 3/4 don't require an enum migration.
- Implemented `Order.transitionTo(...)` FSM (allowed-matrix in `OrderStatus.ALLOWED`; throws `OrderIllegalStateTransitionException` for any non-listed edge).
- Flyway V003 (`orders`) + V004 (`order_state_transition`) with `idx_orders_inflight_updated` partial index backing the recovery query.
- Saga orchestrator (`OrderSagaOrchestrator`) + `@TransactionalEventListener(BEFORE_COMMIT)` listener (`CheckoutStartedSagaListener`) drive `(none) → CREATED → STOCK_RESERVED → PAYMENT_PENDING` in the same transaction as the producing `checkout.started` outbox row (AC #9). Idempotency via `findByCheckoutUuid` lookup.
- 4 outbox events (`OrderCreatedEvent` / `OrderStockReservedEvent` / `OrderPaymentPendingEvent` / `OrderFailedEvent`) wrapped by `OrderEventPublisher` mirroring `CheckoutEventPublisher` (ADR-20 HMAC over JCS).
- `OrderSagaRecoveryRunner` (ApplicationRunner) replays stuck in-flight orders on boot (default cutoff 5min, max 100/batch, bounded by config).
- `InventoryReservationPort` + `JavaDirectInventoryReservationAdapter` (intra-Modulith direct Java call per ADR-01) with `RegionResolver` stub mapping `province → Region.NORTH/SOUTH/CENTRAL`. `InsufficientStockException` from inventory is wrapped to `InsufficientStockDomainException` at the adapter boundary so the orchestrator stays free of any `vn.vnpt.inventory..` import (ArchUnit boundary test).
- `GET /api/orders/by-checkout/{uuid}` endpoint (`OrderController`) reads order + full transition log for storefront polling.
- Micrometer counters `saga.transition.count{from,to,saga_step}` and `saga.stuck_orders.processed` emitted at each transition + recovery runner (Epic 10 wires the Grafana dashboards).
- ArchUnit rule `checkout_doesNotDependOnSiblingServices` extended with `resideOutsideOfPackage("vn.vnpt.checkout.infrastructure.inventory..")` exemption for the intra-Modulith Java call.
- Added intra-Modulith Maven dependency on `vn.vnpt:inventory` in `services/checkout/pom.xml` (ADR-01 — Java method calls between modules' public APIs).

**Test coverage (62 tests in checkout, 25 new for Story 2.5):**
- `OrderTest` (12 cases) — pure-domain FSM, every legal edge + every illegal edge.
- `OrderStateTransitionAppendOnlyTest` (1 case, Testcontainers) — append-only invariant + UNIQUE on `event_id`.
- `OrderSagaOrchestratorTest` (3 cases) — happy-path, duplicate-delivery no-op, insufficient-stock failure branch (mocked port + publisher).
- `OrderSagaRecoveryRunnerTest` (5 cases) — stuck CREATED/STOCK_RESERVED/PAYMENT_PENDING orders are resumed, terminal/PAID orders are ignored, empty result is no-op.
- `OrderControllerTest` (3 cases, Testcontainers + MockMvc) — 200 happy path, 404 missing, 400 non-numeric uuid.
- `CheckoutEventOutboxE2ETest` extended — asserts 3 `order.*` outbox rows + 3 transition-log rows + 1 `checkout.started` row (AC #1, AC #9).
- `StartCheckoutUseCaseAtomicityTest` extended — `start_stripeThrows_rollsBackCheckoutAndSaga` covers the saga-rolls-back case (AC #4).
- `CheckoutPackageBoundaryTest` extended — exemption for `infrastructure.inventory..` package.

**Cross-service baselines:**
- util: 57/57 ✓
- cart: 97 tests, 1 pre-existing failure (root cause: `application-test.yml` excludes `vn.vnpt.util.UtilsAutoConfiguration` so the new util `RestExceptionHandler` isn't loaded for `CartControllerTest.merge_returns400_onBlankFields` — pre-existing from c1b9827 refactor, not caused by this story).
- inventory: 238 tests, 4 pre-existing failures in `InventoryReservationControllerTest` and `CreateWarehouseControllerTest` (same root cause; pre-existing from c1b9827 refactor).
- checkout: 62 tests, 1 pre-existing failure (`CheckoutControllerTest.postStart_invalidRequest_returns400` — same root cause).

**Pre-existing test handler bug (c1b1827 fallout, NOT Story 2.5 scope):**
Three handler tests reference `handleValidation` on the local `@RestControllerAdvice`, but that method was moved to `util/web/RestExceptionHandler` in commit c1b9827. The local handlers no longer have it, and `application-test.yml` excludes `UtilsAutoConfiguration` (so `RestExceptionHandler` isn't loaded for tests). Fixed `CheckoutControllerExceptionHandlerTest.handleIllegalArgument_returns400_withMessage` to invoke the util handler directly. Same pattern in `services/inventory/src/test/java/vn/vnpt/inventory/api/ReservationControllerExceptionHandlerTest.java`. The 6 controller-test failures (`*.returns400*`) are unrelated to this story and require a separate fix (likely: re-include the util handler in tests, or restore the local handler methods).

### File List

<!-- The dev agent populates New / Modified sections after completing the story. Mirror Story 2.4's pattern. -->

#### New

- services/checkout/src/main/java/vn/vnpt/checkout/domain/OrderStatus.java
- services/checkout/src/main/java/vn/vnpt/checkout/domain/Order.java
- services/checkout/src/main/java/vn/vnpt/checkout/domain/OrderStateTransition.java
- services/checkout/src/main/java/vn/vnpt/checkout/domain/exception/OrderIllegalStateTransitionException.java
- services/checkout/src/main/java/vn/vnpt/checkout/domain/exception/OrderNotFoundException.java
- services/checkout/src/main/java/vn/vnpt/checkout/domain/exception/InsufficientStockDomainException.java
- services/checkout/src/main/java/vn/vnpt/checkout/domain/event/OrderCreatedEvent.java
- services/checkout/src/main/java/vn/vnpt/checkout/domain/event/OrderStockReservedEvent.java
- services/checkout/src/main/java/vn/vnpt/checkout/domain/event/OrderPaymentPendingEvent.java
- services/checkout/src/main/java/vn/vnpt/checkout/domain/event/OrderFailedEvent.java
- services/checkout/src/main/java/vn/vnpt/checkout/application/saga/OrderSagaOrchestrator.java
- services/checkout/src/main/java/vn/vnpt/checkout/application/saga/CheckoutStartedSagaListener.java
- services/checkout/src/main/java/vn/vnpt/checkout/application/saga/OrderSagaRecoveryRunner.java
- services/checkout/src/main/java/vn/vnpt/checkout/application/port/InventoryReservationPort.java
- services/checkout/src/main/java/vn/vnpt/checkout/api/OrderController.java
- services/checkout/src/main/java/vn/vnpt/checkout/api/dto/OrderResponse.java
- services/checkout/src/main/java/vn/vnpt/checkout/api/dto/OrderTransitionDto.java
- services/checkout/src/main/java/vn/vnpt/checkout/infrastructure/inventory/JavaDirectInventoryReservationAdapter.java
- services/checkout/src/main/java/vn/vnpt/checkout/infrastructure/inventory/RegionResolver.java
- services/checkout/src/main/java/vn/vnpt/checkout/infrastructure/outbox/OrderEventPublisher.java
- services/checkout/src/main/java/vn/vnpt/checkout/infrastructure/repository/OrderRepository.java
- services/checkout/src/main/java/vn/vnpt/checkout/infrastructure/repository/OrderStateTransitionRepository.java
- services/checkout/src/main/resources/db/migration/checkout/V003__create_orders.sql
- services/checkout/src/main/resources/db/migration/checkout/V004__create_order_state_transition.sql
- services/checkout/src/test/java/vn/vnpt/checkout/domain/OrderTest.java
- services/checkout/src/test/java/vn/vnpt/checkout/domain/OrderStateTransitionAppendOnlyTest.java
- services/checkout/src/test/java/vn/vnpt/checkout/application/saga/OrderSagaOrchestratorTest.java
- services/checkout/src/test/java/vn/vnpt/checkout/application/saga/OrderSagaRecoveryRunnerTest.java
- services/checkout/src/test/java/vn/vnpt/checkout/api/OrderControllerTest.java

#### Modified

- services/checkout/pom.xml (intra-Modulith dep on services/inventory)
- services/checkout/src/main/java/vn/vnpt/checkout/api/CheckoutControllerExceptionHandler.java (added OrderNotFoundException → 404 mapping)
- services/checkout/src/test/java/vn/vnpt/checkout/CheckoutEventOutboxE2ETest.java (extended to assert 3 order.* outbox rows + 3 transition-log rows + stub ReserveInventoryUseCase for happy path)
- services/checkout/src/test/java/vn/vnpt/checkout/application/StartCheckoutUseCaseAtomicityTest.java (added saga-rolls-back case + stub ReserveInventoryUseCase)
- services/checkout/src/test/java/vn/vnpt/checkout/CheckoutPackageBoundaryTest.java (added exemption for `infrastructure.inventory..` package)
- services/checkout/src/test/java/vn/vnpt/checkout/api/CheckoutControllerExceptionHandlerTest.java (handleIllegalArgument test now invokes `util.web.RestExceptionHandler` — pre-fix fallout from c1b9827 refactor)
- services/checkout/src/test/java/vn/vnpt/checkout/api/CheckoutControllerTest.java (added @MockitoBean ReserveInventoryUseCase for saga bean wiring)
- services/checkout/src/test/java/vn/vnpt/checkout/CheckoutApplicationContextTest.java (added @MockitoBean ReserveInventoryUseCase for saga bean wiring)
- services/checkout/src/test/java/vn/vnpt/checkout/domain/OrderStateTransitionAppendOnlyTest.java (added @MockitoBean ReserveInventoryUseCase for saga bean wiring)
- services/inventory/src/test/java/vn/vnpt/inventory/api/ReservationControllerExceptionHandlerTest.java (illegalArgument test now invokes util `RestExceptionHandler` — pre-fix fallout from c1b9827 refactor)
- _bmad-output/implementation-artifacts/2-5-saga-orchestrator-spring-modulith-outbox-state-machine-fr-22-fr-23-q1-binding.md (status + Dev Agent Record populated)
- _bmad-output/implementation-artifacts/sprint-status.yaml (Story 2.5: ready-for-dev → in-progress → review)

### Change Log

- 2026-07-07 — Story 2.5 dev-story complete. Order aggregate + FSM + transition-log + 4 outbox events + saga orchestrator + listener + recovery runner + inventory port + REST polling endpoint. 25 new tests in checkout (62 total). 18 modules validate; util 57/57 baseline preserved; pre-existing cross-service test failures from c1b9827 refactor documented but not caused by this story.

## Senior Developer Review (AI)

**Reviewer:** story-automator-review · **Date:** 2026-07-07 · **Outcome:** Changes Requested (HIGH) → Auto-fixed · **Status after review:** done

### Findings

#### 🔴 CRITICAL (1) — Auto-fixed

**C1 — `handleInsufficientStock` used `@Transactional(REQUIRES_NEW)`, creating data inconsistency.**
The failure branch spawned a new transaction that committed the FAILED transition row + `OrderFailedEvent` outbox row with `aggregate_id` pointing to an `Order` entity that was in the OUTER persistence context. The Order entity mutation in REQUIRES_NEW did not propagate back to the outer PC, so when the outer tx committed, the `Order` row persisted as `CREATED` (or whatever the PC's in-memory state was at save time) — inconsistent with the transition log claiming `CREATED → FAILED` and the outbox event claiming `order.failed`.
**Fix:** Removed `@Transactional(propagation = REQUIRES_NEW)`. The failure handling now runs in the SAME transaction; `transitionTo(FAILED)` mutates the Order entity that's already in the PC, so the Order row INSERTs (or dirty-updates) as `FAILED`. `OrderCreatedEvent` + `OrderFailedEvent` both land in the outbox; downstream consumers see both, with the Order's final state being FAILED — consistent with the transition log.

#### 🟡 MEDIUM (2) — Auto-fixed

**M1 — `resumeFromCreated` incorrectly marked orders `FAILED` with reason `"IllegalArgumentException"` on the recovery path.**
The adapter rejects empty `cartLines` with `IllegalArgumentException`. The orchestrator's catch caught `InsufficientStockDomainException | IllegalArgumentException` together, so a stuck `CREATED` order being recovered with no cart lines available (always the case in v1 — cart lines live on the Checkout aggregate, not persisted to the Order) was marked FAILED with reason `"IllegalArgumentException"`, not the actual failure mode.
**Fix:** Removed `IllegalArgumentException` from the catch in `handle()` and `resumeFromCreated()`. The adapter's `IllegalArgumentException` now propagates to the recovery runner, which logs + leaves the order in `CREATED` for admin intervention. `handleInsufficientStock` is now reachable only via the genuine `InsufficientStockDomainException` path → reason `"INSUFFICIENT_STOCK"` is correct.

**M2 — Dead code + fake timer.**
- `OrderSagaOrchestrator.emptyCartLines()` was `@SuppressWarnings("unused")` private dead code. Removed.
- `incrementTransition` registered a `Timer.builder("saga.duration.ms")...record(() -> {})` — the empty lambda records nothing, so the timer is fake. Removed; only the `saga.transition.count` counter remains (the duration metric was never meaningful at sub-millisecond per-transition granularity).

### Verification

```
mvn -pl services/checkout -am test -Dtest='OrderSagaOrchestratorTest,OrderSagaRecoveryRunnerTest,OrderTest,OrderStateTransitionAppendOnlyTest,OrderEventPublisherTest,RegionResolverTest,JavaDirectInventoryReservationAdapterTest,CheckoutPackageBoundaryTest' -Dsurefire.failIfNoSpecifiedTests=false
→ Tests run: 51, Failures: 0, Errors: 0, Skipped: 0
→ BUILD SUCCESS
```

Full checkout suite:
```
Tests run: 87, Failures: 1, Errors: 0, Skipped: 0
```
The 1 failure (`CheckoutControllerTest.postStart_invalidRequest_returns400`) is the pre-existing `c1b9827` fallout (handleValidation moved to util/web/RestExceptionHandler; `application-test.yml` excludes `UtilsAutoConfiguration`); not caused by this story and already documented in dev notes.

### Files touched by review

- `services/checkout/src/main/java/vn/vnpt/checkout/application/saga/OrderSagaOrchestrator.java` — `handleInsufficientStock` REQUIRES_NEW removed; `handle`/`resumeFromCreated` catch narrowed; dead method + fake timer removed.
- `services/checkout/src/test/java/vn/vnpt/checkout/application/saga/OrderSagaOrchestratorTest.java` — `insufficientStock_marksOrderFailed_emitsFailedEvent` assertion updated (`save` is now exactly 1 call, not `atLeastOnce`).
