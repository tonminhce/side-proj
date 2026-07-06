---
audience: future-agents
project: side-project
date: 2026-07-06
canonical-source: _bmad-output/planning-artifacts/architecture.md
how-to-use: 1-page quick reference. Read in 60 seconds. For deep-dive, see ADR-INDEX.md or architecture-detail.md.
---

# Architecture Quick Reference — side-project

> **Source of truth:** `architecture.md` (1,176 lines) + `architecture-detail.md` (253 lines). This file is a 1-page digest.

---

## 1. Stack (binding)

| Layer | Technology | Version |
|---|---|---|
| Language | Java | 25 (LTS) |
| Framework | Spring Boot | 4.0.0 (GA 10 Jun 2026) |
| Cloud | Spring Cloud | 2025.1 "Oakwood" |
| Messaging | Apache Kafka | 4 (KRaft mode) |
| CDC / Outbox | **Spring Modulith outbox** (NOT Debezium) | — |
| Schema registry | Apicurio | 2.6 Avro |
| Search | Elasticsearch | 8.x (per-locale indices) |
| Cache / rate-limit | Redis | 7 (Lua scripts) |
| Database | PostgreSQL | 16+ |
| Observability | OTel + Prometheus + Grafana + Loki + Tempo | — |
| Frontend | Next.js | 15 (App Router, TypeScript strict) |
| Payment | Stripe Elements (iframe-only) | — |
| Auth | JWT (util's `CustomSecurityExpressionHandler`) | — |
| Reporting | Jasper | 7.0.3 (Vietnamese fonts in util) |
| Hashing | HMAC-SHA-256 (event signing) | — |

**Vietnamese-first:** tax-invoice (Circular 78/2021/TT-BBC), address hierarchy (Province/District/Commune), diacritic search, locale formatting.

---

## 2. 13 services + Auth (folded into Customer)

```
services/
├── catalog/         ← products, variants, Avro events
├── inventory/       ← per-warehouse ledger, FOR UPDATE reservation
├── cart/            ← anonymous cart + merge on login
├── checkout/        ← single-page checkout + saga orchestrator
├── payment/         ← Stripe + idempotency
├── order/           ← append-only log + price-snapshot
├── fulfillment/     ← carrier-agnostic shipment
├── returns/         ← RMA workflow
├── customer/        ← Customer aggregate + auth sub-module  ← Auth lives here
├── search/          ← per-locale ES + Vietnamese analyzer
├── notification/    ← SendGrid + FCM
├── admin/           ← role-gated Next.js routes
├── pricing/         ← PricingService stub (5.7)
└── invoice/         ← VN tax-invoice (Sprint 9)
```

**Each service:** own DB + outbox table + processed_event table + own Avro events.

---

## 3. Saga pattern (Q1 binding)

**Architecture:** Spring Modulith outbox. Single deployment. Saga = intra-process state machine on the `order` aggregate.

**Order state machine (10 states):**
```
(none) → CREATED → STOCK_RESERVED → PAYMENT_PENDING → PAID
                                                    ↓
                       PACKED → SHIPPED → DELIVERED
                       
       (any non-terminal) → CANCELLED
       PAYMENT_PENDING/PAID → FAILED → COMPENSATED
```

**State stored on `order` aggregate** + `order_state_transition` log table. **Version** for optimistic concurrency.

**Crash recovery:** on Modulith restart, find orders in non-terminal state for >5 min → re-derive saga step from last transition → re-trigger idempotent side-effect.

**Compensation paths:** `STOCK_RESERVED → CANCELLED` releases inventory; `PAID → CANCELLED` calls Stripe refund + releases inventory; `PAYMENT_PENDING → CANCELLED` cancels Stripe PaymentIntent.

---

## 4. Event-driven foundation

```
[Service] → [outbox table] → [Modulith outbox bridge] → [Kafka topic] → [Consumer]
                                  ↑ every 500ms                       ↑
                                  batch=100                     HMAC verify
```

- **Avro** schemas, strict backward+forward compat (CI gate)
- **Outbox table** per service; writes to outbox + business state in same transaction
- **Modulith outbox bridge** polls + publishes (500ms, batch=100, exp-backoff on Kafka failure, sweeper drops published rows > 7 days)
- **HMAC event signing** (ADR-20): HS256, base64url, per-service key in Vault at `secret/events/hmac/<service>`, quarterly rotation with 7-day overlap, producer fails loud if Vault unreachable

---

## 5. Idempotency contract

| Operation | Key |
|---|---|
| Saga step retry | `(order_id, saga_step_name)` — stable across retries |
| Stripe webhook | `event.id` (stored in `webhook_dedup`) |
| Event consumer | `event.id` (stored in `processed_event`) |
| `cart.merge` (login) | `(guest_cart_id, user_id)` |

**Why stable keys:** Kafka redelivers on consumer rebalance, Stripe retries 3 days, all retries must hit the same Stripe response. Per-retry keys = double-charge risk.

---

## 6. Per-locale search (FR-52 / ADR-25)

ES index naming: `catalog_<locale>_<env>` (e.g., `catalog_vi_prod`, `catalog_en_staging`).

**Vietnamese analyzer config:**
```json
{
  "tokenizer": "standard",
  "filter": ["lowercase", "asciifolding", "vi_diacritic_fold", "metaphone"]
}
```

**Index bootstrap:** SearchService creates per-locale indices at startup if absent. Idempotent.

---

## 7. VN tax-invoice (ADR-26 + Q5 closure)

```
Q5 closure requires `vietnam_tax_authority_credential` row before InvoiceService can issue:
```

| Column | Type | Notes |
|---|---|---|
| `merchant_tax_code` | VARCHAR(14) UNIQUE | MST (Mã Số Thuế) |
| `serial_prefix` | VARCHAR(2) | "AA" / "AB" |
| `serial_number_start` | BIGINT | Allocated by tax authority |
| `serial_number_end` | BIGINT | — |
| `tax_authority_api` | VARCHAR(255) | Daily batch endpoint |
| `tax_authority_token` | TEXT | Encrypted, Vault path `secret/tax/<MST>` |
| `reviewed_by` | VARCHAR(36) | Accountant sign-off |
| `reviewed_at` | TIMESTAMP | Within last 30 days for production |

**CI gate:** InvoiceService fails-fast at startup if no active credential row. Dev environment has a stub.

**Accountant input template** (the structured form they fill): see `architecture-detail.md` §"Detail: ADR-26" → "Accountant input template".

---

## 8. 11-Sprint plan

| Sprint | Domain | Notes |
|---|---|---|
| 0 | Foundations | R-01 fix (util parent pom), monorepo, docker-compose, CI, R-22 |
| 1 | Catalog + Inventory | DI-01 fix (FOR UPDATE reservation) |
| 2 | Cart + Checkout | Saga + Q1 binding |
| 3 | Payment + Idempotency | R-03, R-05, R-15 mitigations |
| 4 | Order + Fulfillment | Append-only log, carrier adapters |
| 5 | Customer + Auth | AT-02 (lockout), LC-01 (PDPD), Pricing stub |
| 6 | Search | R-07 (VN diacritic) |
| 7 | Returns | DI-07 (cumulative refund safety) |
| 8 | Admin UI | Role-gated routes |
| 9 | Notification + VN Tax | LC-03 + Q5 closure (Story 9.2b) |
| 10 | Observability + Chaos | Validates R-01..R-15 mitigations end-to-end |

---

## 9. Risk-binding summary (R-01..R-15)

| Critical risks (must mitigate) | Bound by |
|---|---|
| R-01 util parent pom | Sprint 0 fix (Story 0.1) |
| R-02 inventory oversell | Sprint 1 (Story 1.6) |
| R-03 payment double-capture | Sprint 3 (Stories 3.1, 3.2) |
| R-04 Debezium outbox | Mitigated by ADR-01 + ADR-14 (no Debezium in v1) |
| R-05 card-testing | Sprint 3 (Story 3.4) |
| R-06 VN tax-invoice | Sprint 9 (Story 9.2) |
| R-15 PCI scope | Sprint 3 (Story 3.3) |

**Full 15-entry register** in `addendum.md` (PRD companion) §A1.

---

## 10. 26 ADRs at a glance

```
ADR-01 saga           ADR-10 Next.js       ADR-19 OPA admission
ADR-02 services        ADR-11 idempotency   ADR-20 HMAC signing
ADR-03 DB-per-svc      ADR-12 saga state    ADR-21 webhook dedup
ADR-04 Kafka/Avro     ADR-13 Lua rate-lim  ADR-22 Snowflake strict
ADR-05 @SoftUk         ADR-14 outbox brg    ADR-23 PCI scope
ADR-06 single-whse     ADR-15 Avro compat   ADR-24 card-testing
ADR-07 B2C v1          ADR-16 obs+chaos     ADR-25 VN search
ADR-08 VN tax (Q5)     ADR-17 K8s+Helm     ADR-26 VN tax-implementation
ADR-09 REST+BFF        ADR-18 Vault
```

**Detail pages** for ADR-01, 04, 09, 10, 20, 26 in `architecture-detail.md`. See `ADR-INDEX.md` for 1-paragraph summary per ADR.

---

## 11. Hard constraints (DO NOT VIOLATE)

1. **No Debezium in v1** — Modulith outbox is the bridge (ADR-14)
2. **No PAN in logs** — R-15
3. **No `.env` files in repo** — Vault only (ADR-18)
4. **HMAC signatures required on every event** — R-04, AT-03 (ADR-20)
5. **Idempotency keys from `(aggregate_id, saga_step_name)`** — DI-02 (ADR-11)
6. **Q5 closure: `vietnam_tax_authority_credential` row required** before InvoiceService
7. **Vietnamese-first** — locale formatting, diacritic search, tax-invoice
8. **Per-service DB, no cross-service joins** — ADR-03

---

**Read next:** `AGENT-ONBOARDING.md` (entry point) → `ADR-INDEX.md` (per-ADR summary) → `architecture.md` (full) as needed.
