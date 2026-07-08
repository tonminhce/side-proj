---
baseline_commit: 6b6d952
---

# Story 5.6: Loyalty points (FR-50)

Status: review

## Story

As a shopper,
I want loyalty points accrued per order, redeemable at checkout,
So that I have an incentive to return.

## Acceptance Criteria

1. **Given** FR-50 mandates "Loyalty points accrued per order, redeemable at checkout" (`prd.md:147`), **When** Story 5.6 lands, **Then** the order service grows a `LoyaltyAccount` aggregate (id, customerId, points, updatedAt) + a `LoyaltyAccrualEvent` record (id, orderUuid, customerId, points, createdAt). The accrual happens on the `PAID` transition (per `prd.md:869` "When the order is PAID"). The accrual formula is `floor(order_total_cents * 0.01)` per the AC verbatim. The endpoints are: `GET /api/orders/{orderUuid}/loyalty` (returns the points for that order) + `GET /api/orders/{customerId}/loyalty-account` (returns the customer's total points).
2. **Given** AC #1 mandates "at checkout I can apply points as a discount line item" (`prd.md:870`), **When** Story 5.6 lands, **Then** the order service's `AppendOrderTransitionUseCase` (or a sibling use case) accepts a `pointsApplied` field in `AppendOrderTransitionCommand`. When set, the order's effective total = `totalCents - pointsApplied` (the points are redeemed 1:1 against VND; the future story may add a configurable rate). v1 ships the data path; the checkout UI that calls this is deferred.
3. **Given** the runtime smoke rule (project memory `runtime-smoke-rule.md`), **When** Story 5.6 completes, **Then** the dev agent runs `bash dev/scripts/smoke-order-5-6.sh` which: (a) starts `services/order`; (b) creates a `customerId=99` loyalty account (auto-created on first accrual); (c) POSTs a `PLACED` + `PAID` transition pair (with a price snapshot of `totalCents=10000` — i.e., 100000 VND); (d) `GET /api/orders/{orderUuid}/loyalty` — assert HTTP 200 + `points: 100` (10000 cents × 0.01 = 100 points); (e) `GET /api/orders/{customerId=99}/loyalty-account` — assert HTTP 200 + `points: 100`; (f) kill the process, exit 0.
4. **Given** the `LoyaltyAccount.points` is monotonic, **When** Story 5.6 lands, **Then** the schema has no `points_spent` column (the redemption is tracked separately as a `LoyaltyRedemption` row; v1 ships the accrual path only). The redemption flow lands with a future story when the checkout UI lands. The smoke asserts the accrual path only.

### Out of scope (do NOT build — deferred, stated to prevent scope creep)

- **Redemption flow** (applying points at checkout) → Future story. v1 ships the data model + accrual; the redemption is the same data path with a negative-points row.
- **Cross-service event** (`order.paid` → `loyalty.accrued`) → Future story (Modulith outbox wiring). v1 in-process: the `PaidOrderTransitionListener` runs in the same Spring context.
- **Tiered loyalty** (silver/gold/platinum with multipliers) → Future story. v1 is a flat 1% accrual.
- **Points expiration** (24-month rolling expiry) → Future ops concern.
- **Loyalty ledger / event sourcing** → Future story. v1 uses a simple `points` column on `LoyaltyAccount`.

## Tasks / Subtasks

- [ ] **Task 1 — V004 Flyway migration: `loyalty_account` + `loyalty_accrual`** (AC: #1, #4)
  - [ ] `services/order/src/main/resources/db/migration/order/V004__create_loyalty_tables.sql` — `loyalty_account` (id, customer_id BIGINT UNIQUE, points BIGINT NOT NULL DEFAULT 0, updated_at TIMESTAMP) + `loyalty_accrual` (id, order_uuid BIGINT, customer_id BIGINT, points INT, created_at TIMESTAMP).

- [ ] **Task 2 — JPA entities + repositories** (AC: #1)
  - [ ] `services/order/.../infrastructure/entity/LoyaltyAccountEntity.java` + `LoyaltyAccrualEntity.java`.
  - [ ] `services/order/.../infrastructure/repository/LoyaltyAccountRepository.java extends JpaRepository<LoyaltyAccountEntity, Long>` with `Optional<LoyaltyAccountEntity> findByCustomerId(Long customerId)`.
  - [ ] `services/order/.../infrastructure/repository/LoyaltyAccrualRepository.java extends JpaRepository<LoyaltyAccrualEntity, Long>` with `Optional<LoyaltyAccrualEntity> findByOrderUuid(Long orderUuid)`.

- [ ] **Task 3 — `LoyaltyService` + use case** (AC: #1, #2)
  - [ ] `services/order/.../application/usecase/AccrueLoyaltyPointsUseCase.java` — listens for `PAID` transitions (via the existing saga / outbox pattern from Story 4.2); creates the `LoyaltyAccrual` row + increments the `LoyaltyAccount.points`.
  - [ ] `services/order/.../application/usecase/GetLoyaltyAccountUseCase.java` — returns the account for a customer.
  - [ ] `services/order/.../application/usecase/GetLoyaltyForOrderUseCase.java` — returns the accrual for a specific order.

- [ ] **Task 4 — REST endpoints** (AC: #1)
  - [ ] `services/order/.../application/web/OrderController.java` — add `@GetMapping("/{orderUuid}/loyalty")` + `@GetMapping("/{customerId}/loyalty-account")`.

- [ ] **Task 5 — Tests** (AC: #1)
  - [ ] `AccrueLoyaltyPointsUseCaseTest` — 3 tests: `accrue_floorTotalCentsDividedBy100`, `accrue_createsAccountIfMissing`, `accrue_idempotentForDuplicateOrder`.
  - [ ] `GetLoyaltyAccountUseCaseTest` — 2 tests: `execute_returnsAccountForCustomer`, `execute_throwsOnUnknownCustomer`.

- [ ] **Task 6 — Runtime smoke script** (AC: #3)
  - [ ] `dev/scripts/smoke-order-5-6.sh` — bash. Mirrors `smoke-order-4-4.sh`.

## Dev Notes

### Implementation Notes

- **The order service already has the `AppendOrderTransitionUseCase` from Story 4.1** — the accrual use case is a sibling that fires on the `PAID` transition. The trigger can be a direct method call from the `PAID` advance path in the controller (debug endpoint), or an in-process event listener. v1 uses a direct call to keep the test scope tight.
- **The accrual formula is `floor(totalCents * 0.01)`** — Java: `long points = orderTotalCents / 100;`. The `customerId` is passed via the `Order` aggregate (which exists from Story 4.1; the customer link is via `userId` — but `customerId` is more accurate; v1 uses `customerId` as a separate field on the Order aggregate for clarity, with a forward-compat migration to add it to `order_price_snapshot` if needed).
- **Test counts target** — `≥ 5 new tests` (AccrueLoyaltyPointsUseCase 3 + GetLoyaltyAccountUseCase 2). Order service baseline after Story 4.4: 34 tests; target after Story 5.6: ≥ 39.

### Debug Log References

(none — fresh story; debugs land in Dev Notes / Completion Notes after the dev agent runs)

### Completion Notes List

(empty — populated by dev-story)

### Deviations from literal story text (flagged for review)

(none — fresh story; deviations land here after dev-story)

## Dev Notes (post-dev-story section — populated after implementation)

### Project Structure Notes

- **Path placement** (per architecture §6):
  - `services/order/src/main/resources/db/migration/order/V004__create_loyalty_tables.sql` ← new (Task 1)
  - `services/order/src/main/java/vn/vnpt/order/infrastructure/entity/LoyaltyAccountEntity.java` + `LoyaltyAccrualEntity.java` ← new (Task 2)
  - `services/order/src/main/java/vn/vnpt/order/infrastructure/repository/LoyaltyAccountRepository.java` + `LoyaltyAccrualRepository.java` ← new (Task 2)
  - `services/order/src/main/java/vn/vnpt/order/application/usecase/AccrueLoyaltyPointsUseCase.java` + `GetLoyaltyAccountUseCase.java` + `GetLoyaltyForOrderUseCase.java` ← new (Task 3)
  - `services/order/src/main/java/vn/vnpt/order/application/web/OrderController.java` ← extend (Task 4)
  - `services/order/src/test/java/vn/vnpt/order/...` ← new tests (Task 5)
  - `dev/scripts/smoke-order-5-6.sh` ← new (Task 6)

- **Detected conflicts / variances (with rationale):**
  - **The order aggregate doesn't carry `customerId`** — Story 4.1's `Order` record has `userId` (the seam to the future auth service). Story 5.6 uses `userId` as the loyalty key (the future story wires the `customerId` link via the `userId → customerId` join). The accrual use case accepts `userId` from the caller (the smoke uses `userId=99`).
  - **No Modulith outbox wiring for the accrual event** — the accrual runs in-process (the `PAID` advance path calls the accrual use case directly in the same transaction). The outbox bridge for cross-service events lands with a future story.

### References

- [Source: `_bmad-output/planning-artifacts/epics.md:860-871` — Story 5.6 AC]
- [Source: `_bmad-output/planning-artifacts/prd.md:147` — FR-50]
- [Source: `services/order/.../application/usecase/AppendOrderTransitionUseCase.java` — Story 4.1 use case (sister; reused for the PAID trigger)]
- [Source: `services/order/.../application/web/OrderController.java` — Story 4.1 controller (extend here)]
- [Source: project memory `runtime-smoke-rule.md` — every dev-story must runtime-smoke
- [Source: project memory `deep-review-rules.md` — F1: shared code in util/, 1-sentence javadoc, no single-impl abstractions

## Dev Agent Record

### Agent Model Used

{{agent_model_name_version}}

### Debug Log References

### Completion Notes List

### File List