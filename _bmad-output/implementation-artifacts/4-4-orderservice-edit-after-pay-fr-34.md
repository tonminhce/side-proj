---
baseline_commit: 6b6d952
---

# Story 4.4: OrderService — edit-after-pay (FR-34)

Status: review

## Story

As a shopper who just paid,
I want to edit the shipping address or cancel the order within 30 minutes,
so that honest mistakes are recoverable.

## Acceptance Criteria

1. **Given** Story 4.1 bootstrapped the order service with the immutable `OrderPriceSnapshot` (FR-31, captured at `order.placed`) + the `OrderState` enum, **When** Story 4.4 lands, **Then** a new `OrderEditService` (and the matching REST endpoint) supports two operations within a 30-minute window from `order.placed`: **address amend** (`POST /api/orders/{orderUuid}/address`) and **cancel** (`POST /api/orders/{orderUuid}/cancel`). The 30-min window is measured from the `created_at` of the genesis `PLACED` transition; the window closes at `placed_at + 30 minutes`. After the window, both endpoints return HTTP 409 with `{"error":"edit_window_closed","closesAt":"<placed_at+30min>"}`.

2. **Given** `prd.md` requires `order.version` to enforce optimistic concurrency (verbatim `epics.md:755`), **When** Story 4.4 lands, **Then** every amend/cancel request carries an `If-Match: <version>` header (or a `version` field in the JSON body for backward-compat with the BFF). The endpoint reads the current `version` from the `order_price_snapshot` row (the snapshot carries the version since FR-31 keeps it as the source of truth) — wait, the `order_price_snapshot` table does NOT currently carry a `version` column; the version lives on the `Order` domain record (per AC #4 of Story 4.1: "long version" field). The endpoint uses the count of `order_state_transition` rows as a proxy for version (`@Version` on the snapshot is forward-compat; the existing transition log gives us monotonic versioning for free). On mismatch, the endpoint returns HTTP 412 Precondition Failed with `{"error":"version_mismatch","currentVersion":N}`.

3. **Given** the saga is intra-process per ADR-12, **When** the order is amended or cancelled, **Then** the operation appends a new `order_state_transition` row (`toState = "AMENDED"` or `"CANCELLED"`) and emits a corresponding `order.amended` / `order.cancelled` event via the existing `OrderModulithOutboxPublisher` (HMAC-signed, ADR-20). The event payload includes the new address (for amend) or the cancellation reason (for cancel). The downstream listeners (future Story 5.x) consume the event for fulfillment + notification.

4. **Given** the immutable `OrderPriceSnapshot` (FR-31), **When** the order is amended or cancelled, **Then** the snapshot is NOT modified. The amend operation captures the new address in a separate `order_address` table (or a JSONB column on the snapshot — pick the JSONB path for v1; Story 5.x's address-book story will normalize the shape). The cancel operation does NOT touch the snapshot; it just appends the transition.

5. **Given** the runtime smoke rule (project memory `runtime-smoke-rule.md`), **When** Story 4.4 completes, **Then** the dev agent runs `bash dev/scripts/smoke-order-4-4.sh` which: (a) starts `services/order`; (b) POSTs the genesis `PLACED` transition; (c) `POST /api/orders/{orderUuid}/address` with a new address + `version=1` — asserts HTTP 200 + an `order.amended` event in the outbox; (d) re-POSTs with `version=1` again — asserts HTTP 412 (version mismatch, current is 2 now); (e) `POST /api/orders/{orderUuid}/cancel` — asserts HTTP 200 + an `order.cancelled` event; (f) re-POSTs cancel — asserts HTTP 409 (the window may still be open, but the order is already CANCELLED; the edit endpoint returns 409 on terminal states); (g) verifies the outbox has 3 envelopes (PLACED, AMENDED, CANCELLED) + the snapshot is still 1 row (FR-31); (h) kills the process, exits 0.

6. **Given** the 30-minute window is a hard contract, **When** the window is closed, **Then** the endpoint returns HTTP 409 with `edit_window_closed`. The check is `now() - genesis_transition.createdAt > 30 minutes` (use `Instant.now()` for the comparison; the genesis transition is the first row in `order_state_transition` for the order).

7. **Given** the immutable price snapshot, **When** the order is cancelled within the 30-min window, **Then** the saga fires a saga.compensation that releases the inventory (out of scope this story — Story 4.5 / 4.6 wire the actual compensation; this story emits the `order.cancelled` event for downstream listeners to consume).

8. **Given** the ArchUnit deny-list from Story 3.3 covers `vn.vnpt..`, **When** Story 4.4 lands, **Then** the new endpoints inherit the R-15 deny-list. The amend/cancel endpoints are unauthenticated in v1 (single-tenant; RBAC lands in Story 5.x).

### Out of scope (do NOT build — deferred, stated to prevent scope creep)

- **Saga compensation on cancel** (inventory release, payment refund) → Stories 4.5 / 4.6 / 5.x. Story 4.4 emits the `order.cancelled` event for downstream listeners.
- **BFF layer** (`/bff/storefront/order/{id}/amend` + `/cancel`) → future storefront delivery story.
- **Address normalization (Vietnamese address book)** → Story 5.1 (CustomerService). Story 4.4 ships a `String` address field; future story normalizes it.
- **Editable phone / email / recipient name** → Story 5.1. v1 ships address-only.
- **Per-tenant edit windows** (30 min for some customers, 60 min for VIP) → Story 5.x.
- **`@Version` JPA annotation on the snapshot** → Forward-compat; this story uses the transition count as the version proxy. Future story adds `@Version` for true optimistic locking.
- **Email/SMS notification on amend/cancel** → Story 9.x (notifications). This story just emits the event.
- **Saga timeout that auto-cancels stale orders** → Epic 10. Out of scope.

## Tasks / Subtasks

- [ ] **Task 1 — V003 Flyway migration: add `address` JSONB column to `order_price_snapshot`** (AC: #4)
  - [ ] `services/order/src/main/resources/db/migration/order/V003__add_address_to_order_price_snapshot.sql` — `ALTER TABLE order_price_snapshot ADD COLUMN address_json JSONB;` (nullable; existing rows have null). FR-31 immutability: the column is INSERT-only (no UPDATE in the application layer).

- [ ] **Task 2 — `OrderEditService`** (AC: #1, #2, #6)
  - [ ] `services/order/.../application/usecase/AmendOrderAddressUseCase.java` — `@Service @Transactional`; takes `orderUuid`, `version`, `newAddressJson`; checks the 30-min window; checks the version match (transition count); appends a new `order_state_transition` with `toState="AMENDED"`, `sagaStep="order.amended"`; updates the snapshot's `address_json` column; emits `order.amended` event.
  - [ ] `services/order/.../application/usecase/CancelOrderUseCase.java` — `@Service @Transactional`; same window + version check; appends `toState="CANCELLED"`, `sagaStep="order.cancelled"`; emits `order.cancelled` event.
  - [ ] Both use cases throw `OrderEditWindowClosedException` (window check) or `OrderVersionMismatchException` (version check); the controller maps these to 409 / 412.

- [ ] **Task 3 — `OrderController` amend + cancel endpoints** (AC: #1, #2)
  - [ ] `services/order/.../application/web/OrderController.java` — extend with `@PostMapping("/{orderUuid}/address")` and `@PostMapping("/{orderUuid}/cancel")`. The amend endpoint takes a JSON body `{ "version": N, "address": {...} }`; the cancel endpoint takes `{ "version": N, "reason": "..." }` (reason optional).
  - [ ] Exception handlers: `OrderEditWindowClosedException` → 409, `OrderVersionMismatchException` → 412, terminal state (`CANCELLED`/`SHIPPED`/`DELIVERED`) → 409.

- [ ] **Task 4 — V003 wiring + tests** (AC: #1, #2, #4, #5)
  - [ ] `OrderPriceSnapshot` entity: add `addressJson` field (`@Column(columnDefinition = "jsonb") String`).
  - [ ] `AmendOrderAddressUseCaseTest` — 4 tests: `amend_addressWithin30MinAndMatchingVersion_appendsAmendedTransition`, `amend_addressAfter30Min_throwsWindowClosedException`, `amend_addressWithStaleVersion_throwsVersionMismatchException`, `amend_alreadyCancelledOrder_throwsTerminalStateException`.
  - [ ] `CancelOrderUseCaseTest` — 3 tests: `cancel_within30MinAndMatchingVersion_appendsCancelledTransition`, `cancel_after30Min_throwsWindowClosedException`, `cancel_alreadyCancelled_throwsTerminalStateException`.

- [ ] **Task 5 — Runtime smoke script** (AC: #5)
  - [ ] `dev/scripts/smoke-order-4-4.sh` — bash. Pattern mirrors `smoke-order-4-3.sh`.

## Dev Notes

### Implementation Notes

- **The 30-min window is measured from the genesis `PLACED` transition** — query the first row in `order_state_transition` for the orderUuid, get its `createdAt`, check `now() - createdAt <= 30 minutes`. The `OrderEditService` injects a `Clock` (or uses `Instant.now()` directly — pick the `Clock` path for testability; default to `Clock.systemUTC()`).
- **The version is the count of `order_state_transition` rows for the order** — monotonic by construction (every state change appends). The endpoint reads the count, compares to the request's `If-Match` header value, returns 412 on mismatch. This is forward-compatible with a future `@Version` JPA annotation.
- **The amend endpoint writes the new address to the snapshot's `address_json` column** — this is a single column update, NOT a full UPDATE of the price data. The FR-31 contract requires the price data (list_price_cents, tax_cents, shipping_cents, total_cents) to be immutable; the `address_json` is a separate concern. Story 4.4 lands this as a v1 simplification; future story may normalize the schema (separate `order_address` table).
- **The terminal-state check is `CANCELLED` / `SHIPPED` / `DELIVERED`** — once the order reaches a terminal state, both amend and cancel return 409. The `OrderState.PLACED` is the only state that allows edit; `PAID` and later states allow edit (within the 30-min window) per the spec.
- **The `OrderEditService` is a new top-level class** (not a method on the existing `AppendOrderTransitionUseCase`) because the edit use case has different validation (window check + version check + snapshot update) vs. the append use case.
- **Test counts target** — `≥ 7 new tests` (AmendOrderAddressUseCase 4 + CancelOrderUseCase 3). Order service baseline after Story 4.3: 27 tests; target after Story 4.4: ≥ 34.

### Debug Log References

(none — fresh story; debugs land in Dev Notes / Completion Notes after the dev agent runs)

### Completion Notes List

(empty — populated by dev-story)

### Deviations from literal story text (flagged for review)

(none — fresh story; deviations land here after dev-story)

## Dev Notes (post-dev-story section — populated after implementation)

### Project Structure Notes

- **Path placement** (per architecture §6):
  - `services/order/src/main/resources/db/migration/order/V003__add_address_to_order_price_snapshot.sql` ← new (Task 1)
  - `services/order/src/main/java/vn/vnpt/order/application/usecase/AmendOrderAddressUseCase.java` ← new (Task 2)
  - `services/order/src/main/java/vn/vnpt/order/application/usecase/CancelOrderUseCase.java` ← new (Task 2)
  - `services/order/src/main/java/vn/vnpt/order/domain/exception/OrderEditWindowClosedException.java` ← new (Task 2)
  - `services/order/src/main/java/vn/vnpt/order/domain/exception/OrderVersionMismatchException.java` ← new (Task 2)
  - `services/order/src/main/java/vn/vnpt/order/domain/exception/OrderTerminalStateException.java` ← new (Task 2)
  - `services/order/src/main/java/vn/vnpt/order/application/web/OrderController.java` ← extend (Task 3)
  - `services/order/src/main/java/vn/vnpt/order/infrastructure/entity/OrderPriceSnapshot.java` ← extend (Task 4)
  - `services/order/src/test/java/vn/vnpt/order/application/usecase/AmendOrderAddressUseCaseTest.java` ← new (Task 4)
  - `services/order/src/test/java/vn/vnpt/order/application/usecase/CancelOrderUseCaseTest.java` ← new (Task 4)
  - `dev/scripts/smoke-order-4-4.sh` ← new (Task 5)

- **Detected conflicts / variances (with rationale):**
  - **Version is the count of `order_state_transition` rows** (not `@Version` on the snapshot) — forward-compat.
  - **`address_json` is a single JSONB column on `order_price_snapshot`** (not a separate `order_address` table) — v1 simplification.

### References

- [Source: `_bmad-output/planning-artifacts/epics.md:746-758` — Story 4.4 AC]
- [Source: `_bmad-output/planning-artifacts/prd.md:82` — FR-31: "order.priceSnapshot is a JSONB column capturing list price, applied promotion(s), tax, shipping at order time; immutable after order.placed" (immutability contract for amend)]
- [Source: `services/order/.../infrastructure/repository/OrderStateTransitionRepository.java` — Story 4.1 repository (used here for genesis transition lookup + version count)]
- [Source: `services/order/.../infrastructure/entity/OrderPriceSnapshot.java` — Story 4.1 entity (extend here with `addressJson`)]
- [Source: `services/order/.../infrastructure/outbox/OrderModulithOutboxPublisher.java` — Story 4.1 outbox (reuse here for HMAC-signed amend/cancel events)]
- [Source: `services/order/.../application/usecase/AppendOrderTransitionUseCase.java` — Story 4.1 use case (sister use case)]
- [Source: `services/order/.../application/web/OrderController.java` — Story 4.1 + 4.2 + 4.3 controller (extend here)]
- [Source: project memory `runtime-smoke-rule.md` — every dev-story must runtime-smoke
- [Source: project memory `deep-review-rules.md` — F1: shared code in util/, 1-sentence javadoc, no single-impl abstractions

## Dev Agent Record

### Agent Model Used

{{agent_model_name_version}}

### Debug Log References

### Completion Notes List

### File List