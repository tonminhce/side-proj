---
audience: dev-agent (Amelia)
project: side-project
date: 2026-07-06
sprint: 1 (Catalog + Inventory)
how-to-use: step-by-step handbook. Read sequentially; each step has deliverables + verification + pitfalls.
---

# Sprint 1 Dev Handbook — Catalog + Inventory

> **Sprint goal:** Build CatalogService + InventoryService with Avro event publishing, FOR UPDATE reservation, and full soft-delete + @SoftUk enforcement.
> **Sprint 1 stories:** 8 (Story 1.1..1.8). See `epics.md` for full AC.
> **Risks solved:** DI-01 (oversell race, Story 1.6), DI-09 (soft-delete uniqueness, Story 1.8).

---

## 0. Pre-Sprint 1 Checklist (must complete first)

### ✅ Sprint 0 must be DONE

Sprint 1 is **not greenfield**. Sprint 0 must be complete:

- [ ] Story 0.1 — `util/` parent pom fixed (R-01)
- [ ] Story 0.2 — monorepo bootstrapped
- [ ] Story 0.3 — dev docker-compose (Postgres + Kafka KRaft + ES + Redis + Apicurio + MinIO)
- [ ] Story 0.4 — CI scaffold (GitHub Actions + archunit + Spotless + Prettier)
- [ ] Story 0.5 — Snowflake strict mode

**Verify:** `mvn clean install` from project root succeeds. `docker compose -f dev/docker-compose.yml up` brings all services up healthy.

### ✅ Confirm constraints

- Boot 4.0.0 + Spring Cloud 2025.1 "Oakwood" installed
- Java 25
- Per architecture's `addendum.md` A4: version matrix pinned

---

## 1. Story 1.1 — CatalogService Maven module bootstrap + Per-service Postgres DB

### Goal
Create the `services/catalog/` Maven module with its own Postgres DB. Establish the per-service DB convention (ADR-03).

### Steps

```bash
# 1. Create the module directory
mkdir -p services/catalog/src/main/{java/vn/vnpt/catalog/{api,domain,application,infrastructure,config},resources/db/migration}
mkdir -p services/catalog/src/test/java/vn/vnpt/catalog

# 2. Add to root pom.xml
#    <module>services/catalog</module>

# 3. Create services/catalog/pom.xml
#    - parent: project root pom
#    - dependencies: spring-boot-starter-data-jpa, postgresql, util/, spring-modulith-outbox

# 4. Add CatalogApplication.java
#    @SpringBootApplication
#    @Modulith
#    public class CatalogApplication { ... }

# 5. Configure application.yml
#    spring:
#      datasource:
#        url: jdbc:postgresql://localhost:5432/catalog
#      jpa:
#        hibernate.ddl-auto: validate

# 6. Create Flyway migration V001__create_products.sql
```

### Deliverables

- [ ] `services/catalog/pom.xml` exists, `mvn -pl services/catalog -am clean install` succeeds
- [ ] CatalogApplication boots when Postgres is running
- [ ] Flyway migrations apply on startup (verify `psql -d catalog -c "\dt"` shows tables)

### Verification

```bash
# Build the module
mvn -pl services/catalog -am clean install

# Bring up the dev platform + this service
docker compose -f dev/docker-compose.yml up -d
mvn -pl services/catalog spring-boot:run

# Check tables
psql -h localhost -U catalog -d catalog -c "\dt"
# Should see: products, variants, attributes, outbox, processed_event
```

### Pitfalls

- ❌ Don't share the `postgres` DB across services. Each service has its own DB. Cross-service refs are by aggregate ID, NOT by FK.
- ❌ Don't use `spring.jpa.hibernate.ddl-auto: update` — use `validate` + Flyway migrations.
- ❌ Don't put DDL in the JPA entity classes.
- ❌ Don't add `@EnableJpaRepositories(basePackages = "...")` if Spring Boot's auto-config handles it. Less is more.

---

## 2. Story 1.2 — Product aggregate + variant graph (FR-1, FR-2, FR-4)

### Goal
Model the Product → Option → Variant aggregate. SKU is hash of variant attributes. Attributes stored as JSONB.

### Steps

```java
// domain/Product.java
@Entity @Table(name = "products")
public class Product extends BaseEntity {  // from util
    private String name;
    private String slug;
    @OneToMany(cascade = CascadeType.ALL, mappedBy = "product")
    private List<Variant> variants = new ArrayList<>();
    // ...
}

// domain/Variant.java
@Entity @Table(name = "variants")
public class Variant extends BaseEntity {
    @ManyToOne
    private Product product;
    private String sku;  // hash(red|M) — see ADR or util
    private String attributes;  // JSONB
    private BigDecimal priceList;
    private BigDecimal priceSale;
    private String imageSet;
    // ...
}

// In Variant constructor or factory:
public static Variant create(Product p, Map<String, String> attrs, BigDecimal price) {
    var v = new Variant();
    v.sku = hashOf(p.getId(), attrs);  // stable hash
    v.attributes = toJson(attrs);
    // ...
}
```

### Deliverables

- [ ] Product + Variant entities compile
- [ ] SKU generator is stable: same attrs → same SKU across rebuilds
- [ ] `attributes` column is JSONB, queryable with `nativeQuery` or Hibernate's JSON support

### Verification

```java
// Unit test: same attributes → same SKU
@Test
void skuIsStable() {
    var p = new Product();
    var v1 = Variant.create(p, Map.of("color", "red", "size", "M"), ...);
    var v2 = Variant.create(p, Map.of("color", "red", "size", "M"), ...);
    assertEquals(v1.getSku(), v2.getSku());
}
```

### Pitfalls

- ❌ Don't use `Long.hashCode()` for SKU — unstable across JVM restarts (well, actually it IS stable per session but varies per JVM, which is fine for ID generation but bad for SKU). Use SHA-256 or stable hash.
- ❌ Don't add side effects to Variant constructor — use factory method.
- ❌ Don't use `@Lob` for JSONB — use Hibernate's `@JdbcTypeCode(SqlTypes.JSON)` (Hibernate 6+) or `hypersistence-utils` `@Type(JsonType.class)`.

---

## 3. Story 1.3 — Catalog change events with Avro strict compat (FR-5)

### Goal
Publish `catalog.product.created`, `catalog.product.updated`, `catalog.product.price_changed` Avro events via outbox + Modulith outbox bridge.

### Steps

```java
// 1. Define Avro schema (resources/avro/catalog/CatalogProductCreated.avsc)
{
  "namespace": "vn.vnpt.catalog.events",
  "type": "record",
  "name": "CatalogProductCreated",
  "fields": [
    {"name": "eventId", "type": "long"},
    {"name": "productId", "type": "long"},
    {"name": "name", "type": "string"},
    {"name": "slug", "type": "string"},
    {"name": "createdAt", "type": "long", "logicalType": "timestamp-millis"}
  ]
}

// 2. Register schema in Apicurio (CI step in Story 0.4)

// 3. Domain event
public record CatalogProductCreated(
    long eventId, long productId, String name, String slug, Instant createdAt
) {}

// 4. In CreateProductUseCase, write to outbox in same transaction
@Transactional
public Product create(CreateProductCommand cmd) {
    var p = Product.create(cmd);
    repo.save(p);
    outbox.append(new CatalogProductCreated(
        snowflakeId(), p.getUuid(), p.getName(), p.getSlug(), Instant.now()
    ));
    return p;
}
```

### Deliverables

- [ ] Avro schema registered in Apicurio (verify via `curl http://localhost:8080/apis/registry/v2/groups/catalog/artifacts`)
- [ ] Outbox row inserted on save (verify via SQL)
- [ ] Modulith outbox bridge picks up + publishes within 500ms (verify in Kafka consumer log)

### Verification

```bash
# 1. Start everything
docker compose -f dev/docker-compose.yml up -d
mvn -pl services/catalog spring-boot:run

# 2. Create a product (via API or seed)
curl -X POST http://localhost:8080/api/catalog/products -d '...'

# 3. Check outbox
psql -h localhost -U catalog -d catalog -c "SELECT * FROM outbox ORDER BY id DESC LIMIT 1;"

# 4. Check Kafka topic
docker exec -it kafka kafka-console-consumer --topic catalog.product.created --from-beginning --max-messages 1
```

### Pitfalls

- ❌ Don't publish events directly to Kafka from the use case. Always go through outbox. Otherwise: outbox says success, Kafka publish fails, business state is committed but event is lost.
- ❌ Don't include `eventId` inside payload if Apicurio schema doesn't have it. Keep event envelope (eventId, occurredAt) OUTSIDE the Avro record; the Avro record is the payload.
- ❌ Don't reuse event type names across versions. `CatalogProductCreated` and `CatalogProductCreated_v2` if breaking change.

---

## 4. Story 1.4 — Admin UI catalog read view (FR-6, FR-7)

### Goal
Staff can read the catalog via role-gated `/admin/catalog` page in Next.js.

### Steps

```bash
# 1. Add to Next.js admin app (already in monorepo from Sprint 0)
# frontend/admin/src/app/catalog/page.tsx
```

```typescript
// Pseudocode
import { serverClient } from '@/lib/server-client';

export default async function CatalogPage() {
    // server-side fetch from CatalogService via BFF
    const products = await serverClient.catalog.list();
    return <ProductTable products={products} />;
}
```

### Deliverables

- [ ] `/admin/catalog` renders a paginated table
- [ ] Read-only (write actions disabled in Sprint 1; FR-62 read-first)
- [ ] Audit trail: every page load logs to OTel (no PII)

### Verification

- [ ] Login as staff → `/admin/catalog` shows products
- [ ] Login as customer → redirect to home (RBAC works)
- [ ] OTel shows page-load span with `user_role=staff` (no email/PII)

### Pitfalls

- ❌ Don't fetch from CatalogService directly from the browser. Always go through BFF.
- ❌ Don't ship without RBAC. The `/admin/*` route MUST check role server-side.

---

## 5. Story 1.5 — InventoryService per-warehouse ledger (FR-8)

### Goal
InventoryService has its own DB. `inventory_ledger` is double-entry. `on_hand` is a sum-derivation (computed column or read-only view).

### Steps

```sql
-- V001__create_inventory_ledger.sql
CREATE TABLE inventory_ledger (
    id BIGSERIAL PRIMARY KEY,
    variant_uuid BIGINT NOT NULL,
    warehouse_id VARCHAR(50) NOT NULL,
    delta BIGINT NOT NULL,  -- positive = receive, negative = release
    event_id BIGINT,        -- optional: originating outbox event
    reason VARCHAR(50) NOT NULL,  -- 'reservation' | 'allocation' | 'shipment' | 'adjustment' | 'release'
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_inventory_variant_warehouse ON inventory_ledger(variant_uuid, warehouse_id);

-- Read-only view: current on-hand per (variant, warehouse)
CREATE VIEW inventory_on_hand AS
SELECT
    variant_uuid, warehouse_id,
    SUM(delta) AS on_hand
FROM inventory_ledger
GROUP BY variant_uuid, warehouse_id;
```

### Deliverables

- [ ] `inventory_ledger` table created via Flyway
- [ ] `inventory_on_hand` view accessible
- [ ] InventoryService has read + write repos
- [ ] InventoryService is the **only** service that writes to `inventory_ledger` (per FR-13)

### Verification

```sql
-- Seed: receive 10 units of variant 1 at HCM
INSERT INTO inventory_ledger (variant_uuid, warehouse_id, delta, reason) VALUES (1, 'HCM', 10, 'adjustment');
-- Verify view
SELECT * FROM inventory_on_hand WHERE variant_uuid = 1;
-- Should show: 1, HCM, 10
```

### Pitfalls

- ❌ Don't store `on_hand` as a mutable column. Always derive from ledger.
- ❌ Don't let any service OTHER than InventoryService write to `inventory_ledger`. Enforce via separate DB user permissions.
- ❌ Don't delete ledger rows. Corrections are new rows with `delta` of opposite sign.

---

## 6. Story 1.6 — Reservation with TTL (FR-9) — solves DI-01 root cause

### Goal
`inventory.reserve()` is atomic per row via `SELECT FOR UPDATE`. Reservations auto-expire after 15 min. **Solves DI-01** (oversell race on concurrent checkout).

### Steps

```java
@Transactional
public Reservation reserve(ReservationRequest req) {
    // 1. SELECT FOR UPDATE on the variant row
    var variant = em.find(Variant.class, req.variantId, LockModeType.PESSIMISTIC_WRITE);
    
    // 2. Check on_hand >= requested
    var available = inventoryRepo.getOnHand(req.variantId, req.warehouseId);
    if (available < req.qty) {
        throw new InsufficientStockException();
    }
    
    // 3. Insert negative delta in ledger
    inventoryRepo.appendLedger(req.variantId, req.warehouseId, -req.qty, "reservation", req.eventId);
    
    // 4. Insert reservation record with TTL
    var reservation = new Reservation(req.cartId, req.variantId, req.qty, Instant.now().plus(15, MINUTES));
    reservationRepo.save(reservation);
    
    return reservation;
}

// Sweeper job (Quartz) every minute
@Scheduled(cron = "0 * * * * *")
public void sweepExpired() {
    var expired = reservationRepo.findByExpiresAtBefore(Instant.now());
    for (var r : expired) {
        inventoryRepo.appendLedger(r.variantId, r.warehouseId, r.qty, "release", null);
        reservationRepo.delete(r);
        // emit inventory.released event
    }
}
```

### Deliverables

- [ ] `reserve()` is `@Transactional` with `SELECT FOR UPDATE`
- [ ] Concurrent reservations of last unit return 1 success + N-1 `InsufficientStockException`
- [ ] Sweeper runs every minute, releases expired reservations
- [ ] 100x concurrent reservation test passes

### Verification

```java
@Test
void concurrentReservationOfLastUnit() throws Exception {
    // Setup: variant with 1 unit in stock
    seedInventory(1L, "HCM", 1);
    
    // 100 concurrent reservations of 1 unit
    var executor = Executors.newFixedThreadPool(100);
    var latch = new CountDownLatch(100);
    var successes = new AtomicInteger(0);
    var failures = new AtomicInteger(0);
    for (int i = 0; i < 100; i++) {
        executor.submit(() -> {
            try {
                reserve(1L, "HCM", 1);
                successes.incrementAndGet();
            } catch (InsufficientStockException e) {
                failures.incrementAndGet();
            } finally {
                latch.countDown();
            }
        });
    }
    latch.await();
    assertEquals(1, successes.get());
    assertEquals(99, failures.get());
}
```

### Pitfalls

- ❌ Don't use `synchronized` instead of `SELECT FOR UPDATE`. Serializes per JVM, not per row.
- ❌ Don't use a counter column (e.g., `available_units`) updated with `UPDATE ... SET available = available - 1`. Race conditions on read-then-write.
- ❌ Don't forget the sweeper. Without it, stuck reservations block stock forever.
- ❌ Don't make the TTL > 15 min. The user might leave their cart; you don't want to hold stock.

---

## 7. Story 1.7 — Multi-warehouse per-variant stock (FR-10)

### Goal
Each variant has per-warehouse `on_hand`. Reservation picks the closest warehouse.

### Steps

```java
// Pick the closest warehouse (e.g., from shipping address)
public Warehouse pickClosest(Address address) {
    // Could use PostGIS, or a pre-computed distance table
    return warehouseRepo.findAll().stream()
        .min(comparing(w -> w.distanceTo(address)))
        .orElseThrow();
}
```

### Deliverables

- [ ] Each variant has per-warehouse stock rows
- [ ] Reservation picks nearest warehouse (default: HCM if no address)
- [ ] Carrier distance computation (simple for v1; PostGIS optional)

### Verification

- [ ] Variant 1 with stock {HCM: 10, HN: 0}: shipping to HCM → reserve from HCM
- [ ] Variant 1 with stock {HCM: 0, HN: 5}: shipping to HCM → reserve from HN (or fail if no address-known fallback)

### Pitfalls

- ❌ Don't over-engineer the distance algorithm. v1: a simple table. v2: PostGIS.
- ❌ Don't try to split a single order across multiple warehouses in v1. One order, one warehouse.

---

## 8. Story 1.8 — Inventory lifecycle events (FR-11) + `@SoftUk` extension (FR-12) — solves DI-09

### Goal
Every inventory state change emits an event. Every new soft-deletable entity uses `@SoftUk`.

### Steps

```java
// 1. Define events
public record InventoryReserved(long eventId, long reservationId, long variantId, long qty) {}
public record InventoryReleased(long eventId, long reservationId, long variantId, long qty) {}
public record InventoryAllocated(long eventId, long allocationId, long variantId) {}
public record InventoryShipped(long eventId, long shipmentId, long variantId, long qty) {}
public record InventoryAdjusted(long eventId, long adjustmentId, long variantId, long delta) {}

// 2. Emit from use cases (outbox pattern, per ADR-04)
// 3. Add @SoftUk to InventoryReservation, InventoryAllocation, InventoryShipment entities
// 4. Configure CI lint to reject new soft-deletable entities without @SoftUk
```

### Deliverables

- [ ] 5 lifecycle event types defined + emitted
- [ ] All soft-deletable entities use `@SoftUk`
- [ ] CI lint enforces `@SoftUk` (fails on new entity with `isDeleted` field but no annotation)

### Verification

```java
@Test
void softDeleteKeepsUniqueConstraint() {
    // Setup: variant SKU "ABC-1"
    seedVariant("ABC-1");
    // Soft-delete it
    var v = repo.findBySku("ABC-1");
    v.setDeleted(true);
    repo.save(v);
    // Try to create a new variant with the same SKU
    assertThrows(DataIntegrityViolationException.class, () -> {
        var v2 = new Variant("ABC-1");
        repo.save(v2);
    });
}
```

### Pitfalls

- ❌ Don't soft-delete by setting `isDeleted = true` and calling `repo.save()` without using `@SoftUk`. The `@SoftUk` annotation makes the unique constraint exclude soft-deleted rows.
- ❌ Don't hard-delete in a soft-delete world. The whole point of `@SoftUk` is to keep history.
- ❌ Don't forget the `processed_event` table for consumer dedup. Events will be re-delivered by Kafka.

---

## 9. Sprint 1 completion checklist

- [ ] All 8 stories pass their AC
- [ ] All Avro schemas registered in Apicurio + compat CI gate green
- [ ] Concurrent reservation test (Story 1.6) passes 100x
- [ ] Soft-delete uniqueness test (Story 1.8) passes
- [ ] All audit trail entries recorded (`processed_event` + `audit_trail`)
- [ ] OTel spans visible in Tempo
- [ ] LGTM dashboards show the 3 panels (catalog events, reservation success rate, on-hand)
- [ ] `sprint-status.yaml` updated: Stories 1.1..1.8 marked `done`

**Sprint 1 demo (to PM):**
- "Create a product, see it in admin"
- "Receive stock, see inventory ledger"
- "Reserve stock from 100 concurrent checkouts — only 1 succeeds if on_hand = 1"

**Then proceed to Sprint 2** (Cart + Checkout saga).
