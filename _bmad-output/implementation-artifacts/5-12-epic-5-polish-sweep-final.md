---
baseline_commit: 641944c
---

# Story 5.12 (follow-up): Epic 5 final polish — 4-item sweep

Status: review

Closes the 4 remaining Epic 5 carry-forward items in `_bmad-output/backlog/deferred-issues.md`:

1. `LoyaltyAccrualEntity.points` INT → BIGINT (LOW)
2. `AppendOrderTransitionCommand.pointsApplied` field (MEDIUM, AC #2 gap)
3. `Pricebook.load()` fail-fast (LOW)
4. Pricing controller-level test (LOW)

Plus Move B handler narrowing: dropped broad `IllegalArgumentException → 404` in
favour of targeted `OrderSnapshotMissingException → 404` (root-cause fix).

## Acceptance Criteria

1. **V008 migration** — `ALTER TABLE loyalty_accrual ALTER COLUMN points TYPE BIGINT`. Entity field `Integer → Long`. `AccrueLoyaltyPointsUseCase.execute` returns `long`. Saga + controller callers updated. No data loss (existing rows convert cleanly).
2. **`AppendOrderTransitionCommand.pointsApplied`** — nullable `Long` field. Backward-compat ctor preserved (5-arg defaults to null). Compact constructor validates `pointsApplied >= 0`. Existing saga callers continue to use the 4-arg ctor (null pass-through).
3. **`Pricebook.load()` fail-fast** — `IllegalStateException` thrown on missing/malformed `pricebook.json` (was: log + swallow, leaving the service with zero entries). `load()` made public for cross-package test access.
4. **`PricingControllerTest`** — 2 new tests via `MockMvcBuilders.standaloneSetup`: 200 with JSON shape for known variant, 404 for unknown. +1 `PricebookTest.load_failsLoud` proves the throw contract.
5. **Move B handler narrowed** — broad `IllegalArgumentException` catch removed. `OrderController.accrueLoyalty` now throws the dedicated `OrderSnapshotMissingException` (already existed from Story 4.2). Targeted handler in `OrderEditExceptionHandler` → 404. Root-cause fix: no longer catches IAE from any controller in the order module.
6. **All tests green**: order 68/68 (unchanged), pricing 6/6 (was 3, +3), util 78/78 (unchanged), auth 28/28 (unchanged). Total 180 across touched modules.

## Out of scope (closed; no longer open)

- (none — all 4 deferred items closed this cycle)

## Files changed

**New:**
- `services/order/src/main/resources/db/migration/order/V008__loyalty_accrual_points_bigint.sql`
- `services/pricing/src/test/java/vn/vnpt/pricing/application/web/PricingControllerTest.java`

**Modified:**
- `services/order/src/main/java/vn/vnpt/order/infrastructure/entity/LoyaltyAccrualEntity.java` (Integer → Long)
- `services/order/src/main/java/vn/vnpt/order/application/usecase/AccrueLoyaltyPointsUseCase.java` (int → long throughout)
- `services/order/src/main/java/vn/vnpt/order/application/saga/PaymentCapturedOrderAdvancer.java` (int accrued → long accrued)
- `services/order/src/main/java/vn/vnpt/order/application/web/OrderController.java` (int → long + IAE → OrderSnapshotMissingException)
- `services/order/src/main/java/vn/vnpt/order/application/web/OrderEditExceptionHandler.java` (broad IAE removed, OrderSnapshotMissingException added)
- `services/order/src/main/java/vn/vnpt/order/application/port/AppendOrderTransitionCommand.java` (+pointsApplied field + backward-compat ctor)
- `services/pricing/src/main/java/vn/vnpt/pricing/domain/Pricebook.java` (fail-fast + load() public)
- `services/pricing/src/test/java/vn/vnpt/pricing/domain/PricebookTest.java` (+fail-fast test)
- `services/order/src/test/java/vn/vnpt/order/application/usecase/AccrueLoyaltyPointsUseCaseTest.java` (test int→long cascade + LoyaltyAccrualEntity.builder().points(100L))
- `services/order/src/test/java/vn/vnpt/order/application/saga/PaymentCapturedOrderAdvancerTest.java` (.thenReturn(0L))
- `services/order/src/test/java/vn/vnpt/order/application/web/OrderControllerAccrueLoyaltyTest.java` (.thenReturn(115L))

## ponytail notes

- `AppendOrderTransitionCommand.pointsApplied` is plumbed but unused by the append-only log — a future `RedeemLoyaltyPointsUseCase` will read it and decrement `LoyaltyAccountEntity.points`. Marked nullable so existing callers (PaymentCapturedOrderAdvancer, PaymentRefundedOrderAdvancer) don't need to update.
- `Pricebook.load()` is `public` rather than package-private because the controller's web test (different package) needs to call it during `setUp()`. The alternative — a `@Bean` factory in `PricingApplication` — was heavier for one test seam.