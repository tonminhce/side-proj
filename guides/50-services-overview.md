# 50 — Services Overview

> What each service does, which DB it owns, which port it uses, which story created it.

## The 15 services (5 working + 10 stubs)

`side-proj` has 15 service modules. **5 have actual code; 10 are skeleton placeholders** (created in Story 0.2 multi-module bootstrap, no business logic yet).

| # | Service | Status | Story | DB | Port (configured) | Port (this guide) | Description |
|---|---------|--------|-------|----|--------------------|--------------------|-------------|
| 1 | `catalog` | ✅ code | 1.1 | `catalog_db` | 8081 | 8091 | CatalogService — products, variants, attributes. Avro events on change. |
| 2 | `inventory` | ✅ code | 1.5 | `inventory_db` | 8083 | 8093 | InventoryService — per-warehouse ledger, reservations, soft-delete (`@SoftUk`). |
| 3 | `cart` | ✅ code | 2.1 | `cart_db` | 8085 | 8095 | CartService — anonymous + merge-on-login, auto-expire (Story 2.2). |
| 4 | `checkout` | ⚠ code but won't boot | 2.3 | `checkout_db` | 8084 | 8094 | CheckoutService — single-page checkout API + saga orchestrator (Story 2.5). |
| 5 | `payment` | ✅ code | 3.1 | `payment_db` | 8086 | 8096 | PaymentService — stable idempotency key + Stripe webhook dedup (Story 3.2). |
| 6 | `admin` | stub | — | — | — | — | admin-bff (placeholder) |
| 7 | `customer` | stub | — | — | — | — | customer-svc (placeholder) |
| 8 | `fulfillment` | stub | — | — | — | — | fulfillment-svc (placeholder) |
| 9 | `invoice` | stub | — | — | — | — | invoice-svc (placeholder) |
| 10 | `notification` | stub | — | — | — | — | notification-svc (placeholder) |
| 11 | `order` | stub | — | — | — | — | order-svc (placeholder) |
| 12 | `pricing` | stub | — | — | — | — | pricing-svc (placeholder) |
| 13 | `returns` | stub | — | — | — | — | returns-svc (placeholder) |
| 14 | `search` | stub | — | — | — | — | search-svc (placeholder) |
| 15 | `util` | ✅ library | 0.x | — | — | — | Shared library: Spring Boot autoconfig, JPA base, Modulith outbox, JCS/HMAC, retry, security shims. **Not a service** — installed to `~/.m2`. |

## Quick reference: which service for which user story

- "I want to list products" → `catalog` (Story 1.2+)
- "I want to add to cart" → `cart` (Story 2.1)
- "I want to checkout" → `checkout` (Story 2.3+)
- "I want to know stock" → `inventory` (Story 1.5+)
- "I want to pay" → `payment` (Story 3.1+)
- "I want to track my order" → `order` (Epic 4 — stub)
- "I want to return" → `returns` (Epic 7 — stub)

## Database ownership (database-per-service pattern, ADR-03)

Each service owns exactly one Postgres database. Cross-database joins are forbidden. Cross-service references are by UUID only (no FK constraints across DBs).

| DB | Owner | Owner user | Created by story |
|----|-------|-----------|-----------------|
| `app` | postgres | postgres | docker init (default) |
| `catalog_db` | catalog_user | catalog_user | 1.1 |
| `inventory_db` | inventory_user | inventory_user | 1.5 |
| `cart_db` | cart_user | cart_user | 2.1 |
| `checkout_db` | checkout_user | checkout_user | 2.3 |
| `payment_db` | payment_user | payment_user | 3.1 |

**Verify with:**
```bash
docker exec ecommerce-platform-postgres-1 psql -U postgres -tAc \
  "SELECT datname FROM pg_database WHERE datname LIKE '%_db' OR datname = 'app' ORDER BY 1"
```

## Inter-service comms (ADR-01 — Spring Modulith)

Within a single Spring Modulith deployment, services are packages. Cross-package calls happen via:

- **In-process events**: `@ApplicationModuleListener` listens for events from other packages. Used by Story 1.5 (catalog event → inventory ledger).
- **Modulith outbox**: events written to the `outbox` table inside one transaction with the business state. Modulith bridge polls and dispatches.

When services are split into separate JVMs (planned for later epics), the outbox will be backed by Kafka, Redis, or HTTP.

## What services are stub-only

Services 6-15 (admin, customer, fulfillment, invoice, notification, order, pricing, returns, search) have:
- `pom.xml` with Spring Boot 4 setup
- `src/main/java/vn/vnpt/<svc>/<SvcApplication.java` with `@SpringBootApplication` only
- No business logic, no entities, no migrations

They're placeholders for future stories. Building them with `mvn -pl services/<svc> package` produces a runnable jar that just starts an empty Spring context.

## Next

- [20-runtime-smoke.md](20-runtime-smoke.md) — what to curl
- [30-known-issues.md](30-known-issues.md) — what's broken
- [10-build-and-start.md](10-build-and-start.md) — how to run
