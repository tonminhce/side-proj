---
audience: all agents
project: side-project
date: 2026-07-06
how-to-use: visual reference. Mermaid source for all diagrams. Render at https://mermaid.live or in any Markdown viewer.
---

# Architecture Diagrams — side-project

> **Source:** `architecture.md` + `architecture-detail.md` (textual). This file is the **visual companion** — C4 + sequence diagrams.
> **Format:** Mermaid (text-based, version-controllable). Render in VS Code, GitHub, mermaid.live.
> **Where these go in canonical:** C4 System Context → `architecture.md` §"Project Structure". Sequence diagrams → corresponding ADR's Detail section.

---

## 1. C4 — System Context (Level 1)

The whole system + its external actors.

```mermaid
C4Context
    title System Context — side-project ecommerce platform

    Person(customer, "Shopper", "Browses catalog, places orders")
    Person(staff, "Operator", "Manages catalog, orders, returns")
    Person(architect, "Architect", "Reviews, deploys, maintains platform")
    Person(sre, "SRE", "Monitors, on-call, runs chaos drills")
    Person(pm, "PM", "Plans sprints, writes PRD/Epics")

    System(platform, "side-project platform", "Java 25 + Spring Boot 4 + Kafka + Modulith outbox saga")

    System_Ext(stripe, "Stripe", "Payment processing")
    System_Ext(vault, "HashiCorp Vault", "Secret management")
    System_Ext(carriers, "GHN/GHTK/Viettel Post/DHL", "Shipping carriers")
    System_Ext(sendgrid, "SendGrid", "Email delivery")
    System_Ext(fcm, "FCM", "Push notifications")
    System_Ext(tax_auth, "Vietnam Tax Authority", "Tax-invoice submission")
    System_Ext(github, "GitHub", "Source control + CI")

    Rel(customer, platform, "Browses, places orders, tracks shipment", "HTTPS")
    Rel(staff, platform, "Manages catalog, orders", "HTTPS")
    Rel(architect, platform, "Deploys, monitors", "HTTPS")
    Rel(sre, platform, "Operates, monitors", "HTTPS")

    Rel(platform, stripe, "Charge cards, capture payments", "HTTPS")
    Rel(platform, vault, "Reads secrets", "HTTPS")
    Rel(platform, carriers, "Creates shipments, fetches tracking", "HTTPS")
    Rel(platform, sendgrid, "Sends emails", "HTTPS")
    Rel(platform, fcm, "Sends push notifications", "HTTPS")
    Rel(platform, tax_auth, "Submits daily invoices", "HTTPS")
    Rel(platform, github, "Reads source, triggers CI", "HTTPS")
```

---

## 2. C4 — Container (Level 2)

Inside the system: services, BFFs, frontend, infrastructure.

```mermaid
C4Container
    title Container diagram — side-project

    Person(customer, "Shopper", "")
    Person(staff, "Operator", "")

    System_Boundary(platform, "side-project platform") {
        Container(frontend_store, "Storefront (Next.js)", "Next.js 15 App Router + Stripe Elements", "Customer-facing UI")
        Container(frontend_admin, "Admin (Next.js)", "Next.js 15 App Router", "Staff-facing UI")

        Container(bff_store, "Storefront BFF", "Java 25 + Spring Cloud Gateway", "Auth, rate-limit, aggregation")
        Container(bff_admin, "Admin BFF", "Java 25 + Spring Cloud Gateway", "Auth, RBAC, audit")

        Container(modulith, "Modulith (Spring Modulith)", "Java 25 + Spring Boot 4", "13 logical service modules + saga orchestrator")

        Container(kafka, "Apache Kafka 4", "KRaft mode, no Zookeeper", "Event streaming")
        Container(es, "Elasticsearch 8.x", "Per-locale indices", "Search + read-side")
        Container(postgres, "PostgreSQL 16+", "Per-service DBs", "System of record")
        Container(redis, "Redis 7", "Lua scripts", "Rate-limit + cache")
        Container(apicurio, "Apicurio Registry 2.6", "Avro schema", "Schema registry")
        Container(vault, "HashiCorp Vault", "Secret store", "Secrets + HMAC keys")
        Container(otel, "OTel Collector", "Traces + metrics + logs", "OTLP")
        Container(lgtm, "LGTM stack", "Loki + Grafana + Tempo", "Observability UI")
    }

    Rel(customer, frontend_store, "Browses, places orders", "HTTPS")
    Rel(staff, frontend_admin, "Manages catalog, orders", "HTTPS")
    Rel(frontend_store, bff_store, "API calls", "HTTPS")
    Rel(frontend_admin, bff_admin, "API calls", "HTTPS")
    Rel(bff_store, modulith, "Service calls", "mTLS + JWT")
    Rel(bff_admin, modulith, "Service calls", "mTLS + JWT")

    Rel(modulith, kafka, "Publish + consume events", "mTLS + SASL")
    Rel(modulith, es, "Read-side projection", "mTLS")
    Rel(modulith, postgres, "Per-service DB", "mTLS")
    Rel(modulith, redis, "Cache + rate-limit", "mTLS")
    Rel(modulith, apicurio, "Avro schema reg", "mTLS")
    Rel(modulith, vault, "Read secrets", "mTLS")

    Rel(modulith, otel, "OTLP export", "OTLP")
    Rel(otel, lgtm, "Store + visualize", "Internal")
```

---

## 3. C4 — Component (Level 3) — Saga Module (per ADR-01 + ADR-12)

Inside the Modulith, focusing on the saga component.

```mermaid
C4Component
    title Component diagram — Saga module (Checkout → Order flow)

    Container_Boundary(modulith, "Modulith") {
        Component(cart_api, "Cart API", "REST endpoint", "Manages cart state")
        Component(checkout_api, "Checkout API", "REST endpoint", "Initiates checkout")
        Component(saga_orch, "Saga Orchestrator", "State machine", "Drives checkout → order flow")
        Component(order_state, "Order State Machine", "10-state enum", "Tracks order lifecycle")
        Component(payment_client, "Payment Client", "Stripe API", "Charge / capture / refund")
        Component(inventory_client, "Inventory Client", "HTTP", "Reserve / release stock")
        Component(outbox, "Outbox + Bridge", "Modulith outbox bridge", "Atomic state + event publish")
        Component(event_consumer, "Saga Event Consumer", "@ApplicationModuleListener", "Intra-Modulith events")
    }

    ContainerDb(postgres, "Postgres", "Per-service DBs")
    Container(kafka, "Kafka", "Event log")

    Rel(checkout_api, saga_orch, "Initiates saga", "Java method call")
    Rel(saga_orch, order_state, "Transitions states", "Java method call")
    Rel(saga_orch, payment_client, "Authorizes payment", "Java method call")
    Rel(saga_orch, inventory_client, "Reserves stock", "Java method call")
    Rel(saga_orch, outbox, "Writes events atomically", "DB transaction")
    Rel(outbox, kafka, "Publishes events", "Bridge poll 500ms")
    Rel(event_consumer, saga_orch, "Triggers next step", "Async")
    Rel(saga_orch, postgres, "Reads/writes", "JDBC")
```

---

## 4. Sequence — Place an order (happy path)

```mermaid
sequenceDiagram
    autonumber
    actor Customer
    participant SF as Storefront<br/>(Next.js)
    participant BFF as Storefront BFF
    participant Chk as CheckoutService
    participant Saga as Saga<br/>Orchestrator
    participant Pay as PaymentService
    participant Stripe
    participant Inv as InventoryService
    participant DB as Postgres<br/>(per-service)
    participant OB as Outbox
    participant Bridge as Outbox<br/>Bridge
    participant K as Kafka

    Customer->>SF: POST /bff/storefront/checkout {cartId, address}
    SF->>BFF: forward with session cookie
    BFF->>Chk: checkout.start(cartId, address)
    Chk->>DB: BEGIN; INSERT checkout
    Chk->>OB: INSERT checkout.started
    Chk-->>BFF: {checkoutId, state: CREATED, stripeClientSecret}
    BFF-->>SF: 201 {checkoutId}
    SF-->>Customer: Show Stripe Elements iframe

    Customer->>Stripe: Enter card details (iframe)
    Stripe->>BFF: Webhook payment_intent.succeeded
    BFF->>Pay: handleWebhook(event.id, event.type)
    Pay->>DB: SELECT * FROM webhook_dedup WHERE event_id = ? (no row → new)
    Pay->>Stripe: Confirm PaymentIntent
    Pay->>DB: BEGIN; INSERT payment {stripe_id, amount}; UPDATE webhook_dedup
    Pay->>OB: INSERT payment.captured
    Pay-->>BFF: 200 OK
    BFF-->>Stripe: 200 OK

    Bridge->>OB: Poll unpublished rows every 500ms
    OB-->>Bridge: 1 row (payment.captured)
    Bridge->>K: publish to topic payment.captured (HMAC-signed)

    K->>Saga: payment.captured event
    Saga->>Saga: state: PAYMENT_PENDING → PAID
    Saga->>DB: BEGIN; INSERT order_state_transition; UPDATE orders.state
    Saga->>OB: INSERT order.placed
    Saga->>Bridge: poll + publish order.placed
    Bridge->>K: publish to topic orders.placed

    K->>Inv: orders.placed (reserves stock again — re-validates after capture)
    Inv->>DB: SELECT FOR UPDATE on variant row
    Inv->>DB: UPDATE inventory_ledger (release reservation)
    Inv-->>K: ack

    Customer->>SF: GET /bff/storefront/checkout/{id}
    SF->>BFF: forward
    BFF->>Chk: getStatus(checkoutId)
    Chk-->>BFF: {state: PAID, orderId: ...}
    BFF-->>SF: 200
    SF-->>Customer: Redirect to /orders/{orderId}
```

---

## 5. Sequence — Card testing defense (R-05 / ADR-24)

```mermaid
sequenceDiagram
    autonumber
    actor Attacker
    participant GW as Gateway<br/>(rate-limiter)
    participant Lua as Redis<br/>Lua script
    participant RS as Redis
    participant Stripe
    participant Pay as PaymentService
    participant OB as Outbox
    participant K as Kafka
    participant Alrt as Alertmanager

    Attacker->>GW: 1000 rapid POST /api/payment/charge
    Note over Attacker,GW: Different IPs (residential proxies),<br/>but same card BIN (e.g. 424242)

    GW->>Lua: EVAL rate_limit_check(key, now, ip, card_fingerprint, asn)
    Lua->>RS: ZREMRANGEBYSCORE rate_limit:ip:1.2.3.4 0 (now-60)
    Lua->>RS: ZCARD rate_limit:ip:1.2.3.4
    Note over Lua,RS: IP-keyed limit<br/>(100 req/min) — exceeded
    Lua-->>GW: DENY (429)

    GW->>Lua: EVAL rate_limit_check(key_bin, ...)
    Lua->>RS: ZCARD rate_limit:bin:424242
    Note over Lua,RS: BIN-keyed limit<br/>(1000 req/hour, cross-user)
    Lua-->>GW: DENY (429)

    GW-->>Attacker: 429 Too Many Requests

    Note over Pay,OB: All 1000 attempts blocked before reaching Stripe
    Note over K: Outbox emits rate_limited event (audit)

    RS->>Alrt: rate_limited_count{bin=424242} > threshold
    Alrt-->>SRE: page on-call: card-testing detected, BIN 424242
```

---

## 6. Sequence — Inventory oversell defense (R-02 / DI-01)

```mermaid
sequenceDiagram
    autonumber
    actor CustomerA
    actor CustomerB
    participant Chk as CheckoutService
    participant Inv as InventoryService
    participant DB as Postgres

    Note over DB: Variant X, on_hand = 1<br/>(2 concurrent checkouts)

    par Concurrent checkouts
        CustomerA->>Chk: POST /bff/storefront/checkout (qty 1)
        CustomerB->>Chk: POST /bff/storefront/checkout (qty 1)
    end

    Chk->>Inv: reserve(variant=X, qty=1, saga_step="payment")
    Inv->>DB: BEGIN; SELECT * FROM variants WHERE id=X FOR UPDATE
    Note over DB: Lock acquired (CustomerA's tx)
    Inv->>DB: SELECT on_hand FROM inventory_on_hand WHERE variant=X
    Note over DB: Returns 1 (sufficient)
    Inv->>DB: INSERT inventory_ledger (-1, reason='reservation')
    Inv->>DB: INSERT reservations (qty=1, expires_at=now()+15min)
    Inv->>DB: COMMIT
    Inv-->>Chk: 201 {reservationId}

    Chk->>Inv: reserve(variant=X, qty=1, saga_step="payment")
    Inv->>DB: BEGIN; SELECT * FROM variants WHERE id=X FOR UPDATE
    Note over DB: Blocks — CustomerA's lock still held
    Note over DB: Eventually CustomerA's tx commits
    Note over DB: CustomerB's tx now proceeds
    DB-->>Inv: on_hand = 1 - 1 = 0
    Inv-->>Chk: 409 InsufficientStockException

    Chk-->>CustomerB: 409 Out of stock
```

---

## 7. Sequence — Event injection defense (R-XX / AT-03 / ADR-20)

```mermaid
sequenceDiagram
    autonumber
    participant Attacker as Attacker<br/>(compromised pod)
    participant K as Kafka
    participant Cons as Consumer<br/>(e.g., Inventory)
    participant Hmac as HmacVerifier

    Attacker->>K: publish to catalog.product.created
    Note over Attacker,K: Spoofed event with bad signature

    K->>Cons: deliver event
    Cons->>Hmac: verify(event.signatures.hmac_sha256, payload)
    Hmac->>Hmac: lookup Vault secret for "catalog-service"
    Hmac->>Hmac: compute HMAC-SHA256(canonical_json(payload), secret)
    Note over Hmac: constant_time_compare
    Note over Hmac: signatures do NOT match
    Hmac-->>Cons: throw InvalidEventSignatureException

    Cons->>Cons: emit security alert: hmec_validation_failures_total
    Cons-->>K: ack (don't requeue)

    Note over Cons: Bad event rejected; legitimate events still flow
```

---

## 8. Sequence — Vietnamese tax-invoice (R-06 / LC-03 / ADR-26 + Q5 closure)

```mermaid
sequenceDiagram
    autonumber
    actor Customer
    participant Ord as OrderService
    participant Inv as InvoiceService
    participant DB as Postgres
    participant Vault as HashiCorp Vault
    participant Jasper as Jasper<br/>Engine
    participant QR as QRCodeUtil
    participant Batch as Daily<br/>Batch (Quartz)
    participant Auth as Vietnam<br/>Tax Authority

    Note over Inv,DB: Startup check: credentials row exists?
    Inv->>DB: SELECT * FROM vietnam_tax_authority_credential WHERE active_until > today
    DB-->>Inv: 1 row found
    Note over Inv: OK to start

    Customer->>Ord: Order paid (post-saga)
    Ord->>Inv: emitInvoice(orderId, amount)
    Inv->>DB: BEGIN; SELECT * FROM tax_invoice_sequence FOR UPDATE
    Inv->>DB: UPDATE tax_invoice_sequence SET current_value = current_value + 1
    Inv->>DB: INSERT INTO invoices (tax_invoice_id, amount, pdf_path, qr_data)
    Inv->>DB: COMMIT

    Inv->>Vault: read secret tax/<merchant_tax_code>/tax_authority_token
    Vault-->>Inv: <encrypted token>
    Inv->>Jasper: render(invoice_template, data)
    Jasper-->>Inv: PDF bytes
    Inv->>QR: generate(merchant_tax_code, tax_invoice_id, amount)
    QR-->>Inv: QR data
    Inv->>DB: UPDATE invoices SET pdf_path = ..., qr_data = ...
    Inv-->>Ord: {invoice_id, tax_invoice_id, pdf_url}

    Note over Batch: Daily 23:00 cron
    Batch->>DB: SELECT * FROM invoices WHERE issued_at::date = today
    Batch->>Auth: POST /api/v1/invoices (with tax_authority_token)
    Auth-->>Batch: 200 OK (invoice registered)
```

---

## 9. Sequence — Saga compensation (any failure mid-saga)

```mermaid
sequenceDiagram
    autonumber
    participant Chk as CheckoutService
    participant Saga as Saga<br/>Orchestrator
    participant Inv as InventoryService
    participant Pay as PaymentService
    participant Stripe
    participant OB as Outbox

    Note over Chk,OB: Saga state: STOCK_RESERVED → PAYMENT_PENDING → PAID
    Note over Chk,OB: Suppose PAID state succeeded, then ship step fails

    Chk->>Saga: state = PAID, advance to PACKED
    Note over Saga: Suppose PACKED state fails (warehouse API down)
    Saga->>Saga: state = PAID → FAILED
    Saga->>Inv: release(variant, qty, reason="saga_compensation")
    Inv->>OB: emit inventory.released
    Inv-->>Saga: 200 OK
    Saga->>Pay: refund(orderId, amount, reason="saga_compensation")
    Pay->>Stripe: paymentIntents.refund(...)
    Pay->>OB: emit payment.refunded
    Stripe-->>Pay: refund_id
    Saga->>Saga: state = FAILED → COMPENSATED
    Saga->>OB: emit order.compensated
    OB-->>Chk: end

    Note over Chk,OB: Customer sees: "Your order was cancelled and refunded"
```

---

## 10. Sequence — Crash recovery (saga stuck on Modulith restart)

```mermaid
sequenceDiagram
    autonumber
    participant Old as Modulith<br/>(pre-restart)
    participant K as Kafka
    participant New as Modulith<br/>(post-restart)
    participant DB as Postgres

    Note over Old: Saga state: PAYMENT_PENDING (in-flight)

    Old-->>K: state change events
    Note over Old: 💥 Modulith crashes

    K->>New: bootstrap, consume pending events
    New->>DB: SELECT * FROM orders WHERE state = 'PAYMENT_PENDING' AND updated_at < now() - interval '5 min'
    DB-->>New: 1 stuck order (uuid=X, saga_step=payment.authorize)

    New->>DB: SELECT * FROM order_state_transition WHERE order_id = X ORDER BY created_at DESC LIMIT 1
    DB-->>New: last transition: (PAYMENT_PENDING, saga_step=payment.authorize)

    New->>New: decide: re-trigger payment.authorize (idempotent on order_id, saga_step)
    New->>New: payment.authorize (idempotency key = sha256(X, payment.authorize))
    New->>Stripe: capture(PaymentIntent, idempotency_key)
    Stripe-->>New: success
    New->>DB: UPDATE orders SET state = 'PAID'
    New->>DB: INSERT order_state_transition (PAYMENT_PENDING → PAID, saga_step=payment.authorize.recovery)
    New->>DB: COMMIT

    Note over New: Order recovered, no manual intervention
```

---

## 11. State diagram — Order state machine (per ADR-12)

```mermaid
stateDiagram-v2
    [*] --> CREATED: cart.submit

    CREATED --> STOCK_RESERVED: inventory.reserve(OK)
    CREATED --> FAILED: inventory.reserve(InsufficientStock)

    STOCK_RESERVED --> PAYMENT_PENDING: payment.authorize
    STOCK_RESERVED --> CANCELLED: user.cancel
    STOCK_RESERVED --> COMPENSATED: saga.timeout

    PAYMENT_PENDING --> PAID: payment.captured
    PAYMENT_PENDING --> FAILED: payment.failed
    PAYMENT_PENDING --> CANCELLED: user.cancel
    PAYMENT_PENDING --> COMPENSATED: saga.timeout

    PAID --> PACKED: warehouse.markPacked
    PAID --> CANCELLED: user.cancel → refund + release
    PAID --> COMPENSATED: warehouse.unavailable

    PACKED --> SHIPPED: carrier.dispatched
    PACKED --> CANCELLED: user.cancel → refund + return-to-stock

    SHIPPED --> DELIVERED: carrier.delivered
    SHIPPED --> [*]

    DELIVERED --> [*]

    FAILED --> COMPENSATED: compensators run
    CANCELLED --> COMPENSATED: compensators run

    COMPENSATED --> [*]
```

---

## 12. Architecture decision flow (per ADR-01 — Modulith outbox saga)

```mermaid
flowchart TD
    A[checkout.start] -->|MODULITH OUTBOX| B[inventory.reserve]
    B -->|FR-9 FOR UPDATE| C{stock >= qty?}
    C -->|Yes| D[emit inventory.reserved<br/>+ outbox]
    C -->|No| E[throw InsufficientStockException<br/>→ checkout.compensated]
    D -->|saga state: STOCK_RESERVED| F[payment.authorize<br/>FR-25 idempotency key]
    F -->|Stripe Elements| G{captured?}
    G -->|Yes| H[emit payment.captured<br/>+ outbox]
    G -->|No| I[emit payment.failed<br/>→ saga.compensated]
    H -->|saga state: PAID| J[order.placed<br/>+ emit outbox]

    style A fill:#e1f5e1
    style D fill:#e1f5e1
    style H fill:#e1f5e1
    style J fill:#e1f5e1
    style E fill:#ffe1e1
    style I fill:#ffe1e1
```

Legend: 🟢 = happy path / 🟥 = failure / compensation path

---

## 13. Data flow — Outbox + Kafka + consumer (per ADR-04 + ADR-14)

```mermaid
flowchart LR
    subgraph Service A
        A1[business logic] --> A2[begin transaction]
        A2 --> A3[update business state]
        A3 --> A4[insert outbox row]
        A4 --> A5[commit transaction]
    end

    A5 -->|atomic| DB1[(Service A's DB<br/>outbox table)]
    DB1 -.->|poll every 500ms| Bridge[Modulith outbox bridge]

    subgraph Kafka
        K[(Kafka topic)]
    end

    Bridge -->|publish w/ HMAC| K

    K -->|subscribe| B1[Service B consumer<br/>@ApplicationModuleListener or @KafkaListener]
    B1 --> B2[verify HMAC signature<br/>FR-82 / ADR-20]
    B2 -->|valid| B3[check processed_event<br/>FR-26 dedup]
    B3 -->|new event| B4[process business logic]
    B3 -->|duplicate| B5[ack and skip]
    B4 --> B6[commit + insert processed_event]
```

---

## 14. Per-locale ES index pattern (per architecture "Elasticsearch read-side")

```mermaid
flowchart TB
    subgraph Postgres
        P1[catalog.products]
    end

    subgraph Modulith outbox bridge
        OB[bridge]
    end

    subgraph ES
        E1[catalog_vi_prod<br/>analyzer: vi_text]
        E2[catalog_en_prod<br/>analyzer: en]
        E3[catalog_search<br/>(read alias)]
    end

    P1 -->|CDC| OB
    OB -->|write| E1
    OB -->|write| E2
    E1 -->|alias| E3
    E2 -->|alias| E3

    Client[Search query] -->|GET catalog_search/_search| E3
    E3 -->|results| Client
```

---

## 15. How to render these

### Local (VS Code)

```bash
# Install Markdown Preview Mermaid Support extension
# Or use Mermaid Preview in VS Code
```

### Online

- https://mermaid.live (paste and render)
- https://mermaid.ink (URL-based image generation)

### In Markdown viewers

GitHub, GitLab, Bitbucket render Mermaid natively in Markdown files.

### As images for docs

```bash
# Install mmdc (Mermaid CLI)
npm install -g @mermaid-js/mermaid-cli

# Render to PNG
mmdc -i ARCHITECTURE-DIAGRAMS.md -o diagrams/
```

---

## 16. Where each diagram goes in canonical docs

| Diagram | Goes in |
|---|---|
| §1 C4 System Context | `architecture.md` §"Project Structure" intro |
| §2 C4 Container | `architecture.md` §"Project Structure" |
| §3 C4 Component (Saga) | `architecture-detail.md` §"Detail: ADR-01" |
| §4 Sequence (Place order) | `architecture-detail.md` §"Detail: ADR-04" + Epic 4 docs |
| §5 Sequence (Card testing) | `architecture-detail.md` §"Detail: ADR-24" |
| §6 Sequence (Oversell) | `architecture-detail.md` §"Saga state storage" |
| §7 Sequence (Event injection) | `architecture-detail.md` §"Detail: ADR-20" |
| §8 Sequence (Tax-invoice) | `architecture-detail.md` §"Detail: ADR-26" |
| §9 Sequence (Compensation) | `architecture-detail.md` §"Detail: ADR-12" |
| §10 Sequence (Crash recovery) | `architecture-detail.md` §"Detail: ADR-12" + §"Detail: ADR-01" |
| §11 State machine | `architecture-detail.md` §"Detail: ADR-12" |
| §12 Architecture flow | `architecture.md` §"Detail: ADR-01" |
| §13 Data flow (Outbox) | `architecture-detail.md` §"Detail: ADR-04" |
| §14 ES per-locale | `architecture-detail.md` §"Detail: ADR-04" → "Elasticsearch read-side" |

---

## 17. Cross-references

- **Architecture (textual):** `architecture.md`
- **Per-ADR detail:** `architecture-detail.md`
- **Domain boundaries:** `PROBLEM-DOMAINS-MAP.md`
- **Event catalog (full list):** `PROBLEM-DOMAINS-MAP.md` §5
- **Risk binding:** `RISK-REGISTER.md`
- **Sprint work (implementing these flows):** `SPRINT-1-DEV-HANDBOOK.md` + `EPIC-1-STORIES-QUICKREF.md`
- **Story automator:** `STORY-AUTOMATOR-CHEATSHEET.md`
- **Onboarding:** `AGENT-ONBOARDING.md`
- **Glossary:** `GLOSSARY.md`
