---
baseline_commit: 6b6d952
---

# Story 4.1: OrderService — append-only event log (FR-30, FR-31)

Status: review

## Story

As the order aggregate,
I want all state changes logged in append-only form with an immutable price snapshot,
so that order history is replayable and prices don't retroactively change (FR-30 + FR-31).

## Acceptance Criteria

1. **Given** the architecture binds a **`services/order/`** Maven module that owns FR-30 to FR-34 (`architecture.md:905` + `architecture.md:1030`), **and** the current `services/order/pom.xml` is a stub with packaging `pom` (no Java sources), **When** Story 4.1 lands, **Then** `services/order/` is bootstrapped with packaging `jar`, port 8087, dependencies: `util` (for `BaseEntity`, `SnowflakeIdGenerator`, `HmacServiceKeyProvider` if payment-signed events arrive — verify; order may not need signing for inbound), `spring-boot-starter-web` (MVC for the timeline endpoint in Story 4.3), `spring-boot-starter-data-jpa` + `spring-boot-flyway` + `flyway-core` + `flyway-database-postgresql` + `org.postgresql:postgresql` (per-service database, ADR-03), `spring-modulith-events-jdbc` (Modulith outbox, ADR-01/14), `spring-boot-starter-actuator` (for `/actuator/health`), `spring-boot-starter-test` + `archunit-junit5` + Testcontainers `postgresql` (test scope), Lombok. The `mvn validate` module count stays at 19 (services/order already counted in the root pom). `services/order/src/main/java/vn/vnpt/order/OrderApplication.java` is `@SpringBootApplication @ApplicationModule(displayName = "order")`.
2. **Given** FR-30 mandates an "append-only event log; current state is a projection" (verbatim `prd.md:80`), **When** the dev agent writes the schema, **Then** two tables land in `services/order/src/main/resources/db/migration/order/`:
   - `order_state_transition` — `id BIGSERIAL PRIMARY KEY`, `order_uuid BIGINT NOT NULL`, `from_state VARCHAR(32)` (nullable for the genesis event), `to_state VARCHAR(32) NOT NULL`, `saga_step VARCHAR(64) NOT NULL`, `event_id BIGINT NOT NULL UNIQUE` (Snowflake, mirrors `processed_event` pattern from Story 1.3 / 3.2), `created_at TIMESTAMP NOT NULL DEFAULT now()`. Mirrors the canonical schema in `architecture.md:299` row "Order state transition log: id, order_uuid, from_state, to_state, saga_step, event_id, created_at".
   - `order_price_snapshot` — `order_uuid BIGINT PRIMARY KEY`, `list_price_cents BIGINT NOT NULL`, `promo_codes TEXT[]` (nullable), `tax_cents BIGINT NOT NULL`, `shipping_cents BIGINT NOT NULL`, `total_cents BIGINT NOT NULL` (computed: list + tax + shipping − promo discount; the column is the source of truth for `order.total`), `currency CHAR(3) NOT NULL`, `captured_at TIMESTAMP NOT NULL DEFAULT now()`. NO `updated_at` column (FR-31: "immutable after order.placed"). 
   - Indexes: `idx_order_state_transition_order_uuid` on `(order_uuid, created_at DESC)`, `idx_order_state_transition_event_id` on `(event_id)` (UNIQUE covers it but explicit for clarity).
   - The migration runs in V001; V002 (and later) layers changes.
3. **Given** FR-31 requires `order.priceSnapshot` to be JSONB (per `addendum.md` + the price-snapshot column set above maps to a `JsonNode` view in the application layer), **When** the dev agent implements the JPA entities, **Then** `services/order/src/main/java/vn/vnpt/order/infrastructure/entity/OrderPriceSnapshot.java` is `@Entity @Table(name = "order_price_snapshot")` with `@Id Long orderUuid` (no surrogate — the order is the natural key), Lombok `@Getter @Builder @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode(of = "orderUuid")` (no `@Setter` per immutability per FR-31). The fields map to the columns in AC #2; the `promoCodes` field uses Hibernate's `@Column(columnDefinition = "text[]")` (Postgres array) with a `@Type` annotation if needed (verify against Hibernate 7.0+ in Spring Boot 4.0).
4. **Given** the architecture binds `order_state_transition` as the append-only log (`architecture.md:299` row), **When** the dev agent writes the JPA entity, **Then** `services/order/src/main/java/vn/vnpt/order/infrastructure/entity/OrderStateTransition.java` is `@Entity @Table(name = "order_state_transition")` with `@Id @GeneratedValue Long id`, `@Column Long orderUuid`, `String fromState` (nullable), `@Column(nullable = false) String toState`, `@Column(nullable = false) String sagaStep`, `@Column(unique = true) Long eventId`, `LocalDateTime createdAt`. Lombok `@Getter @Builder @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode(of = "id")`. NO `@Setter` — append-only; updates are forbidden at the JPA level (the entity has setters for builder but the use case layer treats the table as INSERT-only).
5. **Given** the aggregate-root pattern, **When** the dev agent writes the domain, **Then** `services/order/src/main/java/vn/vnpt/order/domain/Order.java` is a `record Order(long orderUuid, OrderState state, OrderPriceSnapshot priceSnapshot, long version)` (immutable — the value object). `OrderState` is an enum: `PLACED → PAID → ALLOCATED → PACKING → PACKED → SHIPPED → DELIVERED` (mirrors Story 4.2's saga; the genesis state for Story 4.1 is `PLACED`; subsequent states land in Story 4.2). The aggregate logic lives in `application/usecase/AppendOrderTransitionUseCase.java` which: (a) loads the latest transition for `orderUuid` via `OrderStateTransitionRepository.findFirstByOrderUuidOrderByIdDesc(...)`, (b) validates the new state transition against the OrderState enum's allowed edges (PLACED → PAID; PAID → ALLOCATED; etc. — for Story 4.1 only PLACED is exercised; the validator is forward-compatible), (c) inserts a new `OrderStateTransition` row with the new state + saga_step, (d) inserts the `OrderPriceSnapshot` (on the first transition; subsequent transitions keep the existing snapshot). The use case is `@Service @Transactional`; the append is atomic across both tables.
6. **Given** the append-only contract, **When** the dev agent writes the repository, **Then** `services/order/src/main/java/vn/vnpt/order/infrastructure/repository/OrderStateTransitionRepository.java` extends `JpaRepository<OrderStateTransition, Long>`, exposes `Optional<OrderStateTransition> findFirstByOrderUuidOrderByIdDesc(Long orderUuid)`, `List<OrderStateTransition> findByOrderUuidOrderByIdAsc(Long orderUuid)`, AND the use case layer enforces INSERT-only (the repository itself is a normal JPA repo, but the use case NEVER calls `save()` on an existing entity — it always creates a new one with `@GeneratedValue` IDs). The `OrderPriceSnapshotRepository` extends `JpaRepository<OrderPriceSnapshot, Long>`, exposes only `save(...)` and `findById(...)`; no `deleteById`/`deleteAll` methods exposed (Ponytail: don't write the deletion API at all; the contract is write-once).
7. **Given** the architecture binds HMAC event signing to producer-side services (Story 3.5 + ADR-20; `architecture-detail.md:191`), **and** the order service IS a producer of `order.placed` / `order.allocated` / `order.packed` etc. events (FR-28 row "payment.captured, payment.refunded, payment.failed, payment.disputed" — Story 4.2's saga emits these; order is the consumer for `payment.captured` and the producer for downstream events), **When** Story 4.1 lands, **Then** the Modulith outbox publisher follows the same pattern as Story 3.5: `services/order/src/main/java/vn/vnpt/order/infrastructure/outbox/OrderModulithOutboxPublisher.java` extends `ModulithOutboxPublisher`, overrides `signaturesFor(...)` to sign the envelope with the order's HMAC key (path `secret/events/hmac/order` in Vault; dev profile reads `HMAC_SERVICE_SECRET_ORDER` env var; reuse the `HmacServiceKeyProvider` pattern from Story 3.5). The smoke test asserts the `outbox` table has a row with `signatures.hmac_sha256` populated.
8. **Given** the runtime smoke rule (project memory `runtime-smoke-rule.md` — every dev-story must runtime-smoke), **When** Story 4.1 completes, **Then** the dev agent runs `bash dev/scripts/smoke-order-4-1.sh` which: (a) starts `services/order` with `SPRING_PROFILES_ACTIVE=dev` + `HMAC_SERVICE_SECRET_ORDER=<32-byte-hex>`; (b) waits for `/actuator/health` UP (up to 90s); (c) `curl -X POST http://localhost:8087/api/orders/test -H 'Content-Type: application/json' -d '{"orderUuid":12345,"state":"PLACED","priceSnapshot":{"listPriceCents":10000,"taxCents":1000,"shippingCents":500,"totalCents":11500,"currency":"USD"}}'` — asserts HTTP 201 with the new order's transition ID; (d) `docker exec postgres psql -d order_db -c "SELECT count(*) FROM order_state_transition WHERE order_uuid = 12345"` returns 1; (e) `docker exec postgres psql -d order_db -c "SELECT count(*) FROM order_price_snapshot WHERE order_uuid = 12345"` returns 1; (f) `docker exec postgres psql -d order_db -c "UPDATE order_price_snapshot SET total_cents = 99999 WHERE order_uuid = 12345"` should succeed at the SQL level (no DB constraint forbids it — the immutability is at the application layer; FR-31 trust boundary is the use case, not the DB); the test asserts that no API endpoint exists for this UPDATE (the JPA repository has no setter methods, the use case has no update path); (g) `curl -X POST` with a second transition (`state=PAID`) for the same orderUuid — asserts HTTP 201 + a 2nd row in `order_state_transition`; (h) `curl -X GET http://localhost:8087/api/orders/12345` returns the full transition history; (i) kills the process, exits 0.
9. **Given** the existing ArchUnit deny-list from Story 3.3 (`util/.../archunit/RequestBodyLoggerDenyListTest.java`) covers `vn.vnpt..`, **When** Story 4.1 lands, **Then** the order service automatically inherits the deny-list. The new payment-port-contract test for order lives at `services/order/src/test/java/vn/vnpt/order/OrderPortContractTest.java` and mirrors the existing pattern: (a) `application.usecase → infrastructure.entity` is forbidden; (b) `application.usecase → infrastructure.repository` is forbidden; (c) `application.usecase → infrastructure.outbox` is forbidden (outbox is infrastructure; use case talks to the port); (d) `application.port → infrastructure.*` is forbidden; (e) `noRequestBodyLogger_subclassesAbstractRequestLoggingFilter` (deny-list). The test class extends the same pattern as `services/payment/.../PaymentPortContractTest.java`.
10. **Given** the Story 3.3 R-15 redaction is repo-wide via `util/.../logging/PanRedactingAppender`, **When** Story 4.1 lands, **Then** `services/order/src/main/resources/logback-spring.xml` `<include>`s the shared `util/.../logback-include.xml` (same pattern as `services/payment/.../logback-spring.xml` from Story 3.3). The `application.yml` mirrors `services/payment/src/main/resources/application.yml` structure: `server.port: 8087`, `management.endpoints.web.exposure.include: health,info` (FR-29 deny-list), Postgres datasource via `POSTGRES_ORDER_*` env vars, Modulith outbox + EventPublicationRegistry excluded in the same pattern as payment (per the codebase convention from Story 3.1 + the `modulith.events.jdbc` exclude).

### Out of scope (do NOT build — deferred, stated to prevent scope creep)

- **State transitions beyond PLACED** (PAID, ALLOCATED, PACKING, PACKED, SHIPPED, DELIVERED) → Story 4.2 (post-payment lifecycle). Story 4.1 ships the `PLACED` state + the transition log + the validator stub. The enum is forward-compatible.
- **Saga integration with payment** (consume `payment.captured` from `services/payment`'s outbox + advance `PLACED → PAID`) → Story 4.2. Story 4.1 ships the producer-side outbox + the append-only log; the consumer side lands in Story 4.2.
- **`OrderEditService` (edit-after-pay within 30 min)** → Story 4.4. Story 4.1 ships `Order` + `OrderPriceSnapshot`; edit-after-pay adds the `amendOrder` use case with optimistic concurrency.
- **User-visible timeline endpoint** (`GET /bff/storefront/order/{id}` with `[{ state, timestamp }]`) → Story 4.3. Story 4.1 ships the `findByOrderUuidOrderByIdAsc` repository method that Story 4.3 reads.
- **Carrier adapters (GHN, GHTK, Viettel Post)** → Stories 4.5 / 4.6. Out of scope this story.
- **Real `dev/.env` file** → Story 3.3 deferred; same workaround (env vars via `application-dev.yml`) applies.
- **Per-order HMAC isolation** (a separate key per `orderUuid`) → Story 5.x (multi-tenant). v1 uses a single `order` service key.
- **Full saga FSM with retry + timeout** (per Story 2.5's `OrderSagaOrchestrator`) → Story 4.2 ships the saga wiring; Story 4.1 ships the data model only.
- **Postgres CDC event sourcing** (Debezium-style full event replay from `order_state_transition`) → Epic 10 (observability). Story 4.1 ships the table; Epic 10 wires the CDC pipeline.

## Tasks / Subtasks

- [ ] **Task 1 — Bootstrap `services/order` Maven module** (AC: #1)
  - [ ] Modify `services/order/pom.xml`: change `<packaging>pom</packaging>` to `<packaging>jar</packaging>`; add dependencies per AC #1.
  - [ ] Create `services/order/src/main/java/vn/vnpt/order/OrderApplication.java` with `@SpringBootApplication(scanBasePackages = "vn.vnpt.order") @ApplicationModule(displayName = "order")`.
  - [ ] Create `services/order/src/main/resources/application.yml` per AC #10 (port 8087, datasource, Modulith exclusions, management.endpoints).
  - [ ] Create `services/order/src/main/resources/logback-spring.xml` per AC #10 (include util's logback-include.xml + REDACTING_CONSOLE).
  - [ ] Create `services/order/src/main/resources/application-dev.yml` (sk_test_dev_placeholder-style — empty for now, just `order: dev` markers).
  - [ ] Verify `mvn -pl services/order -am validate -o` succeeds; module count stays at 19 (services/order already in the root pom).

- [ ] **Task 2 — V001 Flyway migration: order_state_transition + order_price_snapshot** (AC: #2)
  - [ ] Create `services/order/src/main/resources/db/migration/order/V001__create_order_event_log.sql` with the two tables + indexes per AC #2.
  - [ ] Verify `mvn -pl services/order flyway:info -o` lists V001 as `Pending`.

- [ ] **Task 3 — JPA entities + repositories** (AC: #3, #4, #6)
  - [ ] `services/order/src/main/java/vn/vnpt/order/infrastructure/entity/OrderStateTransition.java` per AC #4.
  - [ ] `services/order/src/main/java/vn/vnpt/order/infrastructure/entity/OrderPriceSnapshot.java` per AC #3.
  - [ ] `services/order/src/main/java/vn/vnpt/order/infrastructure/repository/OrderStateTransitionRepository.java` per AC #6.
  - [ ] `services/order/src/main/java/vn/vnpt/order/infrastructure/repository/OrderPriceSnapshotRepository.java` per AC #6.

- [ ] **Task 4 — Domain types: `Order` + `OrderState`** (AC: #5)
  - [ ] `services/order/src/main/java/vn/vnpt/order/domain/OrderState.java` — enum `PLACED, PAID, ALLOCATED, PACKING, PACKED, SHIPPED, DELIVERED` (forward-compatible; Story 4.1 only emits `PLACED`).
  - [ ] `services/order/src/main/java/vn/vnpt/order/domain/Order.java` — record `(long orderUuid, OrderState state, OrderPriceSnapshot priceSnapshot, long version)`.
  - [ ] `services/order/src/main/java/vn/vnpt/order/domain/OrderTransitionValidator.java` — validates allowed edges; for Story 4.1 only `PLACED` is exercised; the validator is forward-compatible (throw `IllegalStateException` for now on any other transition; Story 4.2 will add the validator logic).

- [ ] **Task 5 — `AppendOrderTransitionUseCase`** (AC: #5)
  - [ ] `services/order/src/main/java/vn/vnpt/order/application/port/AppendOrderTransitionCommand.java` — record `(long orderUuid, OrderState toState, String sagaStep, OrderPriceSnapshot priceSnapshot)`.
  - [ ] `services/order/src/main/java/vn/vnpt/order/application/port/OrderTransitionAppender.java` — interface (the seam between use case and outbox/persistence).
  - [ ] `services/order/src/main/java/vn/vnpt/order/application/usecase/AppendOrderTransitionUseCase.java` — `@Service @Transactional`; constructor-injects the repositories + the appender; orchestrates: validate → insert transition → insert price snapshot (if absent) → publish to outbox.

- [ ] **Task 6 — HMAC-signed outbox publisher** (AC: #7)
  - [ ] `services/order/src/main/java/vn/vnpt/order/infrastructure/outbox/OrderModulithOutboxPublisher.java` — extends `ModulithOutboxPublisher`, overrides `signaturesFor(...)` with the order's HMAC key.
  - [ ] Reuse the `HmacServiceKeyProvider` pattern from Story 3.5: `services/order/.../infrastructure/security/HmacServiceKeyProvider.java` (interface) + `VaultHmacKeyProvider` (`@Profile("!dev")`) + `DevHmacKeyProvider` (`@Profile("dev")`, reads `HMAC_SERVICE_SECRET_ORDER` env var).

- [ ] **Task 7 — REST controller (POST append + GET history)** (AC: #8)
  - [ ] `services/order/src/main/java/vn/vnpt/order/application/web/OrderController.java` — `@RestController @RequestMapping("/api/orders")`; `@PostMapping` accepts `AppendOrderTransitionCommand`, delegates to `AppendOrderTransitionUseCase`, returns 201 + transition ID; `@GetMapping("/{orderUuid}")` returns the full transition history from `OrderStateTransitionRepository.findByOrderUuidOrderByIdAsc(...)`. Trust-boundary validation lives in the command's compact constructor (matching the codebase's per-service pattern from Story 3.1 + 3.3).

- [ ] **Task 8 — Tests** (AC: #5, #6, #7, #8)
  - [ ] `services/order/src/test/java/vn/vnpt/order/domain/OrderStateTransitionTest.java` — 3 tests: `state_PLACED_isValidInitialState`, `state_PLACED_to_PAID_isAllowedTransition` (forward-compat), `state_PLACED_to_SHIPPED_isRejectedForStory41` (validator stub).
  - [ ] `services/order/src/test/java/vn/vnpt/order/application/usecase/AppendOrderTransitionUseCaseTest.java` — 4 tests: `execute_appendsTransitionAndSnapshotOnFirstCall`, `execute_appendsOnlyTransitionOnSubsequentCalls`, `execute_throwsOnNullSagaStep`, `execute_throwsOnImmutablePriceSnapshotUpdateAttempt`.
  - [ ] `services/order/src/test/java/vn/vnpt/order/infrastructure/entity/OrderStateTransitionRepositoryIT.java` — `@SpringBootTest + @Testcontainers Postgres` (per codebase convention; mirrors `WebhookDedupRepositoryTest` from Story 3.2). 3 tests: `findFirstByOrderUuidOrderByIdDesc_returnsLatest`, `findByOrderUuidOrderByIdAsc_returnsFullHistory`, `appendMultipleTimes_appendsInOrder` (asserts `order_state_transition` has 2 rows for the same orderUuid with monotonically increasing IDs).
  - [ ] `services/order/src/test/java/vn/vnpt/order/infrastructure/security/OrderHmacServiceKeyProviderTest.java` — 2 tests: `devProvider_returnsConsistentSecretAcrossCalls`, `devProvider_usesOrderSpecificEnvVar` (reads `HMAC_SERVICE_SECRET_ORDER`, not the payment's `HMAC_SERVICE_SECRET`).

- [ ] **Task 9 — ArchUnit boundary tests** (AC: #9)
  - [ ] `services/order/src/test/java/vn/vnpt/order/OrderPortContractTest.java` — extends the same pattern as `PaymentPortContractTest.java` (per AC #9).

- [ ] **Task 10 — Runtime smoke script** (AC: #8)
  - [ ] `dev/scripts/smoke-order-4-1.sh` — bash. Pattern mirrors `smoke-payment-3-5.sh`:
        - (a) Free port 8087 (lsof fallback).
        - (b) Start `services/order` with `SPRING_PROFILES_ACTIVE=dev` + `HMAC_SERVICE_SECRET_ORDER=$(openssl rand -hex 32)`.
        - (c) Wait for `/actuator/health` UP (up to 90s).
        - (d) POST a `PLACED` transition via curl; assert HTTP 201 + transition ID returned.
        - (e) Verify `order_state_transition` + `order_price_snapshot` tables have 1 row each via `docker exec postgres psql`.
        - (f) POST a `PAID` transition for the same orderUuid; assert HTTP 201.
        - (g) Verify `order_state_transition` has 2 rows for the orderUuid.
        - (h) GET `/api/orders/{orderUuid}`; assert the response includes 2 transitions.
        - (i) Verify `outbox` table has ≥1 row with non-null `signatures` column (HMAC contract).
        - (j) Kill the process, exit 0.

## Dev Notes

### Implementation Notes

- **`services/order/` is a freshly-bootstrapped module** (the existing `pom.xml` is a stub with packaging `pom`). The dev agent must change packaging to `jar` and add all the dependencies. The codebase pattern for service modules is in `services/payment/pom.xml` (mirror it).
- **Per-service database**: `order_db` per ADR-03. The dev docker-compose has Postgres already (verified by Story 0.3's setup). The dev agent must add `POSTGRES_ORDER_DB=order_db` + `POSTGRES_ORDER_USER=order_user` + `POSTGRES_ORDER_PASSWORD=order_pass` to `dev/.env` (if it exists) or to `application.yml` defaults (the dev `application-dev.yml` carries the literal values per the codebase convention).
- **Append-only is at the application layer, not the DB layer.** Postgres natively supports row-level UPDATE; the immutability of `order_price_snapshot` is enforced by the use case never calling `save()` on an existing entity. A future story may add a `BEFORE UPDATE` trigger to enforce at the DB level (defense in depth). The smoke test asserts the API surface has no update path (AC #8f).
- **`order_state_transition` is the canonical append-only log.** Every state change appends; `from_state` is nullable for the genesis event. The `event_id` column is unique (Snowflake) to support exactly-once-write semantics even under concurrent appends (the unique constraint rejects duplicates at the DB level — the application catches `DataIntegrityViolationException` and re-reads the existing row, similar to Story 3.2's `webhook_dedup` pattern).
- **HMAC key isolation between services**: payment uses `HMAC_SERVICE_SECRET`; order uses `HMAC_SERVICE_SECRET_ORDER`. This is the simplest isolation; per-tenant isolation lands in Story 5.x. The dev profile generates a random key if the env var is missing.
- **Modulith outbox mirror**: order's `OrderModulithOutboxPublisher` mirrors Story 3.5's `PaymentModulithOutboxPublisher` exactly — extends `ModulithOutboxPublisher`, overrides `signaturesFor(...)` with the order's HMAC key. The 15 util HMAC/JCS tests cover the primitive; the order-side test is just the wiring (1 test in `OrderModulithOutboxPublisherHmacTest.java` if budget allows).
- **Test count target** — `≥ 12 new tests` (state transition 3 + use case 4 + repo IT 3 + key provider 2 = 12). Order service baseline: 0 (fresh module); target after Story 4.1: ≥ 12.
- **Module count** — `mvn validate` still reports 19 modules (services/order was already in the root pom as a stub).
- **The `application.yml` mirrors `services/payment/src/main/resources/application.yml`** for consistency (server.port, allow-bean-definition-overriding, autoconfigure excludes for util's `UtilsAutoConfiguration` + Modulith JDBC bridge). Same flyway sub-folder pattern (`db/migration/order/`).
- **The dev `.env` triple (POSTGRES_ORDER_DB / _USER / _PASSWORD)** is documented in `local-docs/00..10.md`; if `dev/.env` doesn't exist, the dev values are hardcoded in `application-dev.yml`.

### Debug Log References

(none — fresh story; debugs land in Dev Notes / Completion Notes after the dev agent runs)

### Completion Notes List

(empty — populated by dev-story)

### Deviations from literal story text (flagged for review)

(none — fresh story; deviations land here after dev-story)

## Dev Notes (post-dev-story section — populated after implementation)

### Project Structure Notes

- **Path placement** (per architecture §6 / `architecture.md:298` + `:924` + `:930`):
  - `services/order/pom.xml` ← change to jar + add deps (Task 1)
  - `services/order/src/main/java/vn/vnpt/order/OrderApplication.java` ← new (Task 1)
  - `services/order/src/main/java/vn/vnpt/order/domain/Order.java` + `OrderState.java` + `OrderTransitionValidator.java` ← new (Task 4)
  - `services/order/src/main/java/vn/vnpt/order/infrastructure/entity/OrderStateTransition.java` + `OrderPriceSnapshot.java` ← new (Task 3)
  - `services/order/src/main/java/vn/vnpt/order/infrastructure/repository/OrderStateTransitionRepository.java` + `OrderPriceSnapshotRepository.java` ← new (Task 3)
  - `services/order/src/main/java/vn/vnpt/order/application/port/AppendOrderTransitionCommand.java` + `OrderTransitionAppender.java` ← new (Task 5)
  - `services/order/src/main/java/vn/vnpt/order/application/usecase/AppendOrderTransitionUseCase.java` ← new (Task 5)
  - `services/order/src/main/java/vn/vnpt/order/infrastructure/outbox/OrderModulithOutboxPublisher.java` ← new (Task 6)
  - `services/order/src/main/java/vn/vnpt/order/infrastructure/security/HmacServiceKeyProvider.java` + `VaultHmacKeyProvider.java` + `DevHmacKeyProvider.java` ← new (Task 6)
  - `services/order/src/main/java/vn/vnpt/order/application/web/OrderController.java` ← new (Task 7)
  - `services/order/src/main/resources/application.yml` + `application-dev.yml` + `logback-spring.xml` ← new (Task 1)
  - `services/order/src/main/resources/db/migration/order/V001__create_order_event_log.sql` ← new (Task 2)
  - `services/order/src/test/java/vn/vnpt/order/...` ← new tests (Task 8)
  - `services/order/src/test/java/vn/vnpt/order/OrderPortContractTest.java` ← new ArchUnit (Task 9)
  - `dev/scripts/smoke-order-4-1.sh` ← new (Task 10)

- **Detected conflicts / variances (with rationale):**
  - **Append-only is at the application layer only** (AC #2 comment). Future hardening: add a Postgres `BEFORE UPDATE` trigger to enforce at the DB layer.
  - **`order_state_transition.event_id` UNIQUE constraint** is the dedup primitive (rejects duplicate appends). Mirrors `processed_event.event_id` (Story 1.3 / 3.2 pattern).
  - **`OrderState` enum is forward-compatible** (Story 4.1 only emits `PLACED`; validator is a stub for other transitions).
  - **`order_db` per ADR-03** — separate from `payment_db`, `checkout_db`, etc. (verify which other services own which DBs).
  - **HMAC key env var is `HMAC_SERVICE_SECRET_ORDER`** (not `HMAC_SERVICE_SECRET`) — per-service isolation.

### References

- [Source: `_bmad-output/planning-artifacts/epics.md:707-718` — Story 4.1 AC]
- [Source: `_bmad-output/planning-artifacts/prd.md:80-82` — FR-30 verbatim: "Order aggregate is **append-only event log**; current state is a projection. Replayable for debugging (brainstorming `[ORD-S]`)"]
- [Source: `_bmad-output/planning-artifacts/prd.md:82` — FR-31: "order.priceSnapshot is a JSONB column capturing list price, applied promotion(s), tax, shipping at order time; immutable after order.placed"]
- [Source: `_bmad-output/planning-artifacts/architecture.md:212` — ADR-03: database-per-service]
- [Source: `_bmad-output/planning-artifacts/architecture.md:221` — ADR-12: "Saga = single Modulith module; saga is intra-process, NOT network"]
- [Source: `_bmad-output/planning-artifacts/architecture.md:263` — Sprint 4: "OrderService + ShipmentService + carrier adapters. FR-30 to FR-39"]
- [Source: `_bmad-output/planning-artifacts/architecture.md:299` — "Order state transition log: id, order_uuid, from_state, to_state, saga_step, event_id, created_at"]
- [Source: `_bmad-output/planning-artifacts/architecture.md:675` — "Saga = intra-process state machine on aggregate | ADR-12"]
- [Source: `_bmad-output/planning-artifacts/architecture.md:745` — `services/order/` (per architecture §Project Structure)]
- [Source: `_bmad-output/planning-artifacts/architecture.md:905` — "FR-30 to FR-34 | `services/order/` | Event-sourced order, price-snapshot, post-payment lifecycle, edit-after-pay"]
- [Source: `_bmad-output/planning-artifacts/architecture.md:1030` — "FR-30 to FR-34 (Order) | services/order/ | ✓"]
- [Source: `services/payment/.../infrastructure/outbox/PaymentModulithOutboxPublisher.java` — Story 3.5 producer pattern (mirror for order)]
- [Source: `services/payment/.../infrastructure/security/HmacServiceKeyProvider.java` + `VaultHmacKeyProvider.java` + `DevHmacKeyProvider.java` — Story 3.5 HMAC key pattern (mirror for order)]
- [Source: `services/payment/src/main/resources/application.yml` — per-service config pattern (mirror for order)]
- [Source: `services/payment/.../logback-spring.xml` — logback + PanRedactingAppender wiring (mirror for order)]
- [Source: `services/payment/.../PaymentPortContractTest.java` — ArchUnit boundary test pattern (mirror for order)]
- [Source: `util/.../events/HmacEventSigner.java` + `JcsCanonicalJson.java` + `ModulithOutboxPublisher.java` — primitives (no new code; reuse verbatim)]
- [Source: `util/.../archunit/RequestBodyLoggerDenyListTest.java` — repo-wide R-15 deny-list (Story 4.1 inherits for free)]
- [Source: `services/payment/.../test/.../WebhookDedupRepositoryTest.java` — `@SpringBootTest + @Testcontainers Postgres` pattern (mirror for order's `OrderStateTransitionRepositoryIT`)]
- [Source: project memory `runtime-smoke-rule.md` — every dev-story must runtime-smoke
- [Source: project memory `deep-review-rules.md` — F1: shared code in util/, 1-sentence javadoc, no single-impl abstractions
- [Source: project memory `dev-agent-personas.md` — adopt both `skills/backend-developer.md` + `skills/spring-boot-engineer.md`

## Dev Agent Record

### Agent Model Used

{{agent_model_name_version}}

### Debug Log References

### Completion Notes List

### File List