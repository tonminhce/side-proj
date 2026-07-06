---
audience: dev-agent (Amelia)
project: side-project
date: 2026-07-06
sprint: 1 (Catalog + Inventory)
how-to-use: per-story quick card. Use alongside SPRINT-1-DEV-HANDBOOK (step-by-step narrative). This file is for quick lookup when you know which story you're working on.
---

# Epic 1 Stories — Quick Reference (Sprint 1)

> **Sprint:** 1 (Catalog + Inventory)
> **Stories:** 8 (1.1..1.8) + dependencies on Sprint 0
> **Source:** `epics.md` §5.1 for full AC. **Use alongside:** `SPRINT-1-DEV-HANDBOOK.md` for narrative guidance.

---

## Story 1.1 — CatalogService Maven module bootstrap

**FR covered:** FR-6 (CatalogService owns its DB)
**Files to create:**
```
services/catalog/
├── pom.xml
└── src/main/
    ├── java/vn/vnpt/catalog/CatalogApplication.java
    └── resources/
        ├── application.yml
        └── db/migration/V001__init.sql
```

**AC (Given/When/Then):**
- Given monorepo is bootstrapped
- When I `mvn -pl services/catalog -am clean install`
- Then the build succeeds
- And `CatalogApplication` boots when Postgres is reachable
- And Flyway migrations apply on startup

**Verification:** `mvn -pl services/catalog -am clean install` exit 0; `psql -c "\dt"` shows tables.

**Pitfalls:** ❌ Don't share Postgres DB. ❌ Don't use `ddl-auto: update` (use `validate` + Flyway).

**Sprint Status:** Update `sprint-status.yaml` `1-1-catalogservice-maven-module-bootstrap-per-service-postgres-db: backlog → in-progress → done`.

---

## Story 1.2 — Product aggregate + variant graph

**FR covered:** FR-1 (product → option → variant graph), FR-2 (variant fields), FR-4 (JSONB attributes)

**Files to create:**
```
services/catalog/src/main/java/vn/vnpt/catalog/domain/
├── Product.java
└── Variant.java
services/catalog/src/main/resources/db/migration/V002__create_products.sql
```

**Key code sketch:**

```java
@Entity @Table(name = "products")
public class Product extends BaseEntity {  // util
    @Column(nullable = false) private String name;
    @Column(unique = true) private String slug;
    @OneToMany(cascade = ALL, mappedBy = "product")
    private List<Variant> variants = new ArrayList<>();
}

@Entity @Table(name = "variants")
public class Variant extends BaseEntity {
    @ManyToOne(fetch = LAZY) private Product product;
    @Column(nullable = false) private String sku;  // stable hash
    @Column(columnDefinition = "jsonb") private String attributesJson;
    private BigDecimal priceList;
    private BigDecimal priceSale;
    private String imageSet;
}
```

**AC (Given/When/Then):**
- Given a Product + Variant
- When I save with attributes `{"color":"red","size":"M"}`
- Then `sku = sha256(productId + "|color:red|size:M")` (stable across rebuilds)
- And attributes stored as JSONB, queryable with native SQL
- And `isDeleted` field inherited from `BaseEntity`

**Verification:** Unit test — same attributes → same SKU; different attributes → different SKU.

**Pitfalls:** ❌ Don't use `Long.hashCode()` for SKU (unstable). Use SHA-256. ❌ Don't use `@Lob` for JSONB.

---

## Story 1.3 — Catalog change events with Avro strict compat

**FR covered:** FR-5 (catalog Avro events)

**Files to create:**
```
services/catalog/src/main/avro/catalog/
├── CatalogProductCreated.avsc
├── CatalogProductUpdated.avsc
└── CatalogProductPriceChanged.avsc
services/catalog/src/main/java/vn/vnpt/catalog/domain/event/
├── CatalogProductCreated.java
├── CatalogProductUpdated.java
└── CatalogProductPriceChanged.java
services/catalog/src/main/java/vn/vnpt/catalog/application/
└── CreateProductUseCase.java  (extends to write outbox)
```

**Avro schema example:**

```json
{
  "namespace": "vn.vnpt.catalog.events",
  "type": "record",
  "name": "CatalogProductCreated",
  "fields": [
    {"name": "productId", "type": "long", "logicalType": "long"},
    {"name": "name", "type": "string"},
    {"name": "slug", "type": "string"},
    {"name": "createdAt", "type": "long", "logicalType": "timestamp-millis"}
  ]
}
```

**AC (Given/When/Then):**
- Given a product is created
- When the transaction commits
- Then a row is inserted in `outbox` table in the same transaction
- And the Modulith outbox bridge publishes to Kafka topic `catalog.product.created` within 500ms
- And the Avro schema is registered in Apicurio + CI compat gate is green

**Verification:** `psql -c "SELECT * FROM outbox ORDER BY id DESC LIMIT 1"` shows the row; `kafka-console-consumer --topic catalog.product.created --from-beginning` shows the message.

**Pitfalls:** ❌ Don't publish directly to Kafka (always through outbox). ❌ Don't put `eventId` inside the Avro record (envelope has it).

---

## Story 1.4 — Admin UI catalog read view

**FR covered:** FR-6 (CDC propagation), FR-7 (admin CRUD + audit)

**Files to create:**
```
frontend/admin/src/app/admin/catalog/
├── page.tsx
├── [productId]/page.tsx
└── components/ProductTable.tsx
```

**AC (Given/When/Then):**
- Given I'm logged in as `staff` or `admin`
- When I navigate to `/admin/catalog`
- Then I see a paginated table of products
- And I can click a product to see its variants
- And write actions are disabled (Sprint 1 = read-first; FR-62)

**Verification:** Login as staff → catalog visible. Login as customer → 403 redirect.

**Pitfalls:** ❌ Don't fetch from CatalogService directly from browser. Use BFF. ❌ Don't skip RBAC server-side.

---

## Story 1.5 — InventoryService per-warehouse ledger

**FR covered:** FR-8 (double-entry ledger), FR-13 (InventoryService is the only writer)

**Files to create:**
```
services/inventory/
├── pom.xml
└── src/main/
    ├── java/vn/vnpt/inventory/InventoryApplication.java
    └── resources/
        ├── application.yml
        └── db/migration/V001__create_inventory_ledger.sql
```

**Schema:**

```sql
CREATE TABLE inventory_ledger (
    id BIGSERIAL PRIMARY KEY,
    variant_uuid BIGINT NOT NULL,
    warehouse_id VARCHAR(50) NOT NULL,
    delta BIGINT NOT NULL,  -- +receive, -release
    reason VARCHAR(50) NOT NULL,  -- 'reservation'|'allocation'|'shipment'|'adjustment'|'release'
    event_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_inv_ledger_variant_wh ON inventory_ledger(variant_uuid, warehouse_id);

CREATE VIEW inventory_on_hand AS
    SELECT variant_uuid, warehouse_id, SUM(delta) AS on_hand
    FROM inventory_ledger GROUP BY variant_uuid, warehouse_id;
```

**AC (Given/When/Then):**
- Given a fresh InventoryService
- When I run Flyway migrations
- Then `inventory_ledger` and `inventory_on_hand` exist
- And receiving 10 units creates a +10 ledger row
- And `on_hand = 10` from the view
- And only InventoryService can write to this table (DB user permissions)

**Verification:** `SELECT * FROM inventory_on_hand WHERE variant_uuid = 1` returns `on_hand = 10`.

**Pitfalls:** ❌ Don't store `on_hand` as mutable column. ❌ Don't allow other services to write here.

---

## Story 1.6 — Reservation with TTL (DI-01 root cause fix)

**FR covered:** FR-9 (SELECT FOR UPDATE + TTL)

**Files to create:**
```
services/inventory/src/main/java/vn/vnpt/inventory/
├── domain/Reservation.java
├── application/ReserveStockUseCase.java
└── application/SweepExpiredReservationsJob.java
```

**Key code sketch:**

```java
@Transactional
public Reservation reserve(long variantId, String warehouseId, long qty) {
    var variant = em.find(Variant.class, variantId, LockModeType.PESSIMISTIC_WRITE);
    var available = invRepo.getOnHand(variantId, warehouseId);
    if (available < qty) throw new InsufficientStockException();
    invRepo.appendLedger(variantId, warehouseId, -qty, "reservation", null);
    var r = new Reservation(variantId, warehouseId, qty, Instant.now().plus(15, MINUTES));
    return reservationRepo.save(r);
}

@Scheduled(cron = "0 * * * * *")  // every minute
public void sweepExpired() {
    var expired = reservationRepo.findByExpiresAtBefore(Instant.now());
    expired.forEach(r -> {
        invRepo.appendLedger(r.variantId, r.warehouseId, r.qty, "release", null);
        reservationRepo.delete(r);
    });
}
```

**AC (Given/When/Then):**
- Given variant with `on_hand = 1`
- When 100 concurrent threads call `reserve(1)` each
- Then exactly 1 succeeds
- And 99 throw `InsufficientStockException`
- And after 15 min, the 1 reservation auto-expires (stock released)

**Verification:** Integration test with `ExecutorService` and `CountDownLatch`; `assertEquals(1, successes.get())`.

**Pitfalls:** ❌ Don't use `synchronized`. ❌ Don't use mutable counter column. ❌ Don't forget the sweeper.

---

## Story 1.7 — Multi-warehouse per-variant stock

**FR covered:** FR-10 (per-warehouse `on_hand`)

**Files to create:**
```
services/inventory/src/main/java/vn/vnpt/inventory/
├── domain/Warehouse.java
└── application/PickClosestWarehouseUseCase.java
```

**Key code:**

```java
public Warehouse pickClosest(Address shippingAddress) {
    return warehouseRepo.findAll().stream()
        .min(Comparator.comparingDouble(w -> distance(w, shippingAddress)))
        .orElseThrow();
}

private double distance(Warehouse w, Address a) {
    // v1: simple table lookup; v2: PostGIS
    return w.distanceKm.getOrDefault(a.city(), 999.0);
}
```

**AC (Given/When/Then):**
- Given variant 1 with `{HCM: 10, HN: 0}`
- When a customer checks out shipping to HCM
- Then the reservation is drawn from HCM
- And falls back to other warehouses if HCM is exhausted

**Verification:** Integration test with mocked address → warehouse distance.

**Pitfalls:** ❌ Don't over-engineer distance (v1: simple table). ❌ Don't split one order across warehouses in v1.

---

## Story 1.8 — Inventory lifecycle events + `@SoftUk`

**FR covered:** FR-11 (lifecycle events), FR-12 (soft-delete uniqueness)

**Files to create:**
```
services/inventory/src/main/avro/inventory/
├── InventoryReserved.avsc
├── InventoryReleased.avsc
├── InventoryAllocated.avsc
├── InventoryShipped.avsc
└── InventoryAdjusted.avsc
services/inventory/src/main/java/vn/vnpt/inventory/
├── domain/event/InventoryReserved.java (etc.)
└── domain/Reservation.java  (with @SoftUk)
```

**Key code:**

```java
@Entity
@SoftUk(fields = {"variantId", "warehouseId", "expiresAt"})  // util annotation
@Table(name = "reservations")
public class Reservation extends BaseEntity {
    private long variantId;
    private String warehouseId;
    private long qty;
    private Instant expiresAt;
}
```

**AC (Given/When/Then):**
- Given any inventory state change (reserve, release, allocate, ship, adjust)
- When the change is committed
- Then the corresponding `inventory.*` event is emitted to outbox
- And a new soft-deletable entity without `@SoftUk` fails the CI lint

**Verification:** Soft-delete uniqueness test — soft-delete then re-create with same key fails.

**Pitfalls:** ❌ Don't hard-delete (breaks `@SoftUk` semantics). ❌ Don't forget the `processed_event` dedup table.

---

## Sprint 1 demo checklist

- [ ] Show CatalogService with a created product visible in admin
- [ ] Show InventoryService ledger with on_hand=10
- [ ] Demo concurrent reservation: 1 success + 99 failures on 1-unit stock
- [ ] Demo reservation auto-expiry: reserve, wait 15 min, stock released
- [ ] Verify OTel spans in Tempo for full flow

## Per-story status updates

After each story passes:

```yaml
# _bmad-output/implementation-artifacts/sprint-status.yaml
1-1-catalogservice-maven-module-bootstrap-per-service-postgres-db: done
1-2-product-aggregate-variant-graph-fr-1-fr-2-fr-4: done
... (etc.)
```
