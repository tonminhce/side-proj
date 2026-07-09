---
baseline_commit: 2736420
---

# Story 5.10 (follow-up): snapshot.user_id — drop customerId=0L placeholder, loyalty saga resolves real customerId

Status: review

Closes Epic 5 carry-forward HIGH item "OrderPriceSnapshot has no userId column" from `_bmad-output/backlog/deferred-issues.md`.

## Story

As the saga path,
I want OrderPriceSnapshot to capture the authenticated userId at order placement,
So that the loyalty accrual on PAID resolves customerId from the snapshot instead of the v1 placeholder customerId=0L.

## Acceptance Criteria

1. **V007 migration** — `order_price_snapshot` gets `user_id BIGINT NULL` + `idx_order_price_snapshot_user_id`. Nullable because legacy snapshots have no userId; saga skips accrual on those.
2. **`OrderPriceSnapshot.userId` field** — nullable Long, plumbed through builder. Existing snapshots constructed without userId still serialize correctly.
3. **`PaymentCapturedOrderAdvancer` resolves customerId from snapshot.userId** — when non-null, calls `accrueLoyaltyPointsUseCase.execute(orderUuid, userId, totalCents)` instead of `(orderUuid, 0L, totalCents)`. When null (legacy snapshot), logs a warning and skips accrual. PAID transition still appends in both paths.
4. **v1 key model** — snapshot.userId IS the customerId (services/auth's `users.customer_id` == userId in v1). Forward-compat note in code: replace with cross-service lookup if the key model diverges.
5. **2 new saga tests**: `onPaymentCaptured_resolvesCustomerIdFromSnapshotUserId`, `onPaymentCaptured_skipsAccrualWhenSnapshotHasNoUserId`. Existing 6 saga tests still green.
6. **All 68 order tests green** (was 66, +2).
7. **Saga path's loyalty accrual is no longer a no-op** when the genesis path supplies userId (smoke + checkout service caller sets `"userId": N` on the priceSnapshot JSON). Legacy dev snapshots still accrue via the existing `/api/orders/{orderUuid}/accrue-loyalty` endpoint with `customerId` query param (Move B).

## Out of scope (deferred, still open)

- Cross-service user→customer lookup (only needed if auth/customer key model diverges from v1 equality).
- `LoyaltyAccrualEntity.points` INT → BIGINT migration (~21M cap, LOW).
- `pointsApplied` field on `AppendOrderTransitionCommand` (redemption flow, MEDIUM).
- Updating checkout service to set `userId` on the priceSnapshot JSON (out-of-order scope; the field accepts it; caller change is a 1-line update when checkout lands).

## Files changed

**New:**
- `services/order/src/main/resources/db/migration/order/V007__order_price_snapshot_user_id.sql`

**Modified:**
- `services/order/src/main/java/vn/vnpt/order/infrastructure/entity/OrderPriceSnapshot.java` (+`userId` field)
- `services/order/src/main/java/vn/vnpt/order/application/saga/PaymentCapturedOrderAdvancer.java` (saga resolves customerId from snapshot.userId)
- `services/order/src/test/java/vn/vnpt/order/application/saga/PaymentCapturedOrderAdvancerTest.java` (+2 tests)

## Cleanup done this cycle

- Removed stale `target/test-classes/vn/vnpt/order/OrderPortContractTest.class` (source was deleted in Epic 5 closeout, stale .class survived and caused 2 ArchUnit failures despite source absence). No source change — `mvn clean` would also fix this for future runs.

## ponytail notes

- `userId` field on the entity is **nullable** (not `Long` boxed but kept as primitive risk) — legacy snapshots pre-V007 have no userId; the saga's null-check + warn log is the forward-compat seam.
- Forward-compat: when auth/customer key model diverges, replace `customerId = userId` with a `UserCustomerLookup` port. The `// ponytail: cross-service user→customer lookup when key model diverges` comment in the saga marks the seam.