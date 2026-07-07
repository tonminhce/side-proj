# Test Automation Summary — Story 1.7

**Story:** Multi-warehouse per-variant stock (FR-10) — region-based picker dispatch
**Story file:** `_bmad-output/implementation-artifacts/1-7-multi-warehouse-per-variant-stock-fr-10.md`
**Workflow:** `bmad-qa-generate-e2e-tests`
**Test framework:** JUnit 5 + Spring Boot 4 + MockMvc + AssertJ + ArchUnit + Testcontainers (Postgres 16)
**Test command:** `mvn -pl services/inventory -am test`
**Date:** 2026-07-07

---

## Pre-existing Tests from Story 1.7 Implementation

Story 1.7 shipped with these test classes covering the FR-10 binding end-to-end:

| Path | Cases | Covers |
|------|------:|--------|
| `domain/RegionTest.java` | 2 | AC #4 — enum sanity (`name()` ↔ DB CHECK constraint, `valueOf` round-trip) |
| `application/PickWarehouseForReservationUseCaseTest.java` | 3 | AC #7 — in-region first match / cross-region fallback WARN / empty Optional |
| `application/ReserveInventoryUseCaseRegionDispatchTest.java` | 3 | AC #9 + #15 — picker dispatch fires / both-non-null throws / both-null throws |
| `api/InventoryReservationControllerTest.java` | 4 (pre-existing) + 3 (Story 1.7) | AC #10 + #11 — region dispatch 201, both-fields 400, invalid-region 400 |
| `api/InventoryOnHandQueryControllerTest.java` | 1 | AC #11 — per-warehouse breakdown returns 3 rows with correct onHand counts |
| `infrastructure/repository/WarehouseRepositoryTest.java` | 2 (pre-existing) + 1 (Story 1.7) | AC #6 — `findByRegionAndIsActiveTrueAndIsDeletedFalse` excludes soft-deleted |
| `InventoryApplicationContextTest.java` | 8 (pre-existing) + 1 (Story 1.7) | AC #13 — `flywayAppliedV005` |
| `InventoryPackageBoundaryTest.java` | 4 (pre-existing) + 1 (Story 1.7) | AC #16 — `inventory_pickerUsesOnlyOwnRepositories` |

**Story 1.7 implementation total: 14 new tests + 14 pre-existing = 28 inventory tests touching FR-10.**

## QA-Pass Gap Fills (this workflow run)

Four real coverage gaps were detected and auto-applied:

| Gap | Test | Why it matters |
|-----|------|----------------|
| **Picker with no in-region warehouses** (different code path than "in-region has insufficient stock") | `PickWarehouseForReservationUseCaseTest.pickWarehouseId_fallsBackToCrossRegionWhenInRegionListIsEmpty` | The existing cross-region fallback test seeds an in-region warehouse with 0 stock. The path where the in-region list is empty (no warehouses exist in the region) is a different code branch. Both must end at cross-region pick. |
| **Picker with no warehouses anywhere** | `PickWarehouseForReservationUseCaseTest.pickWarehouseId_returnsEmptyWhenNoWarehousesExist` | Guards the "empty in-region + empty cross-region → empty Optional" path. |
| **V005 seed data** | `InventoryApplicationContextTest.v005SeedsHcmAndHnWithCorrectRegions` | Migration V005's intent is to seed HCM-01 (SOUTH) + HN-01 (NORTH). A regression dropping the INSERTs would break dev smoke but no test caught it. |
| **Lowercase region parsing** | `InventoryReservationControllerTest.post_withLowercaseRegion_parsesAndForwards` | Controller defensively upper-cases (`Region.valueOf(shippingRegion.trim().toUpperCase())`). Verifies the defensive parse + captures the command to assert `Region.SOUTH` reached the use case. |
| **Idempotent reserve via shippingRegion** | `ReserveInventoryUseCaseRegionDispatchTest.reserve_withShippingRegion_isIdempotentOnSagaStepId` | ADR-11 invariant on the new dispatch path. Picker runs BEFORE the `findBySagaStepId` check; saga retry must return the existing reservation. |

**QA-pass additions: 5 tests.**

## Coverage

| Layer | Coverage | Notes |
|-------|----------|-------|
| Region enum | ✅ values + name() + valueOf round-trip | mirrors `ReservationStatus` / `InventoryReason` |
| PickWarehouseForReservationUseCase | ✅ in-region / cross-region fallback (2 paths) / empty Optional / no-warehouses edge | 5 tests |
| ReserveInventoryUseCase region dispatch | ✅ happy path / both-null throws / both-non-null throws / idempotent retry | 4 tests |
| HTTP POST `/api/inventory-reservations` | ✅ region dispatch 201 / both-fields 400 / invalid-region 400 / lowercase parse | 4 Story 1.7 cases (+ 4 pre-existing) |
| HTTP GET `/api/inventory/variants/{id}/on-hand` | ✅ 3-warehouse breakdown with mixed stock | 1 test |
| WarehouseRepository region queries | ✅ region-scoped active+non-deleted / soft-delete exclusion | 1 Story 1.7 test |
| Flyway V005 | ✅ applied + seeded HCM-01 (SOUTH) + HN-01 (NORTH) | 2 tests |
| ArchUnit boundary | ✅ picker uses only own repositories | 1 new rule (5/5 total) |
| Cross-region WARN log | ✅ emitted via `@Slf4j` — no separate test (covered by use-case path) | skip |

## Test Execution

```
mvn -pl services/inventory -am test
...
Tests run: 188, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

- Story 1.6 baseline: 169 inventory tests
- Story 1.7 implementation: +14 tests (= 183)
- QA-pass gap fills: +5 tests (= **188**)

## Validation Against `checklist.md`

- [x] API tests generated (if applicable) — `InventoryReservationControllerTest`, `InventoryOnHandQueryControllerTest` (Spring MockMvc slice + `@SpringBootTest` MOCK)
- [x] E2E tests generated (if UI exists) — N/A (no UI in Story 1.7; HTTP endpoints covered)
- [x] Tests use standard test framework APIs — JUnit 5 + AssertJ + MockMvc + Testcontainers
- [x] Tests cover happy path — POST 201 with `warehouseId`, POST 201 with `shippingRegion`, GET 200 with breakdown
- [x] Tests cover 1-2 critical error cases — both-fields 400, invalid-region 400, both-null / both-non-null throws, insufficient stock, idempotency
- [x] All generated tests run successfully — 188/188 pass, 0 failures, 0 errors, 0 skipped
- [x] Tests use proper locators (semantic, accessible) — semantic: `Region.values()`, derived queries, Spring `@MockitoBean`; HTTP via JSON path assertions
- [x] Tests have clear descriptions — `@Test` method names read like spec lines (`pickWarehouseId_picksInRegionWarehouseWithEnoughStock`, `reserve_withShippingRegion_isIdempotentOnSagaStepId`)
- [x] No hardcoded waits or sleeps — no `Thread.sleep` / `Awaitility` waits; assertions are direct
- [x] Tests are independent (no order dependency) — `@BeforeEach TRUNCATE TABLE` clears data per test; saga-step IDs uniquified with `nanoTime`
- [x] Test summary created — this file
- [x] Tests saved to appropriate directories — `services/inventory/src/test/java/vn/vnpt/inventory/`
- [x] Summary includes coverage metrics — see Coverage table

## Next Steps

- Run `dev/scripts/multistock_smoke.sh` against a live `docker compose up` to validate the end-to-end flow against real Postgres + the booted service (Story 1.7 AC #18).
- Story 2.5 (checkout saga) will exercise the `shippingRegion` path via Spring bean lookup — no HTTP. Re-run this suite when 2.5 lands.
- Story 8.x (admin distance scoring) is the future Story that may replace the coarse region-based picker.