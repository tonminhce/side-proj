# Domain Research — Ecommerce Microservices

> **Status:** Draft v1 — research input for BMad planning artifacts
> **Audience:** Architects, product managers, and engineers building the reference implementation
> **Scope:** Core ecommerce domain terminology, microservice boundaries, standard event schemas, invariants, and anti-patterns
> **Date:** 2026-07-06

---

## 1. Executive Summary

Ecommerce is a **multi-aggregate domain** with strong consistency requirements inside each aggregate (cart, order, payment, inventory) and deliberate eventual consistency across aggregates. The reference implementation should treat Catalog/Cart/Checkout/Order/Payment/Inventory/Fulfillment/Returns/Customer/Search/Notification as **bounded contexts**, each owning its own data and emitting integration events on a Kafka backbone. Industry-standard event names (`orders.placed`, `payment.captured`, `inventory.reserved`, `shipment.dispatched`) and Stripe-style lifecycle vocabularies (`requires_capture` → `succeeded`) keep the platform legible to anyone who has worked with Shopify, Stripe, or Amazon-style systems. Non-negotiable invariants are: stock cannot go negative, prices are immutable once an Order is placed, refunds must reference the originating payment and authorization, and every monetary amount is stored in **minor units (cents) — never floats**.

---

## 2. Core Domain Terminology

Ecommerce has overlapping vocabulary that produces expensive bugs when confused. Precise definitions below are the ones a reference implementation must hold the line on.

### 2.1 Product, Variant, SKU

- **Product** — the abstract sellable concept. Owns shared attributes (title, description, brand, category, media). A "T-Shirt."
- **Variant** — a purchasable form of a product defined by a unique combination of option values (size + color). "T-Shirt / Black / Large." Each variant carries its own price, weight, and inventory level.
- **SKU (Stock Keeping Unit)** — a **merchant-defined, human-readable** identifier on a variant used for inventory, warehousing, and reporting. Distinct from a platform-internal product ID, which is opaque and machine-generated.

**Hierarchy:** Product → (1..N) Variants → (1..1) SKU per variant. In Shopify's data model this mapping is explicit: a product contains variants, and each variant carries a `sku` field plus its own price and stock count ([Shopify Product/Variant docs](https://shopify.dev/docs/api/admin-graphql/latest/objects/Product)).

**Reference implication:** the Catalog Service owns products and variants; the Inventory Service is keyed by SKU. They are not the same aggregate — keep them in separate services or you blur bounded contexts.

### 2.2 Cart vs. Basket

- **Cart** (US) and **Basket** (UK/EU) are synonyms. They denote the *pre-checkout* line-item set owned by a single customer (identified by anonymous cookie or signed-in user ID). A cart has no monetary commitment.
- Industry implementations (Shopify, Stripe Checkout, eShopOnContainers) treat the cart as an **eventually consistent** persistent projection, not the source of pricing. Prices on cart line items are snapshots; authoritative pricing is recomputed at checkout ([Microsoft eShopOnContainers — Basket API](https://learn.microsoft.com/en-us/dotnet/architecture/microservices/multi-container-microservice-net-applications/subscribe-events)).

### 2.3 Checkout

The transient, multi-step process of converting a cart into a payment authorization and (typically) an Order. Checkout is the canonical home of the **saga pattern**: price recalculation, inventory reservation, tax computation, payment intent creation, and confirmation all happen here.

### 2.4 Payment Intent vs. Payment vs. Charge

- **Payment Intent** — a *stateful* object that represents the intent to collect funds and guides the customer through authentication. Stripe explicitly recommends "exactly one PaymentIntent for each order or customer session" ([Stripe PaymentIntents API](https://docs.stripe.com/api/payment_intents)).
- **Payment** — generic term; in Stripe's modern API it is usually realized as a Payment Intent + one or more Charges.
- **Charge** — a single *attempt* to move money. A successful Payment Intent produces "at most one successful charge." Charges have a separate *authorize-then-capture* lifecycle (`charge.pending` → `charge.captured` / `charge.expired`) that is essential for fraud review, pre-orders, and marketplace flows ([Stripe Charges API](https://docs.stripe.com/api/charges)).

**Reference implication:** treat `PaymentIntent` semantics as the model for the Payment Service's internal aggregate. Use the verbs *authorize*, *capture*, *void*, and *refund* consistently across events.

### 2.5 Order vs. Fulfillment

- **Order** — the customer's contractual commitment to purchase, referencing line items, prices at the time of order, shipping address, and a financial status (pending / authorized / paid / partially_refunded / refunded). In Shopify, an Order also tracks `fulfillment_status` (unfulfilled / partial / fulfilled / restocked) ([Shopify Order API](https://shopify.dev/docs/api/admin-rest/2024-01/resources/order)).
- **Fulfillment (Shipment)** — the act of packing and handing off one or more order line items to a carrier. One Order can have many Fulfillments (split shipments, back-orders).

**Reference implication:** Order Orchestrator owns the Order aggregate; Fulfillment Service owns Shipment aggregates. Splitting them keeps "shipment dispatched" an independent event from "order placed."

### 2.6 Inventory: Reservation vs. Allocation

- **Reservation** — a *soft hold* against available stock with a TTL (typically 10–30 minutes in retail). Reservation does not change on-hand quantity; it reduces *sellable* quantity. Released on cart abandonment or payment failure.
- **Allocation** — a *hard assignment* of stock to a specific order/fulfillment. Incremented from reservation, decrementing on-hand stock. Released back to on-hand only on a return or cancellation.

**Why both:** reservations prevent oversell during browsing; allocations prevent oversell during physical pick & pack. A common anti-pattern is collapsing both into one "stock" counter, which makes "available to promise" calculations impossible.

### 2.7 Return Merchandise Authorization (RMA)

An RMA is a merchant-issued authorization to receive returned goods for refund, replacement, or repair. It is the **gatekeeping step** in the reverse-logistics cycle: customer requests return → merchant issues RMA → customer ships → merchant inspects → restock (or dispose) and refund. Refunds precede, accompany, or follow the RMA depending on policy ([Wikipedia — RMA](https://en.wikipedia.org/wiki/Return_merchandise_authorization)).

**Reference implication:** Returns Service is its own bounded context with its own state machine: `REQUESTED → APPROVED → IN_TRANSIT → RECEIVED → INSPECTED → RESTOCKED|REFUNDED|REJECTED`. The refund step *references* the original payment (idempotent, original-amount-only-or-less) and *references* the original order line item.

---

## 3. Standard Microservice Boundaries

The reference architecture in `local-docs/01-architecture-overview.md` already aligns with industry consensus. The table below makes each service's **ownership** and **emitted events** explicit — the next planning step is encoding these into Kafka topic names.

| Service | Owns (aggregate root) | Database | Emits | Subscribes to |
|---|---|---|---|---|
| **Catalog** | Product, Variant, Category, Brand | Postgres | `product.created`, `product.updated`, `product.price_changed`, `product.deactivated` | — |
| **Inventory** | StockItem (sku-keyed), Reservation, Allocation | Postgres | `inventory.reserved`, `inventory.released`, `inventory.allocated`, `inventory.adjusted` | `orders.placed`, `orders.cancelled`, `returns.received` |
| **Cart** | Cart, LineItem | Redis + Postgres | `cart.checked_out`, `cart.abandoned` | `product.price_changed` (recompute snapshots) |
| **Checkout / Order Orchestrator** | CheckoutSession, SagaState | Postgres | `checkout.started`, `checkout.completed`, `checkout.failed`, `orders.placed`, `orders.cancelled` | — (issues commands) |
| **Payment** | PaymentIntent, Charge, Refund | Postgres | `payment.authorized`, `payment.captured`, `payment.failed`, `payment.refunded` | — |
| **Order** | Order, OrderLine | Postgres | `order.status_changed`, `order.fulfilled`, `order.closed` | `orders.placed` (own aggregate finalization) |
| **Fulfillment / Delivery** | Shipment, TrackingEvent | Postgres | `shipment.dispatched`, `shipment.delivered`, `shipment.returned` | `orders.placed` |
| **Returns** | RMA | Postgres | `rma.requested`, `rma.approved`, `rma.received`, `rma.refunded` | `order.delivered` |
| **Customer** | Customer, Address | Postgres | `customer.created`, `address.updated` | — |
| **Search** | Read-model only | Elasticsearch | — | CDC of `products`, `product.price_changed`, `ProductRated` |
| **Recommendation** | Read-model only | Elasticsearch / Postgres | — | `orders.placed`, `product.viewed` |
| **Notification** | none (consumer only) | Postgres (templates) | `notification.sent` | `orders.placed`, `payment.captured`, `shipment.dispatched`, `rma.refunded` |
| **Promotion / Tax** | Promotion, TaxRule | Postgres | `promotion.applied`, `tax.calculated` | — (command-reply services) |

Two services, **Search** and **Recommendation**, are read projections — they consume CDC streams and the integration-event topic; they never own a transactional aggregate. Notification is a pure side-effect service.

---

## 4. Industry-Standard Event Schemas

Event names in this domain converge on a **past-tense verb pattern** keyed by aggregate (`orders.placed`, not `place_order`). The schemas below are extracted from publicly documented APIs and reflect what an experienced integrator expects to see.

### 4.1 Shopify Order (canonical ecommerce aggregate)

```json
{
  "id": "450789469",
  "customer": { "id": "207119551", "email": "bob.norman@hostmail.com" },
  "line_items": [{ "variant_id": "447654529", "quantity": 1, "sku": "TS-BLK-L", "price": "1990" }],
  "billing_address":  { "...": "..." },
  "shipping_address": { "...": "..." },
  "tax_lines":      [{ "title": "State Tax", "rate": 0.06, "price": "10.20" }],
  "discount_codes": [{ "code": "FAKE30", "amount": "9.00", "type": "percentage" }],
  "financial_status":   "paid",
  "fulfillment_status": "fulfilled",
  "created_at": "2008-01-10T11:00:00-05:00"
}
```

> Money fields are string-encoded **minor units in the store's currency** (`"1990"` = $19.90, never `"19.90"`) ([Shopify Order REST docs](https://shopify.dev/docs/api/admin-rest/2024-01/resources/order)).

### 4.2 Stripe-style Payment lifecycle (event taxonomy)

Stripe publishes `resource.event` over its webhooks; we recommend the same shape over Kafka ([Stripe Events](https://docs.stripe.com/api/events/types)).

| Event | Meaning |
|---|---|
| `payment_intent.created` | PI created |
| `payment_intent.requires_action` | 3DS / SCA step |
| `payment_intent.processing` | Provider is processing |
| `payment_intent.amount_capturable_updated` | Authorized, awaiting capture |
| `payment_intent.succeeded` | Captured (single-shot flow) |
| `payment_intent.payment_failed` | Permanent failure |
| `payment_intent.canceled` | Voided |
| `charge.refunded` | Full or partial refund applied |
| `refund.created`, `refund.updated` | Refund lifecycle (matches an original `charge`) |

### 4.3 Core integration-event topics for our Kafka cluster

```
orders.lifecycle         orders.placed | orders.cancelled | orders.fulfilled
payment.lifecycle        payment.authorized | payment.captured | payment.refunded
inventory.lifecycle      inventory.reserved | inventory.allocated | inventory.released
shipment.lifecycle       shipment.dispatched | shipment.delivered | shipment.returned
returns.lifecycle        rma.requested | rma.approved | rma.received | rma.refunded
catalog.lifecycle        product.created | product.updated | product.price_changed
customer.lifecycle       customer.created | address.updated | customer.deleted
```

These are the names the reference implementation should commit to. Renaming topics mid-flight is a breaking change.

---

## 5. Critical Domain Invariants

These are the rules that, if violated, cause real money loss or customer trust damage. They should be enforced in the service that owns the aggregate, not by convention.

1. **Stock cannot go negative.** Inventory Service must use optimistic locking (`SELECT … FOR UPDATE` or version columns). The Saga must compensate on partial failure; oversell is unrecoverable.
2. **Prices are immutable once an Order is placed.** A `product.price_changed` event updates the *catalog*, never an existing `order_line.price`. Cart snapshots may need reconciliation; Order lines do not.
3. **Refunds must reference the original payment.** A `refund.created` event must include `original_payment_id`, `original_charge_id`, and `original_order_id`. Refunds cannot exceed the captured amount, and partial refunds accumulate.
4. **Monetary amounts are stored as integers in minor units (cents).** Never `float`, never `double`, never `BigDecimal` with scale derived at runtime. This eliminates a whole class of rounding bugs ([Shopify Order REST fields](https://shopify.dev/docs/api/admin-rest/2024-01/resources/order)).
5. **Currencies are explicit.** Every amount carries an ISO 4217 `currency` code. Cross-currency operations are explicit FX conversions, not implicit.
6. **All commands and replies are idempotent.** Every saga step is keyed by an `idempotency_key`; consumers must dedupe on `event_id` ([Microsoft — Idempotent message processing](https://learn.microsoft.com/en-us/dotnet/architecture/microservices/multi-container-microservice-net-applications/subscribe-events)).
7. **Cart snapshots can be stale, Order lines cannot.** The cart is a UI optimization; the Order is the contract.
8. **Returns reference original order lines and restock decisions are reversible.** An RMA that is approved and then rejected must restore inventory state exactly.

---

## 6. Anti-Patterns to Avoid

A *reference* implementation earns that label by showing what **not** to do. The local architecture doc explicitly calls these out (saga, outbox, no sync-in-critical-path); the literature is unanimous:

- **Distributed transactions across services (2PC, XA).** Not supported by Kafka; coupled to broker availability; violates the CAP trade-off ([Chris Richardson — Saga pattern](https://microservices.io/patterns/data/saga.html)). *Instead:* saga with compensations.
- **Dual writes (DB + Kafka without Outbox).** The textbook dual-write problem: "Sending inside a transaction is unreliable; sending after commit is unreliable" ([microservices.io — Transactional Outbox](https://microservices.io/patterns/data/transactional-outbox.html)). *Instead:* the **Transactional Outbox** pattern — write the event to an `outbox` table inside the same DB transaction; a relay (Debezium CDC or a polling publisher) forwards to Kafka.
- **Synchronous call chains in the critical path.** `Cart → Catalog → Inventory → Payment → Order` over HTTP creates the synchronous chains the saga pattern is specifically designed to avoid. *Instead:* the orchestrator publishes a command; participants reply via event topics.
- **Database sharing across services.** Any cross-service `SELECT` re-couples the services and breaks independent deployability. *Instead:* database-per-service, cross-service reads via APIs or materialized projections.
- **Choreography for complex multi-step flows.** The local doc already flags this: orchestration is correct for checkout because the state machine is explicit, compensations are ordered, and timeouts are centralized ([Microsoft — Implementing atomicity](https://learn.microsoft.com/en-us/dotnet/architecture/microservices/multi-container-microservice-net-applications/subscribe-events)). Use choreography for post-commit side effects only (notification, recommendation).
- **Non-idempotent consumers.** Without dedupe keys, at-least-once delivery causes double-charges and double-fulfillments. *Instead:* persist `processed_event_ids` per consumer group.
- **Float-based money arithmetic.** Even rounding to 2 decimals can produce sub-cent drift over many transactions. *Instead:* `long` cents or `BigDecimal` with explicit scale.
- **Snapshotting live prices into Orders on read.** Re-reading the catalog at order time may pick up a flash-sale price the customer didn't see. *Instead:* cart already pinned snapshot prices; Order just copies them.

---

## 7. Open Questions for the Next Planning Round

1. Is `Cart` a separate service or part of `Checkout`? A separate service lets the BFF cache it in Redis hot; folding it into Checkout simplifies the data model. **Recommendation:** keep Cart separate so abandons can drive the Recommendation Service independently.
2. Should `Order` and `Checkout/Orchestrator` be one service or two? They share a database and a state machine in most implementations; the local doc treats them as one ("Order Orchestrator (Saga)"). **Recommendation:** one service for the saga, one bounded context named *Order*; separating creates a chatty boundary for no semantic gain.
3. Tax and Promotion are read-mostly. Are they command-reply services (synchronous within the saga) or pre-computed caches? **Recommendation:** caches refreshed via CDC, applied at checkout via a request-reply pattern with a short timeout.
4. Is dual-currency support in scope? If yes, `Money` should be a value object (`amount`, `currency`) everywhere, not `long`.

---

## 8. Sources

- [Shopify — Admin REST Order API](https://shopify.dev/docs/api/admin-rest/2024-01/resources/order)
- [Shopify — Admin GraphQL Product / ProductVariant](https://shopify.dev/docs/api/admin-graphql/latest/objects/Product)
- [Stripe — PaymentIntents API](https://docs.stripe.com/api/payment_intents)
- [Stripe — Charges API](https://docs.stripe.com/api/charges)
- [Stripe — Event Types](https://docs.stripe.com/api/events/types)
- [Wikipedia — Return merchandise authorization](https://en.wikipedia.org/wiki/Return_merchandise_authorization)
- [Chris Richardson — Saga pattern (microservices.io)](https://microservices.io/patterns/data/saga.html)
- [Chris Richardson — Transactional Outbox pattern](https://microservices.io/patterns/data/transactional-outbox.html)
- [Microsoft .NET Docs — Subscribing to events / eShopOnContainers](https://learn.microsoft.com/en-us/dotnet/architecture/microservices/multi-container-microservice-net-applications/subscribe-events)
- [Project local-docs — `01-architecture-overview.md`, `05-saga-and-checkout-flow.md`](/home/tonminh/Documents/GitHub/side-project/local-docs/)

