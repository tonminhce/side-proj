---
audience: PM, architect, dev, QA
project: side-project
date: 2026-07-06
how-to-use: domain-driven design map. When you have a problem, find the right service / aggregate to modify. Pair with epics.md (FR coverage) and architecture.md (ADRs).
---

# Problem Domains Map — side-project

> **Bounded context (DDD) view:** 13 services + Auth folded into Customer. Each owns a problem domain, a database, an outbox table, and emits its own events.
> **Use case:** "I have a problem — which service should I touch?"

---

## 1. The 13 problem domains

| # | Service | Domain | Owns | File path |
|---|---|---|---|---|
| 1 | **catalog** | Product catalog | products, variants, attributes, prices | `services/catalog/` |
| 2 | **inventory** | Stock + reservation | inventory_ledger, reservations, on_hand view | `services/inventory/` |
| 3 | **cart** | Shopping cart | cart, cart_lines, anonymous cart_id | `services/cart/` |
| 4 | **checkout** | Checkout orchestration | checkout aggregate, single-page-checkout state | `services/checkout/` |
| 5 | **payment** | Money + Stripe | payment aggregate, webhook_dedup, payment_intent refs | `services/payment/` |
| 6 | **order** | Post-payment lifecycle | order, order_state_transition, priceSnapshot | `services/order/` |
| 7 | **fulfillment** | Shipping | shipments, carrier adapters, tracking | `services/fulfillment/` |
| 8 | **returns** | RMA workflow | returns, refunds (cumulative-safety) | `services/returns/` |
| 9 | **customer** | User + auth | customer, address, login, RBAC, MFA, audit | `services/customer/` |
| 10 | **search** | Catalog search | read-side ES indices, faceting, recommendations | `services/search/` |
| 11 | **notification** | Email + push | sendgrid + fcm channels, templates, user prefs | `services/notification/` |
| 12 | **admin** | Staff operations | audit, approval workflows, role-gated UI routes | `services/admin/` |
| 13 | **pricing** | List/sale/promo | list price, sale price, Stripe Coupons | `services/pricing/` |
| 14 | **invoice** | Tax-invoice | `tax_invoice_sequence`, daily batch, Jasper template | `services/invoice/` |

> Note: #14 is the 14th service but only 13 from the original DDD analysis. (Story 5.7 + 9.2b imply a small PricingService stub; tax-invoice is its own InvoiceService to keep the invoice concern separate from generic pricing.)

---

## 2. Decision tree — "Where does X live?"

### "I need to add a new product attribute"

→ **catalog** (Product + Variant aggregates)
→ Use JSONB `attributes` column (per FR-4); no schema migration needed
→ Add field-level documentation; admin UI maps to a control in `frontend/admin/`

### "I need to know if we have stock for variant X at warehouse Y"

→ **inventory** (`on_hand` view)
→ Query: `SELECT on_hand FROM inventory_on_hand WHERE variant_uuid = ? AND warehouse_id = ?`
→ `FOR UPDATE` only if you're about to reserve (per FR-9)

### "I need to start a checkout"

→ **checkout** (single-page-checkout API)
→ POST /bff/storefront/checkout with cart_id, address, Stripe client_secret
→ Returns checkoutId
→ Polling GET /bff/storefront/checkout/{id} for status

### "Stripe webhook arrives, what do I do?"

→ **payment** (webhook handler)
→ Verify signature (Stripe-Signature header)
→ Check `webhook_dedup` table on `event.id`
→ Process only if new; write payment aggregate + outbox `payment.captured`

### "A saga step retried and Stripe charged twice — how do I prevent?"

→ **payment** (idempotency key strategy per ADR-11)
→ Use `sha256(order_id + ":" + saga_step_name)` — stable across retries
→ Stripe returns same response on same key

### "Order's `state` changed to `PAID` — who knows about it?"

→ **order** owns the aggregate and writes `order.placed` to outbox
→ Other services (notification, fulfillment) subscribe via Kafka
→ Order service does NOT call other services directly

### "I need to update the catalog — but the event hasn't propagated to search yet"

→ Search index lag is acceptable per architecture (95% < 5s, alert at 30s)
→ For "needs-to-be-immediate" cases: don't use search; query Postgres directly via Service-to-Service HTTP

### "I need to issue a Vietnamese tax-invoice — but the credentials row is missing"

→ **invoice** service refuses to start (per Q5 closure / CI gate)
→ Error: `MissingTaxAuthorityCredentialException` at startup
→ Solution: Sprint Lead runs Story 9.2b ceremony with Vietnamese accountant
→ DON'T work around by signing invoices without credentials

### "I need a customer's order history for analytics"

→ **order** owns the order aggregate
→ Other services (analytics, recommendation) subscribe via Kafka
→ Do NOT query order's DB directly (per-service DB isolation)
→ Do NOT join across services (per ADR-03)

### "I need a user to be able to log in with email + password"

→ **customer** (auth sub-module)
→ util library has `CustomSecurityExpressionHandler` + `ICodeJwtGrantedAuthoritiesConvertor`
→ Per Story 5.4: email + password (Argon2id), MFA TOTP for staff

### "I need to add a new product image"

→ **catalog** (Product entity has `imageSet` field)
→ CDN URL stored in catalog, served via Next.js storefront
→ Image upload via Next.js API → catalog service → MinIO (S3) → catalog.imageSet

### "I need to track cart abandonment for marketing"

→ **cart** emits `cart.line.added` and `cart.expired`
→ Subscribe via Kafka
→ Do NOT query cart's DB directly

### "I need to refund a customer"

→ **returns** service issues refund
→ Critical: `SELECT SUM(amount_cents) FROM refund WHERE order_id = ? FOR UPDATE` per FR-43 (DI-07 fix)
→ NEVER use Stripe's API directly without this safety check
→ Refund event emits to outbox for downstream reconciliation

### "I need to send a confirmation email"

→ **notification** service subscribes to `order.placed` event
→ Templates per locale (FR-60)
→ Use util's SendGrid (already wired) or FCM for push
→ Do NOT send from the order service (separation of concerns)

### "I need to allow staff to edit products"

→ **admin** service handles staff operations
→ Writes to catalog via BFF (not direct DB)
→ Audit trail in audit_trail table (per FR-63)
→ Approval workflow for high-impact changes (per FR-64)

### "I need to ship to a customer"

→ **fulfillment** service creates shipment
→ Carrier adapter pattern (GHN, GHTK, Viettel Post) per FR-35
→ Tracking webhooks update shipment state
→ Estimated delivery computed using carrier SLA + warehouse distance (per FR-37)

### "I need a Vietnamese diacritic-tolerant search"

→ **search** service (per-locale ES index `catalog_vi_<env>`)
→ Per architecture: asciifolding + diacritic folding + metaphone phonetic
→ Catalog service publishes catalog events; search service consumes + projects to ES

### "I need to add a new carrier (e.g., DHL)"

→ **fulfillment** service: implement a new `DhlShipmentAdapter` implementing `ShipmentService` interface
→ Add config entry in `application.yml`
→ No schema change; no other service touched

### "I need to handle a customer data export request (PDPD)"

→ **customer** service (per FR-46)
→ `GET /bff/storefront/me/export` returns JSON/ZIP
→ Reads from `customer_data_registry` table to know what to include
→ Aggregates from other services via Kafka event log (not direct DB)

### "I need to handle a Vietnamese tax rate change"

→ **invoice** service (per FR-78)
→ Tax rate is per-merchant, stored in `vietnam_tax_authority_credential` table
→ Jasper template reads rate from DB at invoice generation time
→ Accountant updates rate + `reviewed_at` via Story 9.2b ceremony

### "I need to handle a stock adjustment (e.g., damaged goods)"

→ **inventory** service
→ Insert into `inventory_ledger` with `delta = -X, reason = 'adjustment'`
→ No other service touched
→ Outbox event `inventory.adjusted` emits for downstream

### "I need a customer to view their order status"

→ **order** service (timeline endpoint per FR-33)
→ BFF calls `GET /api/orders/{orderId}/timeline`
→ Order returns placed/paid/packed/shipped/delivered events
→ Use caching (30s) for high-traffic customers

### "I need a customer to track a shipment"

→ **fulfillment** service
→ `GET /api/shipments/{shipmentId}/tracking`
→ Tracking updates come via webhook from carrier (FR-36)
→ Use jittered retry on carrier API

---

## 3. Cross-service coordination patterns

### Pattern A: Event-driven (preferred)

```
Service A writes state change → outbox table → Modulith outbox bridge → Kafka topic
                                                                            ↓
                                                              Service B consumes
```

**Use when:** Almost always. Especially for cross-cutting events (`order.placed`, `payment.captured`, `inventory.reserved`).

**Examples:**
- Order → Notification (place confirmation)
- Inventory → Search (catalog projected to ES)
- Cart → Search (popularity)
- Returns → Payment (refund issued)

### Pattern B: Service-to-Service HTTP (rare)

```
Service A → HTTP call → Service B
```

**Use when:** B needs to read live data from A. The "read-side" of an aggregate is stable enough to be queried.

**Examples:**
- Checkout → Inventory (live stock check)
- BFF → Customer (auth verification)
- Admin → Catalog (write operations)

### Pattern C: Frontend (BFF) direct (NEVER)

```
Frontend → HTTP → Service (skipping BFF)
```

**Use when:** **NEVER** (per ADR-09). BFF is mandatory.
- ❌ Frontend → Catalog directly
- ✅ Frontend → BFF → Catalog

---

## 4. Database boundaries

| Service | Database | Tables | Other services can read? |
|---|---|---|---|
| catalog | `catalog` | products, variants, attributes, outbox, processed_event | **No** (own DB) |
| inventory | `inventory` | inventory_ledger, reservations, outbox, processed_event | **No** (own DB) |
| cart | `cart` | cart, cart_lines, outbox, processed_event | **No** (own DB) |
| checkout | `checkout` | checkouts, outbox, processed_event | **No** (own DB) |
| payment | `payment` | payments, webhook_dedup, outbox, processed_event | **No** (own DB) |
| order | `order` | orders, order_state_transition, outbox, processed_event | **No** (own DB) |
| fulfillment | `fulfillment` | shipments, tracking_events, outbox, processed_event | **No** (own DB) |
| returns | `returns` | returns, refunds, outbox, processed_event | **No** (own DB) |
| customer | `customer` | customers, addresses, users, audit_trail, customer_data_registry, outbox, processed_event | **No** (own DB) |
| search | `search_db` (or ES only) | outbox, processed_event | **No** (own DB; ES indices are read-side projections) |
| notification | `notification` | notification_log, templates, outbox, processed_event | **No** (own DB) |
| admin | `admin` | approval_workflows, audit_trail, outbox, processed_event | **No** (own DB) |
| pricing | `pricing` | price_table, coupons, outbox, processed_event | **No** (own DB) |
| invoice | `invoice` | tax_invoice_sequence, vietnam_tax_authority_credential, invoices, outbox, processed_event | **No** (own DB) |

**Rule:** No cross-service JOINs. Use aggregate IDs (Snowflake Long) to reference. Cross-service consistency is via events, not transactions.

---

## 5. Event catalog (the "data flow" of the system)

### Per-service events (Avro, strict backward+forward compat per ADR-15)

| Topic | Producer | Consumers | Key payload fields |
|---|---|---|---|
| `catalog.product.created` | catalog | search, notification, admin | productId, name, slug, createdAt |
| `catalog.product.updated` | catalog | search, notification, admin | productId, name, slug, updatedAt |
| `catalog.product.price_changed` | catalog | search, pricing | productId, oldPrice, newPrice, effectiveAt |
| `inventory.reserved` | inventory | search, cart, checkout | variantId, qty, reservationId, expiresAt |
| `inventory.released` | inventory | cart, search | variantId, qty, reservationId |
| `inventory.allocated` | inventory | order, fulfillment | variantId, qty, allocationId |
| `inventory.shipped` | inventory | order, customer | variantId, qty, shipmentId |
| `inventory.adjusted` | inventory | admin (audit), finance | variantId, delta, reason, adjustedBy |
| `cart.line.added` | cart | search (popularity), notification (abandonment later) | cartId, variantId, qty, customerId |
| `cart.expired` | cart | search (cleanup) | cartId |
| `cart.merged` | cart | search (re-attribute recs) | guestCartId, userId, customerId |
| `checkout.started` | checkout | notification, cart | checkoutId, customerId, totalCents |
| `checkout.completed` | checkout | cart, order, notification | checkoutId, orderId |
| `checkout.compensated` | checkout | cart, order | checkoutId, reason, originalSaga |
| `payment.captured` | payment | order (advance state), notification | orderId, paymentId, amount, currency |
| `payment.refunded` | payment | order, returns | orderId, refundId, amount, reason |
| `payment.failed` | payment | order, notification | orderId, paymentId, failureReason |
| `payment.disputed` | payment | order, customer (notify) | orderId, disputeId, reason |
| `order.placed` | order | notification, fulfillment, search (popularity) | orderId, customerId, totalCents |
| `order.amended` | order | fulfillment, notification | orderId, changeType, before, after |
| `shipment.dispatched` | fulfillment | order, notification, customer | shipmentId, orderId, carrier, trackingNumber |
| `shipment.delivered` | fulfillment | order, customer | shipmentId, orderId, deliveredAt |
| `shipment.exception` | fulfillment | order, customer (notify) | shipmentId, reason |
| `returns.created` | returns | order, payment (refund), notification | returnId, orderId, items, reason |
| `returns.exchanged` | returns | inventory, order, notification | returnId, exchangeItems |
| `refund.issued` | returns | order, customer (notify) | refundId, orderId, amount |
| `account.locked` | customer | notification, admin (audit) | userId, lockedAt, reason |
| `mfa.challenge` | customer | admin (audit) | userId, challengeType |
| `auth.login.success` | customer | admin (audit) | userId, ip, userAgent |
| `auth.login.failure` | customer | admin (audit) | userId, ip, userAgent, reason |
| `tax.invoice.issued` | invoice | customer (download), order, admin (audit) | invoiceId, orderId, merchantTaxCode |

> **Total events:** ~30 distinct types, each with backward+forward compat enforced in CI per ADR-15.

---

## 6. Decision tree — "When to use which event?"

### "Service A changed state. Should I emit an event?"

**Always emit** if:
- The state change is observable to other services
- Other services need to react (notification, search, audit, downstream effects)
- The state change has business meaning (not just internal config)

**Don't emit** if:
- The state change is internal-only (e.g., config update, cache invalidation)
- The change is ephemeral and will be overwritten before anyone consumes it
- The change is bulk (e.g., full table rebuild — use a snapshot event instead)

### "Service A needs data from Service B. Should I consume an event or make an HTTP call?"

**Consume event when:**
- B doesn't need the data in real-time (eventual consistency OK)
- B's data is read-side projection of A's state
- The read traffic is high (e.g., search reads from catalog CDC)

**Make HTTP call when:**
- B needs the data in real-time (synchronous read)
- The data is small and read-once
- The HTTP call has clear error handling (404, 503, etc.)

**Examples:**
- Search → Catalog CDC: consume event (eventual consistency OK)
- Checkout → Inventory live stock: HTTP call (real-time)
- BFF → Customer auth: HTTP call (real-time)

### "Should I use Saga or Transactional Outbox?"

**Use Saga when:**
- The operation spans multiple services
- Steps need to be reversible
- Network calls can fail (distributed transaction problem)

**Use Transactional Outbox when:**
- The operation is a single service's state change that needs to publish an event
- Event publishing must be atomic with state change
- This is the "atomic publish after commit" pattern, NOT a multi-step process

**Our case:** Saga is the CheckoutService; outbox is the per-event pattern. Both used together per ADR-01 + ADR-04.

---

## 7. Anti-patterns to avoid

### ❌ Anti-pattern 1: "I'll just JOIN across service DBs"

```sql
-- ❌ WRONG: This breaks per-service DB isolation
SELECT o.id, c.email FROM order.orders o
JOIN customer.customers c ON o.customer_uuid = c.uuid;
```

**Why wrong:** Service B is coupled to service A's schema. Service A can't change schema without breaking B.

**Instead:** Service A publishes `order.placed` event with customer_uuid. Service B subscribes, builds its own customer projection.

### ❌ Anti-pattern 2: "I'll call Service A from Service B to get live data"

```java
// ❌ WRONG: Synchronous cross-service read in a hot path
public OrderPage renderOrder(UUID orderId) {
    var order = orderRepo.findById(orderId);
    var customer = customerClient.getById(order.getCustomerUuid());  // HTTP call
    return new OrderPage(order, customer);
}
```

**Why wrong:** Adds latency to user-facing request. Customer service outage cascades.

**Instead:** Consume `customer.created` event in order service; cache customer projection. Use HTTP only when data is volatile and can't be cached.

### ❌ Anti-pattern 3: "I'll log a PAN because we need it for audit"

```java
// ❌ WRONG: PCI scope creep
log.info("Customer paid with card {}", request.getCardNumber());
```

**Why wrong:** R-15. PCI-DSS scope expands to every system that touches PAN.

**Instead:** Log `card_last4 + card_brand` (per ADR-23). Audit with token IDs, not card data.

### ❌ Anti-pattern 4: "I'll mutate another service's table directly for performance"

```java
// ❌ WRONG: CatalogService writes to SearchService's ES index
```

**Why wrong:** Bypasses CDC pipeline. Search service can't replay events to rebuild index.

**Instead:** Catalog publishes `catalog.product.changed` event; Search service consumes + projects to ES.

### ❌ Anti-pattern 5: "I'll use per-retry idempotency keys"

```java
// ❌ WRONG: Generates a new key per retry
String key = UUID.randomUUID().toString();
stripe.paymentIntents.capture(orderId, key);
```

**Why wrong:** R-03 / DI-02. Retry with new key = double-charge.

**Instead:** `sha256(order_id + ":" + saga_step_name)` — stable across retries (per ADR-11).

---

## 8. Quick reference: "Who owns X?"

| Data | Owner service |
|---|---|
| Product SKU | catalog |
| Product image URL | catalog (CDN URL stored in imageSet) |
| Stock level per variant | inventory (on_hand view) |
| Cart contents (current) | cart |
| Payment intent ID | payment |
| Order state | order |
| Shipping tracking number | fulfillment |
| Refund record | returns |
| User password hash | customer (auth sub-module) |
| Tax-invoice PDF | invoice |
| Search index entry | search (projection of catalog + inventory) |
| Audit log entry | depends — each service writes its own audit_trail rows; admin reads them |
| Loyalty points balance | customer (per FR-50) |

---

## 9. Cross-references

- **For each service's full AC:** `epics.md` §5.1..§5.16
- **For each event's Avro schema:** `architecture.md` §"Event-driven foundation" + `architecture-detail.md` §"Detail: ADR-04"
- **For each ADR:** `ADR-INDEX.md`
- **For each FR → story binding:** `epics.md` §"FR Coverage Map"
- **For risk → story mitigation:** `RISK-REGISTER.md`
- **For implementation patterns:** `architecture.md` §"Implementation Patterns"
