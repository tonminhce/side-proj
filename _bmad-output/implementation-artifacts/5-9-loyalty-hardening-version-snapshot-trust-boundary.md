---
baseline_commit: 69383b4
---

# Story 5.9 (follow-up): Loyalty hardening — @Version optimistic-lock + snapshot trust boundary

Status: review

Closes Epic 5 carry-forward HIGH item "Loyalty race: missing @Version on LoyaltyAccountEntity + accrual endpoint trusts caller" from `_bmad-output/backlog/deferred-issues.md`.

## Story

As the loyalty accrual path,
I want concurrent accruals to be safe (no double-spend via read-modify-write race),
And I want the accrue-loyalty endpoint to read totalCents from the immutable price snapshot rather than the request body.

## Acceptance Criteria

1. **`@Version` on LoyaltyAccountEntity** — Hibernate manages the version column. V006 migration adds `version BIGINT NOT NULL DEFAULT 0`. Concurrent updates surface as `ObjectOptimisticLockingFailureException`. 1 test on the entity (implicit via entity compile + repository).
2. **Optimistic-lock retry in `AccrueLoyaltyPointsUseCase.execute`** — single retry on `ObjectOptimisticLockingFailureException`. Accrual UNIQUE(order_uuid) is the ultimate idempotency guarantee (a 2nd racing writer falls into the fast-path idempotency check on next read). 2 new tests: `accrue_retriesOnceOnOptimisticLockFailure`, `accrue_surfacesLockFailureAfterSingleRetry`.
3. **`POST /api/orders/{orderUuid}/accrue-loyalty` reads totalCents from snapshot** — request body no longer carries `totalCents`. Endpoint looks up `OrderPriceSnapshotRepository.findById(orderUuid)`; unknown orders → 404 via `OrderEditExceptionHandler.handleUnknownOrder(IllegalArgumentException)`. The use case signature `(long orderUuid, long customerId, long totalCents)` is unchanged — `PaymentCapturedOrderAdvancer` saga caller (line 107) is unaffected. 3 new tests in `OrderControllerAccrueLoyaltyTest`: snapshot-sourced happy path, unknown-order 404, missing-customerId 400.
4. **All 66 order tests green** (was 61, +5).
5. **saga path unbroken** — `PaymentCapturedOrderAdvancer` test still passes (`PaymentCapturedOrderAdvancerTest` 6/6 green, includes the `customerId=0L, totalCents=snapshot` placeholder path; actual `customerId` resolution is the snapshot.user_id story's job).

## Out of scope (deferred, still open)

- `OrderPriceSnapshot.user_id` column — saga placeholder `customerId=0L` stays until this lands.
- `LoyaltyAccrualEntity.points` INT → BIGINT migration (~21M cap, LOW severity).
- `pointsApplied` field on `AppendOrderTransitionCommand` (redemption flow).
- Removing the `customerId` query param on the accrue endpoint (depends on `OrderPriceSnapshot.user_id`).

## Files changed

**New:**
- `services/order/src/main/resources/db/migration/order/V006__loyalty_account_version.sql`
- `services/order/src/test/java/vn/vnpt/order/application/web/OrderControllerAccrueLoyaltyTest.java`

**Modified:**
- `services/order/src/main/java/vn/vnpt/order/infrastructure/entity/LoyaltyAccountEntity.java` (+`@Version` field)
- `services/order/src/main/java/vn/vnpt/order/application/usecase/AccrueLoyaltyPointsUseCase.java` (inline retry-on-OL)
- `services/order/src/main/java/vn/vnpt/order/application/web/OrderController.java` (controller now sources totalCents from snapshot; constructor takes `OrderPriceSnapshotRepository`)
- `services/order/src/main/java/vn/vnpt/order/application/web/OrderEditExceptionHandler.java` (+`IllegalArgumentException → 404` handler)
- `services/order/src/test/java/vn/vnpt/order/application/usecase/AccrueLoyaltyPointsUseCaseTest.java` (+2 retry tests)

## ponytail notes

- Inline single-retry in the use case, not a Spring Retry annotation — `ponytail: add @Retryable when contention metrics justify it.`
- `IllegalArgumentException → 404` handler is broad; it catches IllegalArgumentException thrown by any controller in the order module. Acceptable for now because the only IAE source in order is "missing snapshot" — narrow it to a dedicated `OrderSnapshotMissingException` if a second IAE source appears.