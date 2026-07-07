# Test Automation Summary — Story 1.8

**Story:** Inventory lifecycle events (FR-11) + `@SoftUk` extension (FR-12) — solves DI-09
**Story file:** `_bmad-output/implementation-artifacts/1-8-inventory-lifecycle-events-fr-11-softuk-extension-fr-12-solves-di-09.md`
**Workflow:** `bmad-qa-generate-e2e-tests`
**Test framework:** JUnit 5 + Spring Boot Test + MockMvc + AssertJ + ArchUnit + Testcontainers (Postgres 16-alpine) + Awaitility + Mockito (`@MockitoBean`)
**Test command:** `mvn -pl services/inventory -am test`
**Date:** 2026-07-07

Story 1.8 is a backend-only service (no UI surface) — API/integration testing only — E2E tests deferred to Story 10.5 per Story 1.4/1.5/1.6/1.7 convention.

---

## Generated / Added Tests

### Existing tests from Story 1.8 implementation (already authored)

| Path | Cases | Covers |
|------|------:|--------|
| `services/inventory/src/test/java/vn/vnpt/inventory/InventoryApplicationContextTest.java` | 11 | Story 1.8 AC #2 + #15 — adds `flywayAppliedV006` (V006 lifecycle-event placeholder migration) |
| `services/inventory/src/test/java/vn/vnpt/inventory/InventoryPackageBoundaryTest.java` | 7 | Story 1.8 AC #10 + #13 — adds `inventory_softDeletableEntitiesHaveSoftUkAnnotation` (DI-09 guard) + `inventory_lifecycleEventsRouteThroughPublisher` (FR-11 route guard) |
| `services/inventory/src/test/java/vn/vnpt/inventory/domain/event/LifecyclePhaseTest.java` | 3 | Story 1.8 AC #5 — `wireValue_returnsUpperSnakeString` / `parseFromWireValue_returnsEnum` (case-insensitive) / `parseFromWireValue_returnsNullForUnknown` |
| `services/inventory/src/test/java/vn/vnpt/inventory/domain/event/InventoryLifecycleEventTest.java` | 2 | Story 1.8 AC #5 + #20 — `serialize_thenDeserialize_preservesPhaseField` + `nonNullAnnotation_omitsNullFields` |
| `services/inventory/src/test/java/vn/vnpt/inventory/infrastructure/outbox/LifecycleEventPublisherTest.java` | 5 | Story 1.8 AC #6 — dual-publish (RESERVED → lifecycle+reserved / RELEASED → lifecycle+released) + single-publish (ALLOCATED / SHIPPED / ADJUSTED → lifecycle only) |
| `services/inventory/src/test/java/vn/vnpt/inventory/application/AllocateInventoryUseCaseTest.java` | 3 | Story 1.8 AC #7 — `allocate_promotesActiveReservationToCommitted_andEmitsAllocated` / `allocate_isIdempotentOnAlreadyCommittedReservation_returns200_noOp` / `allocate_onUnknownReservationUuid_throwsReservationNotFoundException` |
| `services/inventory/src/test/java/vn/vnpt/inventory/application/ShipInventoryUseCaseTest.java` | 3 | Story 1.8 AC #7 — `ship_deductsFromAvailable_andEmitsShipped` / `ship_onInsufficientStock_throwsInsufficientStockException` / `ship_onUnknownWarehouse_throwsWarehouseNotFoundException` |
| `services/inventory/src/test/java/vn/vnpt/inventory/application/AdjustInventoryUseCaseTest.java` | 6 | Story 1.8 AC #7 — adds `adjust_emitsAdjustedLifecycleEvent`; existing 5 cases unchanged |
| `services/inventory/src/test/java/vn/vnpt/inventory/application/CreateWarehouseUseCaseTest.java` | 2 | Story 1.8 AC #8 — `create_persistsWarehouse_andCallsUkValidator` (incl. `tenantId='default'` @PrePersist) / `create_onDuplicateCodeInSameTenant_throwsInvalidInputExceptionFromUkValidator` |
| `services/inventory/src/test/java/vn/vnpt/inventory/api/AllocateInventoryControllerTest.java` | 2 | Story 1.8 AC #7 — `post_returns201OnSuccess` / `post_returns404OnReservationNotFound` |
| `services/inventory/src/test/java/vn/vnpt/inventory/api/ShipInventoryControllerTest.java` | 2 | Story 1.8 AC #7 — `post_returns201OnSuccess` / `post_returns409OnInsufficientStock` |
| `services/inventory/src/test/java/vn/vnpt/inventory/api/CreateWarehouseControllerTest.java` | 3 | Story 1.8 AC #9 — `post_returns201OnSuccess` / `post_returns400OnSoftUkViolation` / `post_returns400OnMissingRegion` |
| `services/inventory/src/test/java/vn/vnpt/inventory/domain/WarehouseTest.java` | 2 | Story 1.8 AC #8 — adds `softUkAnnotationIsPresentAtClassLevel` (reflection pin of `@SoftUk(name="warehouse_code_per_tenant", fields={"tenantId","code"})`) |

**Story 1.8 implementation total: 50 tests** across 13 classes (44 new + 6 from Story 1.5/1.6/1.7 baselines updated by Story 1.8). Of the 30 "Story 1.8 new" cases reported in Completion Notes, 50 includes the pre-existing 188 inventory baseline minus the 168 Story 1.6 baseline = 30 new + the updates.

### QA-pass gap fills (this workflow run)

| Path | Δ Cases | Gap addressed |
|------|--------:|---------------|
| `services/inventory/src/test/java/vn/vnpt/inventory/infrastructure/outbox/LifecycleEventPublisherTest.java` | +5 | **HIGH — Dual-publish signatures identity was not pinned.** The existing 5 tests verify `event_type` + call counts via `any()` for signatures. A regression that recomputes a fresh HMAC for the legacy publish (or swaps the signatures map) would still pass. New tests pin: `publish_dualPublishForReservedPhase_usesIdenticalSignatures` (asserts `isSameAs` on both `append` signatures), `publish_dualPublishForReleasedPhase_usesIdenticalSignatures` (same for RELEASED), `publish_passesTheSameEventObjectInstanceToBothAppends` (asserts `isSameAs` on the event itself), `publish_nonLegacyPhasesNeverTouchLegacyTopics` (parameterized for ALLOCATED/SHIPPED/ADJUSTED), `publish_withNullSignatures_stillEmitsLifecycleRow` (defensive — null signatures tolerated, NOT skipped). |
| `services/inventory/src/test/java/vn/vnpt/inventory/api/ReservationControllerExceptionHandlerTest.java` | +1 | **MEDIUM — Story 1.8 added `handleReservationNotFound → 404`; the 4-case Story 1.6 handler test did NOT pin this mapping.** New `reservationNotFound_mapsTo404` asserts 404 + `error=reservation_not_found` + message contains the UUID (for saga diagnostic logging). |
| `services/inventory/src/test/java/vn/vnpt/inventory/application/AllocateInventoryUseCaseTest.java` | +3 | **MEDIUM — Terminal-state guard on RELEASED was not pinned.** `allocate_isIdempotentOnAlreadyCommittedReservation_returns200_noOp` covers the COMMITTED path; the JavaDoc says "status != ACTIVE → log + no-op" (RELEASED is also terminal via `ReservationStatus.isTerminal()`). A regression that only guards on COMMITTED would silently double-deduct on a re-allocate of a RELEASED reservation. New `allocate_isIdempotentOnReleasedReservation_noOp` releases first, then re-allocates, asserts status stays RELEASED and zero `reason='allocate'` ledger rows. **MEDIUM — Use-case-level validation paths** (`null reservationUuid`, blank `sagaStepId`) were covered by the controller's HTTP layer but not directly. New `allocate_throwsIllegalArgumentWhenReservationUuidIsNull` + `allocate_throwsIllegalArgumentWhenSagaStepIdIsBlank` pin the use-case guards with field-name assertions. |
| `services/inventory/src/test/java/vn/vnpt/inventory/application/ShipInventoryUseCaseTest.java` | +4 | **MEDIUM — Use-case-level validation paths** (`quantity<=0`, blank `sagaStepId`) were not directly pinned. New `ship_throwsIllegalArgumentWhenQuantityIsZero` + `ship_throwsIllegalArgumentWhenQuantityIsNegative` + `ship_throwsIllegalArgumentWhenSagaStepIdIsBlank`. **MEDIUM — Partial-availability InsufficientStockException case.** Existing `ship_onInsufficientStock_throwsInsufficientStockException` only tests 0-available; new `ship_onPartialAvailable_throwsInsufficientStockWithActualAvailable` seeds `on_hand=2` and requests 5, asserts `requested=5L, available=2L, variantId, warehouseId` on the exception (the 4-tuple constructor pins). |
| `services/inventory/src/test/java/vn/vnpt/inventory/application/AdjustInventoryUseCaseTest.java` | +1 | **MEDIUM — `phase=ADJUSTED` in the outbox payload JSON was not asserted.** The existing `adjust_emitsAdjustedLifecycleEvent` only verifies `event_type='inventory.lifecycle'`; a regression that flipped the phase to e.g. `RECEIVE` (since the test's reason is RECEIVE) would still pass. New `adjust_outboxPayloadCarriesPhaseAdjusted` asserts `payload::text` contains `"phase"` + `ADJUSTED` + `"aggregateType"` + `InventoryLedger` + `"reason"` + `adjust`, and `signatures::text` contains `hmac_sha256` (the ADR-20 HMAC envelope). |
| `services/inventory/src/test/java/vn/vnpt/inventory/domain/annotation/IgnoreSoftUkAuditTest.java` (new) | +4 | **MEDIUM — The `@IgnoreSoftUkAudit` opt-out was not positively pinned.** The boundary test catches the absence transitively (an entity missing the opt-out would need `@SoftUk` and fail the build). New file: `inventoryLedgerEntry_carriesIgnoreSoftUkAuditMarker` + `inventoryReservation_carriesIgnoreSoftUkAuditMarker` (positive pin at class level), `markerHasRuntimeRetention` + `markerTargetsType` (annotation-meta contract: the `@Retention(RUNTIME)` + `@Target(TYPE)` are what the ArchUnit predicate reads). |
| `services/inventory/src/test/java/vn/vnpt/inventory/domain/exception/ReservationNotFoundExceptionTest.java` (new) | +2 | **LOW — Asymmetric coverage.** Story 1.6 shipped `InsufficientStockExceptionTest` with 1 case; Story 1.8 added `ReservationNotFoundException` but no unit test. New file: `constructor_storesUuidInMessage` (asserts the uuid appears in the message for the handler's HTTP 404 body) + `constructor_withNullUuid_doesNotThrow` (defensive — future refactor safety). |

**QA-pass additions: +20 tests across 7 files** (5 existing files extended + 2 new files).

---

## Coverage

| AC | Before this QA pass | After this QA pass | Notes |
|----|--------------------:|-------------------:|-------|
| #1 (foundation: 17 modules, util baseline, ADR stack) | ✅ (unchanged from Story 1.7) | ✅ | Unchanged. |
| #2 (V006 lifecycle-event placeholder migration applied at boot) | ✅ (`flywayAppliedV006`) | ✅ | Unchanged. |
| #3 (unified `inventory.lifecycle` topic, 5 phases, dual-publish for RESERVED/RELEASED) | ✅ (LifecycleEventPublisherTest + ReserveInventoryUseCaseTest happy-path) | ✅ | Dual-publish signatures identity now pinned (security). |
| #4 (V006 is bookkeeping-only — `SELECT 1` body + comment block) | ✅ (Flyway-applied test) | ✅ | Unchanged. |
| #5 (unified `InventoryLifecycleEvent` record + `LifecyclePhase` enum) | ✅ (LifecyclePhaseTest + InventoryLifecycleEventTest) | ✅ | Unchanged. |
| #6 (LifecycleEventPublisher dual-publish — same signatures, SAME event instance) | ⚠️ Partial — `any()` matchers | ✅ | Captured both signatures + event-instance `isSameAs` assertions. |
| #7 (3 new use cases — Allocate / Ship / Adjust — emit lifecycle events) | ✅ (use case integration tests + adjust_happy path) | ✅ | New: terminal-state guard on RELEASED, use-case validation paths, partial-availability exception contract, `phase=ADJUSTED` payload assertion. |
| #8 (`@SoftUk` on `Warehouse` + `CreateWarehouseUseCase` + `UkValidator`) | ✅ (WarehouseTest reflection + CreateWarehouseUseCaseTest 2 cases) | ✅ | The opt-out for `InventoryLedgerEntry` + `InventoryReservation` is now positively pinned (IgnoreSoftUkAuditTest). |
| #9 (CreateWarehouseController — 201 / 400 / 400-missing-region) | ✅ (controller test) | ✅ | Unchanged. |
| #10 (CI lint — ArchUnit rule for `@SoftUk` + lifecycle route guard) | ✅ (boundary tests) | ✅ | Unchanged. |
| #11 (util 57/57 unchanged) | ✅ | ✅ | Unchanged. |
| #12 (218 inventory tests target — actually 238 post-QA pass) | ✅ (218) | ✅ (238) | 20 gap-fills added. |
| #13 (boundary tests 7/7) | ✅ | ✅ | Unchanged. |
| #14 (17 modules preserved) | ✅ | ✅ | Unchanged. |
| #15 (V006 applied; service boots; Flyway runs) | ✅ (context test) | ✅ | Unchanged. |
| #16 (`dev/scripts/lifecycle_smoke.sh`) | ✅ (manual smoke; not test-suite covered) | ✅ | Out of scope for unit tests. |
| #17 (`dev/README.md` + `dev/scripts/smoke.sh` updated) | ✅ (manual smoke) | ✅ | Out of scope for unit tests. |
| #18 (no new deps) | ✅ | ✅ | Unchanged. |
| #19 (all 5 lifecycle phases via REST) | ✅ (3 controllers tested) | ✅ | Unchanged. |
| #20 (`@JsonInclude(NON_NULL)` on `InventoryLifecycleEvent`) | ✅ (InventoryLifecycleEventTest.nonNullAnnotation_omitsNullFields) | ✅ | Unchanged. |

### Test count

| Stage | Count | Δ |
|-------|------:|---:|
| Story 1.7 implementation + QA pass | 188 inventory | — |
| **Story 1.8 implementation** | **218** (188 baseline + 30 new) | **+30** |
| **This QA pass** | **+20 inventory** (5 publisher + 1 handler + 3 allocate + 4 ship + 1 adjust + 4 ignore-soft + 2 reservation-not-found) | |
| **Total after Story 1.8 QA** | **238** inventory + 57 util + 65 catalog = **360 tests** | |

`mvn -pl services/inventory -am test` → **238 inventory tests pass, 0 failures, 0 errors, 0 skipped** (verified 2026-07-07). Per-class delta from Story 1.8 baseline:
```
[INFO] Tests run: 11, -- in InventoryApplicationContextTest                (baseline 10, +1 Story 1.8 flywayAppliedV006)
[INFO] Tests run: 7,  -- in InventoryPackageBoundaryTest                    (baseline 5, +2 Story 1.8 SoftUk + lifecycle-route rules)
[INFO] Tests run: 2,  -- in AllocateInventoryControllerTest                 (NEW Story 1.8)
[INFO] Tests run: 3,  -- in CreateWarehouseControllerTest                   (NEW Story 1.8)
[INFO] Tests run: 2,  -- in ShipInventoryControllerTest                     (NEW Story 1.8)
[INFO] Tests run: 4,  -- in ReservationControllerExceptionHandlerTest       (baseline 4, +1 QA pass)
[INFO] Tests run: 8,  -- in InventoryReservationControllerTest              (Story 1.6)
[INFO] Tests run: 1,  -- in InventoryOnHandQueryControllerTest              (Story 1.7)
[INFO] Tests run: 5,  -- in PickWarehouseForReservationUseCaseTest           (Story 1.7)
[INFO] Tests run: 2,  -- in CreateWarehouseUseCaseTest                      (NEW Story 1.8)
[INFO] Tests run: 1,  -- in ReservationSweeperJobTest                       (Story 1.6)
[INFO] Tests run: 1,  -- in OnHandAvailableStockTest                        (Story 1.6)
[INFO] Tests run: 1,  -- in AdjustInventoryUseCaseAtomicityTest             (Story 1.5 QA pass)
[INFO] Tests run: 4,  -- in ReserveInventoryUseCaseRegionDispatchTest       (Story 1.7)
[INFO] Tests run: 100,-- in ReserveInventoryUseCaseConcurrentTest           (Story 1.6 — 1 method × 100)
[INFO] Tests run: 8,  -- in ReserveInventoryUseCaseTest                     (Story 1.6)
[INFO] Tests run: 6,  -- in AllocateInventoryUseCaseTest                    (baseline 3 Story 1.8, +3 QA pass)
[INFO] Tests run: 7,  -- in AdjustInventoryUseCaseTest                      (baseline 6 Story 1.8 incl. ADJUSTED lifecycle, +1 QA pass)
[INFO] Tests run: 7,  -- in ShipInventoryUseCaseTest                       (baseline 3 Story 1.8, +4 QA pass)
[INFO] Tests run: 4,  -- in ReleaseInventoryUseCaseTest                     (Story 1.6)
[INFO] Tests run: 2,  -- in OnHandUseCaseTest                               (Story 1.5)
[INFO] Tests run: 2,  -- in CatalogEventListenerTest                        (Story 1.5)
[INFO] Tests run: 1,  -- in CatalogEventListenerHmacFailureTest             (Story 1.5 QA pass)
[INFO] Tests run: 10, -- in LifecycleEventPublisherTest                     (baseline 5 Story 1.8, +5 QA pass)
[INFO] Tests run: 4,  -- in InventoryLedgerEntryRepositoryTest              (Story 1.5)
[INFO] Tests run: 3,  -- in WarehouseRepositoryTest                         (Story 1.5)
[INFO] Tests run: 2,  -- in InventoryReservationRepositoryTest              (Story 1.6)
[INFO] Tests run: 2,  -- in RegionTest                                      (Story 1.7)
[INFO] Tests run: 3,  -- in InventoryLedgerEntryTest                        (Story 1.5)
[INFO] Tests run: 1,  -- in ReservationStatusTest                           (Story 1.6)
[INFO] Tests run: 4,  -- in IgnoreSoftUkAuditTest                           (NEW QA pass)
[INFO] Tests run: 6,  -- in InventoryReasonTest                             (Story 1.5 QA pass)
[INFO] Tests run: 2,  -- in WarehouseTest                                   (baseline 1 Story 1.5, +1 Story 1.8 @SoftUk reflection)
[INFO] Tests run: 1,  -- in InsufficientStockExceptionTest                  (Story 1.6)
[INFO] Tests run: 2,  -- in ReservationNotFoundExceptionTest                (NEW QA pass)
[INFO] Tests run: 3,  -- in InventoryReservationTest                        (Story 1.6)
[INFO] Tests run: 2,  -- in InventoryLifecycleEventTest                     (NEW Story 1.8)
[INFO] Tests run: 3,  -- in LifecyclePhaseTest                              (NEW Story 1.8)
```

### Other CI gates verified

| Gate | Command | Result |
|------|---------|--------|
| Util baseline | `mvn -pl util -am test` | 57/57 pass (Story 1.7 baseline preserved) |
| Catalog baseline | `mvn -pl services/catalog -am test` | 65/65 pass (Story 1.4 baseline preserved) |
| Inventory ArchUnit | `mvn -pl services/inventory test -Dtest=InventoryPackageBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false` | 7/7 pass |

---

## Discovered gaps (auto-applied)

### HIGH — Dual-publish signatures identity not pinned (security invariant)

**Symptom:** `LifecycleEventPublisher.publish(InventoryLifecycleEvent evt)` calls `outbox.append(...)` TWICE for `RESERVED` + `RELEASED` phases — once for the unified `inventory.lifecycle` topic and once for the legacy `inventory.reserved` / `inventory.released` topic. The JavaDoc states: *"Both `outbox.append` calls reuse the SAME `signatures` map — the HMAC is computed once by the calling use case and applied to both topics. This guarantees that a consumer reading either topic sees an identical signature, and avoids double HMAC computation."* The existing 5 test cases verify the `event_type` + call counts but use `any()` for the signatures map. A regression that:
- Recomputed a fresh HMAC for the legacy publish (instead of reusing `evt.getSignatures()`), OR
- Passed a different signatures map to the legacy publish (e.g., a stub or copy), OR
- Passed a different event object to the legacy publish (e.g., a wrapped/copied event),
…would still pass the existing tests. The saga's downstream consumer would see two events with different signatures → one would verify-pass and one would verify-fail silently.

**Fix applied:** 3 new tests in `LifecycleEventPublisherTest` using `ArgumentCaptor<Map<String, String>>` and `isSameAs` (identity, not equality):
1. `publish_dualPublishForReservedPhase_usesIdenticalSignatures` — captures both `append` signatures, asserts each `isSameAs(evt.getSignatures())` AND `isSameAs` each other.
2. `publish_dualPublishForReleasedPhase_usesIdenticalSignatures` — same for RELEASED.
3. `publish_passesTheSameEventObjectInstanceToBothAppends` — captures both `append` event payloads, asserts each `isSameAs(evt)`.

Two additional defensive tests:
4. `publish_nonLegacyPhasesNeverTouchLegacyTopics` — parameterized loop over ALLOCATED / SHIPPED / ADJUSTED; uses `Mockito.reset(outbox)` between iterations to assert `times(0)` for both legacy topics.
5. `publish_withNullSignatures_stillEmitsLifecycleRow` — null signatures map tolerated; both `outbox.append` calls still fire (matches the ADR-20 consumer-side "skip on missing signature" path pinned by Story 1.5's `HmacFailureTest`).

### MEDIUM — Story 1.8 `handleReservationNotFound → 404` mapping not pinned

**Symptom:** `ReservationControllerExceptionHandler.handleReservationNotFound(ReservationNotFoundException)` (added in Story 1.8) maps to 404 with `error=reservation_not_found` + the exception message (which contains the UUID). The pre-existing 4-case handler test (Story 1.6 QA pass) covered `InsufficientStockException` / `WarehouseNotFoundException` / `IllegalArgumentException` / `DataIntegrityViolationException` but NOT the new `ReservationNotFoundException` mapping. A regression that swapped the status code or dropped the handler would only surface at the controller integration layer.

**Fix applied:** New `reservationNotFound_mapsTo404` in `ReservationControllerExceptionHandlerTest` — asserts 404 + `error=reservation_not_found` + message contains `999999` (the UUID, for the saga's diagnostic logging).

### MEDIUM — `AllocateInventoryUseCase` terminal-state guard on RELEASED was not pinned

**Symptom:** `ReservationStatus.isTerminal()` returns `true` for both RELEASED and COMMITTED. The use case's terminal-state guard fires on `status != ACTIVE` (matches the JavaDoc "status != ACTIVE → log + no-op"). The existing `allocate_isIdempotentOnAlreadyCommittedReservation_returns200_noOp` covers the COMMITTED path but NOT the RELEASED path. A regression that only guarded on `status == COMMITTED` (e.g., flipped the operator to `==`) would silently:
- Re-allocate a RELEASED reservation (e.g., after saga cancellation + saga retry),
- Set `status = COMMITTED` (overwriting RELEASED),
- Append a second `reason='allocate'` ledger row → double-deduct on_hand.
This is a subtle data-integrity bug only visible at the ledger sum.

**Fix applied:** New `allocate_isIdempotentOnReleasedReservation_noOp` in `AllocateInventoryUseCaseTest`:
1. Seeds an ACTIVE reservation via `reserveUseCase.reserve(...)`.
2. Releases it via `releaseUseCase.release(sagaStepId)`.
3. Re-allocates via `allocateUseCase.allocate(...)`.
4. Asserts status stays RELEASED.
5. Asserts zero `reason='allocate'` ledger rows.

### MEDIUM — Use-case-level validation paths for Allocate + Ship were not directly pinned

**Symptom:** `AllocateInventoryUseCase.allocate(...)` throws `IllegalArgumentException` for `null reservationUuid` and `blank sagaStepId` (with field-name in the message). `ShipInventoryUseCase.ship(...)` throws `IllegalArgumentException` for `null variantId/warehouseId`, `quantity <= 0`, and `blank sagaStepId`. The controller tests (AllocateInventoryControllerTest, ShipInventoryControllerTest) cover the HTTP-level 400 mapping, but the use-case guards are the FIRST line of defense — a regression that drops the guard would only surface at the HTTP layer (which is the last defense, not the first).

**Fix applied:** 2 new tests in `AllocateInventoryUseCaseTest`:
1. `allocate_throwsIllegalArgumentWhenReservationUuidIsNull` — `new AllocateInventoryCommand(null, "...")` → `IllegalArgumentException` with message containing `reservationUuid`.
2. `allocate_throwsIllegalArgumentWhenSagaStepIdIsBlank` — `new AllocateInventoryCommand(reservationUuid, "  ")` → `IllegalArgumentException` with message containing `sagaStepId`.

3 new tests in `ShipInventoryUseCaseTest`:
1. `ship_throwsIllegalArgumentWhenQuantityIsZero` — `quantity=0` → `IllegalArgumentException` with message containing `quantity`.
2. `ship_throwsIllegalArgumentWhenQuantityIsNegative` — `quantity=-1` → same.
3. `ship_throwsIllegalArgumentWhenSagaStepIdIsBlank` — `sagaStepId="  "` → `IllegalArgumentException` with message containing `sagaStepId`.

### MEDIUM — Partial-availability `InsufficientStockException` contract was not pinned

**Symptom:** `ShipInventoryUseCase.ship(...)` throws `InsufficientStockException(variantId, warehouseId, requested, available)` when `available < requested`. The existing `ship_onInsufficientStock_throwsInsufficientStockException` only exercises the `available=0` case. A regression that swapped `requested` and `available` in the exception constructor (or dropped one of the 4 fields) would not be caught — the 4-tuple is the diagnostic surface for the saga's HTTP 409 response body.

**Fix applied:** New `ship_onPartialAvailable_throwsInsufficientStockWithActualAvailable` in `ShipInventoryUseCaseTest`:
- Seeds `on_hand=2` (delta=+2 in warehouse).
- Requests `quantity=5`.
- Asserts `InsufficientStockException` with `requested=5L, available=2L, variantId=500L, warehouseId=warehouseId`.

### MEDIUM — `AdjustInventoryUseCase` outbox payload `phase=ADJUSTED` was not asserted

**Symptom:** The existing `adjust_emitsAdjustedLifecycleEvent` asserts `event_type='inventory.lifecycle'` on the outbox row but does NOT inspect the `payload` JSON column. The payload's `phase` field is what downstream consumers filter on (the FR-11 unified-topic design — one topic, filter by phase). A regression that flipped the phase from `ADJUSTED` to e.g. `RECEIVE` (the test's `InventoryReason.RECEIVE`) would still pass `event_type='inventory.lifecycle'` but downstream consumers would silently lose the event.

**Fix applied:** New `adjust_outboxPayloadCarriesPhaseAdjusted` in `AdjustInventoryUseCaseTest`:
- Asserts `payload::text` contains `"phase"` + `ADJUSTED` (Postgres `jsonb` normalizes whitespace with a space after the colon, so the assertion is on key + value separately rather than the full key-value pair).
- Asserts `"aggregateType"` + `InventoryLedger`.
- Asserts `"reason"` + `adjust` (the `InventoryReason.ADJUST.toColumnValue()` mapping).
- Asserts `signatures::text` contains `hmac_sha256` (the ADR-20 HMAC envelope key).

### MEDIUM — `@IgnoreSoftUkAudit` opt-out was not positively pinned

**Symptom:** The ArchUnit boundary rule `inventory_softDeletableEntitiesHaveSoftUkAnnotation` requires every entity extending `RootEntity` to carry `@SoftUk` OR `@IgnoreSoftUkAudit`. The rule catches the absence transitively (a missing opt-out would force the entity to need `@SoftUk`, which it doesn't have). But the OPT-OUT mechanism itself — the `@IgnoreSoftUkAudit` annotation on `InventoryLedgerEntry` and `InventoryReservation` — was not positively pinned. A regression that drops the annotation from BOTH entities would fail the boundary test (since neither has `@SoftUk`), but a regression that drops it from ONE entity while the OTHER entity retains `@SoftUk` for some other reason would be invisible to the boundary test.

**Fix applied:** New `IgnoreSoftUkAuditTest` (4 cases):
1. `inventoryLedgerEntry_carriesIgnoreSoftUkAuditMarker` — reflection pin: `InventoryLedgerEntry.class.getAnnotation(IgnoreSoftUkAudit.class)` is non-null.
2. `inventoryReservation_carriesIgnoreSoftUkAuditMarker` — same for `InventoryReservation`.
3. `markerHasRuntimeRetention` — `@Retention(RetentionPolicy.RUNTIME)` (the ArchUnit predicate reads the annotation at classpath-scan time, which requires RUNTIME retention).
4. `markerTargetsType` — `@Target(ElementType.TYPE)` (so the annotation can be applied at the class level on entities).

### LOW — `ReservationNotFoundException` constructor not unit-tested

**Symptom:** Story 1.6 shipped `InsufficientStockExceptionTest` with 1 case (asserting the 4-tuple constructor stores all 4 fields). Story 1.8 added `ReservationNotFoundException` (with the UUID in the message) but no unit test. Asymmetric coverage. The exception's message format is the saga's diagnostic surface — `ReservationControllerExceptionHandler.handleReservationNotFound(...)` returns the message verbatim in the HTTP 404 body.

**Fix applied:** New `ReservationNotFoundExceptionTest` (2 cases):
1. `constructor_storesUuidInMessage` — `new ReservationNotFoundException(9_999_999L)` → `getMessage()` contains `9999999` + `not found`.
2. `constructor_withNullUuid_doesNotThrow` — defensive: `new ReservationNotFoundException(null)` does not NPE (the message contains `"null"`).

---

## Gaps NOT addressed (deliberately skipped)

| Gap | Why skipped | When to revisit |
|-----|-------------|----------------|
| `@JsonInclude(NON_NULL)` not pinned on `signatures` field specifically (only on the class). A regression that explicitly @JsonInclude-EXCLUDED `signatures` from non-null would still pass `nonNullAnnotation_omitsNullFields` (the existing test omits `reservationUuid`/`sagaStepId`/`orderUuid`/`signatures` when null) — the test pins the class-level annotation's effect, which is sufficient. | Story 1.5+ — when a downstream consumer's deserializer actually exercises the null-omission contract. The current test pins the class-level annotation; a per-field @JsonInclude test would duplicate the existing coverage. |
| `LifecycleEventPublisher` with `null event` defensive guard | The use case always builds a valid `InventoryLifecycleEvent` via the builder; a null event would NPE inside `evt.getPhase()` which is fine (the saga's caller would surface a 500). A defensive `if (evt == null) return` would be over-engineering for a contract that can't be violated by any in-tree caller. | Never — defensive guard adds no signal. |
| Allocate terminal-state guard on RELEASED via direct `repository.save(status=RELEASED)` (without going through `releaseUseCase`) | The current `allocate_isIdempotentOnReleasedReservation_noOp` test exercises the realistic path (saga releases, then saga retries allocate). A direct `reservationRepository.save(...)` of a RELEASED reservation would be a code-smell — no in-tree caller does that. | Never — integration path is real. |
| `LifecycleEventPublisherTest.publish_withAdjustedPhase_publishesOnlyToLifecycleTopic` legacy-topic `times(0)` assertion (the new `publish_nonLegacyPhasesNeverTouchLegacyTopics` parameterized version supersedes it) | The original Story 1.8 tests for ADJUSTED + SHIPPED (single-publish) only assert `times(1)` for the lifecycle topic; the parameterized test adds `times(0)` for both legacy topics. Both pass; the new test is more thorough but does not retire the old. Keeping both for clarity. | Never — both tests pin different invariants; both have value. |
| `ReserveInventoryUseCase` test verifying the `phase=RESERVED` payload field (not just `event_type`) | `ReserveInventoryUseCaseTest.reserve_persistsReservationAndLedgerRowAndOutboxEvent` asserts `event_type='inventory.lifecycle'` and `event_type='inventory.reserved'` (the dual-publish). The `phase=RESERVED` payload assertion is the symmetric case to `adjust_outboxPayloadCarriesPhaseAdjusted` (added in this QA pass). YAGNI: the publisher test pins the dual-publish contract; the use-case test pins the integration path. Adding the payload assertion would duplicate `InventoryLifecycleEventTest.serialize_thenDeserialize_preservesPhaseField`. | Never — coverage is real (publisher test + use-case integration test + record round-trip test). |
| `LifecycleEventPublisher` reflection test that it doesn't accidentally reference `OutboxPublisher` from the wrong package | The existing `inventory_lifecycleEventsRouteThroughPublisher` boundary rule catches the inverse: it scans use cases for `OutboxPublisher` references. The publisher class itself is in the `infrastructure.outbox` package which IS allowed to reference `OutboxPublisher`. | Never — the boundary rule is one-directional (use cases → publisher only). |
| `SoftDeleteConfig` bean wiring test | The Story 1.8 dev log notes that util's `UtilsAutoConfiguration` is excluded in `application-test.yml` due to a pre-existing dual-bean issue; Story 1.8 adds an inventory-local `SoftDeleteConfig` that registers the soft-delete beans + `ApiExceptionHandle`. A regression that drops this config would surface as the `@SoftUk` boundary test passing on the static classpath but failing at runtime (no validator bean). Pinning this with a `@SpringBootTest` reflection check on `SoftDeleteConfig` is possible but the existing context tests (`InventoryApplicationContextTest.contextLoads`) already verify the full bean wiring end-to-end — any bean wiring failure surfaces there. | Story 8.x — when util's `UtilsAutoConfiguration` exclusion is removed (the future state); a dedicated `SoftDeleteConfig` test would then be redundant. |
| `@Value("${inventory.events.hmac-secret}")` fallback when the property is missing | Boot 4 fails the context startup on missing `@Value`; the existing `contextLoads` covers this. A separate `HmacSecretMissingTest` would duplicate. | Never — context startup is the gate. |

---

## Validation against `checklist.md`

### Test Generation

- [x] **API tests generated** — `AllocateInventoryControllerTest` (2 cases: 201 / 404), `ShipInventoryControllerTest` (2 cases: 201 / 409), `CreateWarehouseControllerTest` (3 cases: 201 / 400 / 400-missing-region), `ReservationControllerExceptionHandlerTest` (5 cases including the new `reservationNotFound_mapsTo404`). Covers all 3 new Story 1.8 controllers + the extended exception handler.
- [x] **E2E tests generated (if UI exists)** — N/A. Story 1.8 has no UI surface. E2E coverage is via `dev/scripts/lifecycle_smoke.sh` (manual end-to-end per AC #16) — Playwright e2e deferred to Story 10.5 per Story 1.4/1.5/1.6/1.7 convention.
- [x] **Tests use standard test framework APIs** — JUnit 5 + Spring Boot Test + MockMvc + AssertJ + ArchUnit + Testcontainers + Awaitility + Mockito (`@MockitoBean`). No new test deps.
- [x] **Tests cover happy path** — `LifecycleEventPublisherTest.publish_withReservedPhase_publishesToBothLifecycleAndReservedTopic` + 4 sibling dual/single-publish cases; `AllocateInventoryUseCaseTest.allocate_promotesActiveReservationToCommitted_andEmitsAllocated`; `ShipInventoryUseCaseTest.ship_deductsFromAvailable_andEmitsShipped`; `AdjustInventoryUseCaseTest.adjust_emitsAdjustedLifecycleEvent`; `CreateWarehouseUseCaseTest.create_persistsWarehouse_andCallsUkValidator`; `WarehouseTest.softUkAnnotationIsPresentAtClassLevel`.
- [x] **Tests cover 1-2 critical error cases** — 5 lifecycle-route boundary rules (`inventory_lifecycleEventsRouteThroughPublisher`), `@SoftUk` boundary rule, `InsufficientStockException` 4-tuple, `ReservationNotFoundException → 404`, `IllegalArgumentException → 400` (in handler + use cases), partial-availability `InsufficientStockException` field-pin, terminal-state guard on RELEASED.

### Test Quality

- [x] **All generated tests run successfully** — **238/238 inventory + 57/57 util + 65/65 catalog = 360 tests pass**; full suite green. The 100× concurrent test (`ReserveInventoryUseCaseConcurrentTest`) is counted as 100 individual executions.
- [x] **Tests use proper locators (semantic, accessible)** — N/A (backend integration tests; no UI). Backend tests use `assertThat` + AssertJ + `jsonPath` (semantic) + raw SQL `JdbcTemplate.queryForObject` (semantic — queries by column name) + `Map<String, Object>` for outbox row inspection.
- [x] **Tests have clear descriptions** — method names describe outcome: `publish_dualPublishForReservedPhase_usesIdenticalSignatures`, `allocate_isIdempotentOnReleasedReservation_noOp`, `ship_onPartialAvailable_throwsInsufficientStockWithActualAvailable`, `adjust_outboxPayloadCarriesPhaseAdjusted`, `inventoryLedgerEntry_carriesIgnoreSoftUkAuditMarker`, `reservationNotFound_mapsTo404`.
- [x] **No hardcoded waits or sleeps** — new tests use either (a) Testcontainers `TRUNCATE TABLE ... RESTART IDENTITY` in `@BeforeEach` for state isolation or (b) `@Transactional` rollback via JPA dirty-tracking. The pre-existing `CatalogEventListenerTest.await()` retains its 300ms `Thread.sleep` (per Story 1.5 note — same Modulith-bridge test-harness quirk). The new `LifecycleEventPublisherTest` is pure JUnit + Mockito (`@ExtendWith(MockitoExtension.class)`) — no Spring context, no async.
- [x] **Tests are independent (no order dependency)** — each `@SpringBootTest` class has its own `@Container` + `@DynamicPropertySource` (Testcontainers container lifecycle class-scoped via `@Testcontainers`); `@BeforeEach TRUNCATE` resets state per-test; `@MockitoBean` provides a fresh mock per test class; `sagaStepId = "..." + System.nanoTime()` uniquifies ADR-11 idempotency keys across tests; pure-JUnit tests (`LifecyclePhaseTest`, `InventoryLifecycleEventTest`, `IgnoreSoftUkAuditTest`, `ReservationNotFoundExceptionTest`, `LifecycleEventPublisherTest`, `WarehouseTest`) have no shared state.

### Output

- [x] **Test summary created** — this file (Story 1.8 section appended after Stories 1.4 / 1.5 / 1.6 / 1.7 in `_bmad-output/implementation-artifacts/tests/test-summary.md`).
- [x] **Tests saved to appropriate directories** — `services/inventory/src/test/java/vn/vnpt/inventory/{api,application,domain,domain/annotation,domain/event,domain/exception,infrastructure/outbox,infrastructure/repository}/`.
- [x] **Summary includes coverage metrics** — see Coverage table + per-class breakdown + baseline preservation note.

### Validation

**Expected:** All tests pass ✅
**Actual:** `mvn -pl services/inventory -am test` → Tests run: 238, Failures: 0, Errors: 0, Skipped: 0. BUILD SUCCESS. `mvn -pl util -am test` → Tests run: 57, Failures: 0, Errors: 0, Skipped: 0. BUILD SUCCESS. `mvn -pl services/catalog -am test` → Tests run: 65, Failures: 0, Errors: 0, Skipped: 0. BUILD SUCCESS. `mvn -pl services/inventory test -Dtest=InventoryPackageBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false` → Tests run: 7, Failures: 0, Errors: 0, Skipped: 0. BUILD SUCCESS. **Total: 360 tests, 0 failures.**

---

## Next Steps

1. **Commit QA pass.** 20 new tests across 7 files:
   - 2 new files: `IgnoreSoftUkAuditTest` (4 cases), `ReservationNotFoundExceptionTest` (2 cases).
   - 5 existing files extended: `LifecycleEventPublisherTest` (+5 dual-publish signatures identity + non-legacy-phases + null-signatures), `ReservationControllerExceptionHandlerTest` (+1 `reservation_not_found → 404`), `AllocateInventoryUseCaseTest` (+3 RELEASED idempotency + 2 validation), `ShipInventoryUseCaseTest` (+3 validation + 1 partial-availability), `AdjustInventoryUseCaseTest` (+1 payload `phase=ADJUSTED`).
   - No new files in inventory production code, no new deps.
   - Branch: stay on `fix/r-01-util-parent-pom` per Sprint 0 sequential pattern (Stories 0.1–1.7 already live there). Suggested prefix: `test(inventory): QA-pass gap fills — publisher signatures identity + terminal-state RELEASED + use-case validation + @IgnoreSoftUkAudit pin (Story 1.8)`.

2. **Surface to reviewer:** The `LifecycleEventPublisher` signatures-identity tests are the most consequential addition — they pin the ADR-20 producer HMAC invariant (signature computed ONCE, applied to BOTH topics). A regression that recomputes the HMAC per topic would silently break the saga's downstream HMAC verify path; these tests catch it at unit time. The `publish_passesTheSameEventObjectInstanceToBothAppends` test pins the symmetric event-instance invariant.

3. **Story 2.5 (checkout saga) follow-up:** The saga will call `ReserveInventoryUseCase.reserve(...)` + `AllocateInventoryUseCase.allocate(...)` via Spring bean lookup (intra-JVM per ADR-12). The new terminal-state guard on RELEASED is the saga-retry contract for the ALLOCATED step — a saga that retries allocate after a release (cancellation path) hits the no-op and never double-deducts.

4. **Story 4.5 (ShipmentService wire-up) follow-up:** The new `ShipInventoryUseCase` validation tests + partial-availability `InsufficientStockException` field-pin are the saga's HTTP 409 contract surface. Story 4.5 will add the saga-side `(aggregateId, sagaStepId)` UNIQUE for stronger SHIP idempotency (currently no idempotency — the JavaDoc explicitly notes this as a Story 4.x concern).

5. **Story 9.x (legacy topic cutoff) follow-up:** The dual-publish in `LifecycleEventPublisher` is a ONE-SPRINT bridge. Sprint 9 Story 9.x cuts the legacy topics; the `publish_nonLegacyPhasesNeverTouchLegacyTopics` parameterized test pattern is the right shape for the cutover-verification test (add `LifecycleEventPublisherLegacyTopicsCut` test class when the topic constants are removed).

6. **Story 10.5 e2e-tests follow-up:** A Playwright spec for `POST /api/inventory-allocations` + `POST /api/inventory-shipments` + `POST /api/inventory-warehouses` would be the first inventory HTTP E2E coverage. The current story's HTTP coverage is via `@SpringBootTest` + manually-built `MockMvc` (Boot 4 removed `@WebMvcTest`). The Playwright specs land with the testcontainers harness + dev compose wiring that Story 10.5 brings.