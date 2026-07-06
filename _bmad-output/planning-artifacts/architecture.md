---
stepsCompleted: [1, 2, 3, 4, 5, 6, 7, 8]
reviewStatus: 'cycle-4-clean'
reviewDate: '2026-07-06'
reviewCycle: 4
inputDocuments:
  - /home/tonminh/Documents/GitHub/side-project/_bmad-output/planning-artifacts/prd.md
  - /home/tonminh/Documents/GitHub/side-project/_bmad-output/planning-artifacts/prds/prd-side-project-2026-07-06/addendum.md
  - /home/tonminh/Documents/GitHub/side-project/_bmad-output/planning-artifacts/prds/prd-side-project-2026-07-06/.decision-log.md
  - /home/tonminh/Documents/GitHub/side-project/_bmad-output/brainstorming/brainstorming-session-2026-07-06-1119.md
  - /home/tonminh/Documents/GitHub/side-project/_bmad-output/planning-artifacts/market-research.md
  - /home/tonminh/Documents/GitHub/side-project/_bmad-output/planning-artifacts/domain-research.md
  - /home/tonminh/Documents/GitHub/side-project/_bmad-output/planning-artifacts/technical-research.md
  - /home/tonminh/Documents/GitHub/side-project/local-docs/00-first-architect.md
  - /home/tonminh/Documents/GitHub/side-project/local-docs/01-architecture-overview.md
  - /home/tonminh/Documents/GitHub/side-project/local-docs/02-architecture-decisions-and-fixes.md
  - /home/tonminh/Documents/GitHub/side-project/local-docs/03-kafka-and-cdc-strategy.md
  - /home/tonminh/Documents/GitHub/side-project/local-docs/04-rate-limiter-design.md
  - /home/tonminh/Documents/GitHub/side-project/local-docs/05-saga-and-checkout-flow.md
  - /home/tonminh/Documents/GitHub/side-project/local-docs/06-observability-and-security.md
  - /home/tonminh/Documents/GitHub/side-project/local-docs/07-implementation-roadmap.md
  - /home/tonminh/Documents/GitHub/side-project/local-docs/08-infrastructure-and-deployment.md
  - /home/tonminh/Documents/GitHub/side-project/local-docs/09-project-structure.md
  - /home/tonminh/Documents/GitHub/side-project/local-docs/10-util-library.md
workflowType: 'architecture'
project_name: 'side-project'
user_name: 'Tonminh'
date: '2026-07-06'
lastStep: 8
status: 'complete'
completedAt: '2026-07-06'
---

# Architecture Decision Document

_This document builds collaboratively through step-by-step discovery. Sections are appended as we work through each architectural decision together._

## Document Setup

**Created:** 2026-07-06
**Run folder:** `_bmad-output/planning-artifacts/architecture.md`
**Mode:** Auto-C per user instruction (similar to BP session)

**Documents loaded:**
- PRD: `_bmad-output/planning-artifacts/prd.md` (status: final; 17 sections, 82 FRs, 27 NFRs)
- PRD addendum: `prds/prd-side-project-2026-07-06/addendum.md` (Q1 saga options-considered, 15-risk table, capacity assumptions, version matrix)
- PRD decision log: `prds/prd-side-project-2026-07-06/.decision-log.md`
- Brainstorming: `_bmad-output/brainstorming/brainstorming-session-2026-07-06-1119.md` (189 ideas, 13 Five Whys drills, 15 risks)
- Market research: `planning-artifacts/market-research.md`
- Domain research: `planning-artifacts/domain-research.md` (13 services, 8 invariants)
- Technical research: `planning-artifacts/technical-research.md`
- 11 local-docs files (00..10)
- No UX design spec found — **not a blocker for v1** (per PRD §17: UX-feeds-architecture is conditional)

**Status:** Initialization complete. Awaiting [C] to continue to step-02 (project context analysis).

---

## Project Context Analysis

### Requirements Overview

**Functional Requirements (82 FRs across 16 domain groups):**
- 13 service boundaries: Catalog, Inventory, Cart, Checkout, Order, Fulfillment, Returns, Customer, Search, Notification, Admin, Pricing, Payment. Auth folded into Customer per brainstorming "Customer + Auth" coupling.
- Event-driven throughout: every major aggregate emits `*.lifecycle` events with Avro schema-compatibility enforcement.
- Vietnamese-first: diacritic-tolerant search, serialized tax-invoice (Jasper + QR), address hierarchy (Province/District/Commune from util/), Vietnamese fonts in reporting templates.
- Four P0 trust-bearing systems: Inventory (oversell race), Payment (double-capture), Auth (credential stuffing, mTLS), Tax-invoice (Vietnamese compliance).

**Non-Functional Requirements (27 across 7 categories):**
- Performance (5): catalog p99 < 100ms, search p99 < 300ms, hot-product defense, cache stampede, CDC JDBC pool isolation.
- Idempotency (3): event-id-keyed handlers, stable payment keys, idempotent cart merge.
- Availability (4): 99.9% checkout SLO, consumer lag alerts, Resilience4j circuit breakers, explicit fail-open policy.
- Observability (5): OTel + LGTM, bounded metric cardinality, span drop counter, log scrubber.
- Security (4): gateway trust boundary, mTLS + HMAC, OPA admission, Vault.
- Migration (3): expand-then-contract, Avro compat CI gate, ES alias-swap.
- i18n (3): display-vs-charge rounding, locale formatting, RTL config.

**Scale & complexity:**
- Primary domain: full-stack backend (Java/Spring Boot 4) + Next.js 15 web + Next.js admin
- Complexity: **enterprise** — multi-domain event-driven, distributed systems, compliance-heavy
- Architectural components: 13 services + Saga orchestrator + CDC pipeline + Schema registry + Elasticsearch + Redis + Gateway + Next.js storefront/admin
- Critical-path count: 7 (payment, inventory, tax-invoice, rate-limiter, mTLS, idempotency, saga)

### Technical Constraints & Dependencies

- **Java 25** (LTS) — virtual threads, records, sealed types, pattern matching, structured concurrency are first-class.
- **Spring Boot 4.0.0** (GA 10 Jun 2026) + **Spring Cloud 2025.1 "Oakwood"** — Boot 4 ecosystem 4–8 week library-lag risk (R-09).
- **Apache Kafka 4** in KRaft mode (no Zookeeper).
- **CDC:** Spring Modulith outbox (default; ADR-01 binding Q1 — see §"Core Architectural Decisions"). No Debezium in v1.
- **Schema registry:** Apicurio 2.6 with Avro, strict backward + forward compatibility.
- **Search:** Elasticsearch 8.x as primary read-side.
- **Cache + rate-limit:** Redis 7 with Lua scripts for atomic token-bucket.
- **Database:** PostgreSQL 16+ (or latest LTS), per-service DB.
- **Observability:** OTel + Prometheus + Grafana + Loki + Tempo (LGTM stack).
- **util/** shared library — mandatory reuse, but has known blocker: `<parent>vn.vnpt:be</parent>` in `util/pom.xml` references `../pom.xml` which doesn't exist in this repo (R-01). Sprint 0 must fix.
- **Payment:** Stripe only (no Adyen/Braintree in v1).
- **Carriers:** GHN, GHTK, Viettel Post (Vietnamese domestic).
- **Compliance:** Vietnam PDPD, Vietnamese tax-invoice (Circular 78/2021/TT-BTC + Decree 123/2020/NĐ-CP), PCI-DSS v4.0.

### Cross-Cutting Concerns Identified

- **Idempotency** — every event consumer, payment operation, cart merge, and webhook handler must be idempotent. Key strategy: stable `(order_id, saga_step_name)` (per DI-02 root cause).
- **Saga compensations** — every long-running saga step has a compensator; saga state is recoverable after crash.
- **Defense-in-depth** — Avro schema strict evolution, mTLS + HMAC, OTel log redaction, OPA admission policies, R-15 PCI scope minimization.
- **Observability first** — every service emits OTel traces, structured logs, and metrics; chaos tests aligned one-per-P0-risk.
- **Vietnamese compliance** — PDPD export, tax-invoice serialization, address hierarchy, locale formatting.
- **Open-source reference hygiene** — no merchant onboarding, no sales motion, all decisions documented as ADRs.

### Open Questions — Resolved by Architecture

All 5 Open Questions from PRD §13 have been resolved by ADRs in §"Core Architectural Decisions":

- **Q1** ✅ RESOLVED (ADR-01): **Spring Modulith outbox** (Green-Hat default). Saga = intra-process state machine on order aggregate.
- **Q2** ✅ RESOLVED (ADR-06): **Single-warehouse v1 default**; multi-warehouse P1 stretch.
- **Q3** ✅ RESOLVED (ADR-07): **B2C v1**; marketplace deferred to v2.
- **Q4** ✅ RESOLVED (ADR-05): **`@SoftUk` annotation** (util's existing); CI lint enforces.
- **Q5** ✅ **RESOLVED** (ADR-26 + Story 9.2b): Vietnamese tax-invoice implementation pattern bound; merchant credentials collected via structured schema `vietnam_tax_authority_credential` + accountant-input template (see ADR-26 detail). Sprint 9 Story 9.2b tracks the credential-collection ceremony.

---

## Starter Template Evaluation

### Primary Technology Domain

**Full-stack Java/Spring backend + Next.js 15 web + Next.js admin.** This is a reference implementation, not a new project — it has an existing `util/` shared library and a documented architecture (`local-docs/01..10`). The conventional "starter template" question (e.g., create-next-app, Spring Initializr) is **not applicable**; the project scaffold is already defined.

### Starter Options Considered

| Option | Decision | Rationale |
|---|---|---|
| **Spring Initializr** | Skipped | Would create a generic Spring Boot 4 project; the architecture is already committed via `local-docs/01-09` + `util/`. Using Initializr would re-derive decisions already made. |
| **create-next-app** for Next.js storefront | Skipped | Next.js 15 starter would be useful for a fresh project; here we have specific requirements (Stripe Elements, Elasticsearch client, OpenTelemetry browser) that need a custom scaffold. |
| **Existing `util/` + `local-docs/`** | **Selected** | The base library provides 60% of "boring infrastructure" (audit entities, Snowflake, Jasper, Excel, multi-tenant, OAuth2, Telegram errors, Vietnamese fonts). Architecture docs specify Kafka KRaft, CDC, Redis rate-limiter, observability stack. The "starter" is the documented architecture itself. |
| Custom multi-module Maven scaffold | Considered | Architecture's local-docs/09 (`project-structure.md`) defines the structure; see step-06 for details. |

### Selected "Starter": Existing `util/` library + `local-docs/` architecture

**Rationale for Selection:**

The project is a **reference implementation**, not a greenfield commercial app. The user has already done the equivalent of starter-template work:

1. **`util/` shared library** (`local-docs/10-util-library.md`) — already in place, contains:
   - `BaseEntity` + `RootEntity` (audit + soft-delete with `SnowflakeIdGenerator` UUID)
   - `JasperUtils` (PDF/Excel/RTF/HTML rendering with Vietnamese fonts)
   - `ExcelUtils` (annotation-driven import/export)
   - `CommonUtil`, `DatetimeUtil`, `StringUtil`, `JsonUtil`, `FileUtil`, `QRCodeUtil`
   - `TelegramBotAPIUtil` (error reporting channel)
   - `CustomSecurityExpressionHandler` + `ICodeJwtGrantedAuthoritiesConvertor` (OAuth2 helpers)
   - `UtilsAutoConfiguration` (Spring Boot auto-config entry point)
   - **Known blocker:** R-01 — `util/pom.xml` references parent `vn.vnpt:be` which doesn't exist in this repo. Must be fixed in Sprint 0.

2. **`local-docs/01-09.md`** — architecture decisions already documented:
   - `01-architecture-overview.md` — high-level system structure
   - `02-architecture-decisions-and-fixes.md` — ADRs and revisions
   - `03-kafka-and-cdc-strategy.md` — Kafka KRaft, Debezium outbox strategy
   - `04-rate-limiter-design.md` — Redis Lua token-bucket (NB: technical-research flagged a time-source bug — use `redis.call('TIME')` not gateway wall-clock)
   - `05-saga-and-checkout-flow.md` — Spring Statemachine saga with compensating actions
   - `06-observability-and-security.md` — OTel + LGTM, mTLS, OPA
   - `07-implementation-roadmap.md` — sprint plan
   - `08-infrastructure-and-deployment.md` — K8s, Helm, ArgoCD
   - `09-project-structure.md` — multi-module Maven layout

**Initialization command:**

There is no `create-` command to run. The project init is:

```bash
# 1. Fix util/ parent pom blocker (R-01)
#    Option A: vendor vn.vnpt:be parent pom at project root
#    Option B: replace <parent> with inline <dependencyManagement> in util/pom.xml
cd /home/tonminh/Documents/GitHub/side-project
# 2. (After R-01 fix) build and install util/
cd util
mvn clean install -DskipTests
# 3. Bootstrap the reference services per local-docs/09
cd ..
# Service scaffolds created from documented structure
```

**Architectural Decisions Provided by the "Starter":**

| Decision | Value | Source |
|---|---|---|
| Java runtime | 25 (LTS) | PRD §7.1, technical-research |
| Build tool | Maven 4.x | util/pom.xml precedent, local-docs/09 |
| Backend framework | Spring Boot 4.0.0 + Spring Cloud 2025.1 | PRD §7.1 |
| Frontend framework | Next.js 15 | PRD §1, brainstorming |
| Per-service layout | Multi-module Maven | local-docs/09 |
| Database | PostgreSQL 16+ | PRD §7.1 |
| Schema registry | Apicurio 2.6 Avro | PRD §7.1, technical-research |
| Search | Elasticsearch 8.x | PRD §7.1 |
| Cache + rate-limit | Redis 7 with Lua | PRD §7.1, local-docs/04 |
| Messaging | Apache Kafka 4 KRaft | PRD §7.1 |
| CDC | Spring Modulith outbox (default; no Debezium in v1) | ADR-01 (Q1 RESOLVED); ADR-14 |
| Observability | OTel + Prometheus + Grafana + Loki + Tempo | PRD §6.4, local-docs/06 |
| Auth | JWT (util's `CustomSecurityExpressionHandler`) | local-docs/10 |
| Reporting | Jasper 7.0.3 (Vietnamese fonts via util) | local-docs/10 |
| Payment | Stripe Elements (iframe-only) | PRD §4.5 |

**Note:** Project initialization is **Sprint 0, story 0.1** ("Fix `util/` parent pom blocker") and **0.2** ("Bootstrap reference monorepo per `local-docs/09`").

---

## Core Architectural Decisions

### ADR Index

| # | Decision | Status | Affects |
|---|---|---|---|
| ADR-01 | Saga architecture = **Spring Modulith outbox** (default Green-Hat path) | **RESOLVED** (Q1 binding) | FR-22, all long-running flows |
| ADR-02 | Service boundaries = 13 services; Auth folded into Customer | **RESOLVED** | FR-1 to FR-82, all modules |
| ADR-03 | Database-per-service | **RESOLVED** | FR-6, FR-13, FR-30, FR-45, etc. |
| ADR-04 | Event-driven foundation: Kafka 4 KRaft + Avro via Apicurio 2.6 | **RESOLVED** | All event-emitting services |
| ADR-05 | Soft-delete uniqueness via util's `@SoftUk` (Q4) | **RESOLVED** | FR-12, util extension |
| ADR-06 | Single-warehouse v1 (Q2); multi-warehouse P1 stretch | **RESOLVED** | FR-10, InventoryService |
| ADR-07 | B2C v1 (Q3); marketplace v2 | **RESOLVED** | FR-15, CartService |
| ADR-08 | Vietnamese tax-invoice FR-78 binding; registration specifics (Q5) deferred to accountant | **RESOLVED** | FR-78, InvoiceService; ADP-A26 detail |
| ADR-09 | API style: REST + BFF | **RESOLVED** | All services |
| ADR-10 | Frontend: Next.js 15 App Router + Server Actions | **RESOLVED** | UJ-1..UJ-4 |
| ADR-11 | Idempotency-key strategy: stable `(aggregate_id, saga_step_name)` | **RESOLVED** (per DI-02 root cause) | NFR-IDEM-1/2/3 |
| ADR-12 | Saga = single Modulith module; saga is intra-process, NOT network | **RESOLVED** (consequence of ADR-01) | FR-22, FR-23 |
| ADR-13 | Rate-limiter: Redis Lua with `redis.call('TIME')` (fixes local-docs/04 bug) | **RESOLVED** (per technical-research flag) | R-05, FR-81 |
| ADR-14 | Outbox table: per-service; CDC to Kafka is via Modulith outbox bridge (no Debezium in v1) | **RESOLVED** (consequence of ADR-01) | FR-22, R-04 |
| ADR-15 | Avro schema compat: strict backward + forward, CI gate | **RESOLVED** | NFR-MIG-2, FR-5 |
| ADR-16 | Observability: OTel + LGTM + Chaos Mesh; one chaos experiment per P0 risk | **RESOLVED** | NFR-OBS-1..5, §9 |
| ADR-17 | Deployment: K8s (single-region, multi-AZ) + Helm + ArgoCD GitOps | **RESOLVED** | §9, §10 |
| ADR-18 | Secrets: HashiCorp Vault (no `.env` in repo) | **RESOLVED** | NFR-SEC-4 |
| ADR-19 | Admission policies: OPA/Rego for Kafka topic + schema registration | **RESOLVED** | NFR-SEC-3 |
| ADR-20 | CDC event injection defense: mTLS + per-service HMAC headers | **RESOLVED** (per AT-03 root cause) | NFR-SEC-2, FR-82 |
| ADR-21 | Webhook handler: idempotent on Stripe `event.id` via `webhook_dedup` table | **RESOLVED** (per DI-02 + brainstorming `[PAY-A]`) | FR-26 |
| ADR-22 | Snowflake worker-id: throw if `POD_NAME` missing in non-dev profile | **RESOLVED** (per OP-05 root cause) | R-08 |
| ADR-23 | PCI scope: Stripe Elements iframe only; OTel log-redaction matches `\d{13,19}`; default request-body logger deny-listed | **RESOLVED** (per R-15) | FR-29, NFR-SEC-1 |
| ADR-24 | Card-testing defense: rate-limiter keys on `IP + card-fingerprint + ASN`; BIN velocity check | **RESOLVED** (per AT-01 root cause) | FR-81, R-05 |
| ADR-25 | Vietnamese search: diacritic folding token filter + phonetic fallback | **RESOLVED** (per UX-05) | FR-52, R-07 |
| ADR-26 | Vietnamese tax-invoice: serialized number allocator + Jasper template + QR code | **RESOLVED** (per LC-03) | FR-78, R-06 |

### ADR Detail Section → see companion

The per-ADR deep-dive sections (ADR-01, ADR-04, ADR-09, ADR-10, ADR-20, ADR-26) and the saga-state storage detail + merchant-credentials schema + Q5 closure procedure have been moved to the companion file for readability:

→ **`architecture-detail.md`** (in the same folder)

Includes:
- **§"Detail: ADR-01"** — Q1 saga resolution rationale + reversibility path
- **§"Detail: ADR-04"** — Per-locale ES index strategy + outbox bridge operational details
- **§"Detail: ADR-09"** — API style including BFF + service-to-service
- **§"Detail: ADR-10"** — Next.js 15 frontend stack details
- **§"Detail: ADR-20"** — HMAC-SHA-256 event signing scheme (algorithm, Vault path, JCS canonical JSON, key rotation)
- **§"Detail: ADR-26"** — Vietnamese tax-invoice: serialized allocator, Jasper template, QR; **merchant-credentials schema (`vietnam_tax_authority_credential`)** and **Q5 closure procedure**

ADR table below retains summary; full binding detail lives in the companion.

---

### Decision Impact Analysis

**Implementation sequence** (consequence of dependencies):

1. **Sprint 0 (foundations):** Fix `util/` parent pom (R-01). Bootstrap monorepo per `local-docs/09`. Dev docker-compose with KRaft + Postgres + Redis + ES + Apicurio + Stripe test mode. CI scaffold.
2. **Sprint 1 (catalog + inventory):** CatalogService + InventoryService with outbox + CDC. FR-1 to FR-13. Solves DI-01 root cause.
3. **Sprint 2 (cart + checkout):** CartService + CheckoutService with saga on the order aggregate. FR-14 to FR-23. Solves FR-22 binding.
4. **Sprint 3 (payment + idempotency):** PaymentService + webhook handler + Stripe Elements integration. FR-24 to FR-29. Solves DI-02, R-03 (idempotency), R-05 (card-testing defense via gateway Lua + card-fingerprint hash + BIN velocity), R-15 (PCI scope).
5. **Sprint 4 (order + fulfillment):** OrderService + ShipmentService + carrier adapters. FR-30 to FR-39.
6. **Sprint 5 (customer + auth):** CustomerService + auth flow + Vietnamese address autocomplete. FR-45 to FR-50, FR-73 to FR-77. Solves AT-02 (credential stuffing / account lockout per FR-76) and LC-01 (PDPD export).
7. **Sprint 6 (search + recommendation):** SearchService with Vietnamese diacritic folding. FR-51 to FR-55. Solves R-07.
8. **Sprint 7 (returns + RMA):** ReturnsService. FR-40 to FR-44. Solves DI-07 (cumulative refund safety).
9. **Sprint 8 (admin + reporting):** AdminService via Next.js role-gated routes. FR-61 to FR-64.
10. **Sprint 9 (notification + tax-invoice):** NotificationService + InvoiceService. FR-56 to FR-60, FR-78. Solves LC-03, R-06.
11. **Sprint 10 (observability + chaos):** LGTM dashboards provisioned from Git, Chaos Mesh experiments per P0 risk, runbook per alert. Validates R-01..R-15 mitigations.

**Cross-component dependencies:**

- ADR-01 (Modulith outbox) → affects all service modules; saga pattern is intra-process.
- ADR-04 (event-driven) → all services emit events; downstream consumers depend on schema evolution rules (ADR-15).
- ADR-13 (Lua time fix) → gateway team needs to merge the fix BEFORE FR-81 is implemented.
- ADR-22 (Snowflake strict mode) → must be deployed in dev BEFORE any InventoryService tests run; otherwise worker-id collisions go unnoticed.
- ADR-26 (tax-invoice) → depends on util's Jasper template library + Vietnamese fonts already in `util/src/main/resources/fonts/`.

---

## Implementation Patterns & Consistency Rules

> **Goal:** All AI agents (Amelia the dev, any subagent) writing code for this project MUST follow these patterns. Patterns exist to prevent agent-vs-agent conflicts, not to dictate implementation detail.

### Naming Patterns

#### Database Naming Conventions

| Object | Rule | Example |
|---|---|---|
| Tables | `snake_case`, **plural** | `users`, `cart_lines`, `inventory_ledger` |
| Columns | `snake_case` | `user_id`, `created_at`, `is_active` |
| Primary key | `id` (auto-increment BIGINT) per service; `uuid` (Snowflake Long) is the cross-service aggregate ID | `id`, `uuid` |
| Foreign key | `<referenced_table_singular>_id` | `user_id`, `order_id` |
| Indexes | `idx_<table>_<column>` | `idx_users_email`, `idx_orders_user_id` |
| Unique constraints | `uq_<table>_<column>` | `uq_inventory_ledger_variant_warehouse` |
| Outbox table | `outbox` (one per service); columns: `id`, `aggregate_type`, `aggregate_id`, `event_type`, `event_id`, `payload` (JSONB), `created_at`, `published_at` (nullable, set when Modulith bridge acks) | `outbox` (in catalog DB), `outbox` (in order DB) |
| Idempotency table | `processed_event` (or `webhook_dedup` for Stripe) | `processed_event`, `webhook_dedup` |
| Order state transition log | `order_state_transition`; columns: `id`, `order_uuid`, `from_state`, `to_state`, `saga_step`, `event_id`, `created_at` | `order_state_transition` |
| Schema migrations | `V<NNN>__<descriptive_name>.sql` (Flyway convention) | `V001__create_users.sql` |

#### Java Code Naming

| Object | Rule | Example |
|---|---|---|
| Packages | `vn.vnpt.<service>.<layer>` | `vn.vnpt.catalog.domain`, `vn.vnpt.catalog.api` |
| Classes | `PascalCase` | `CatalogService`, `InventoryReservationSaga` |
| Interfaces | `PascalCase`, no `I` prefix | `Saga`, `OutboxPublisher` |
| Methods | `camelCase`, verbs | `reserveStock`, `publishCatalogEvent` |
| Variables | `camelCase` | `cartTotal`, `orderId` |
| Constants | `SCREAMING_SNAKE_CASE` | `MAX_RESERVATION_TTL_MINUTES` |
| Test classes | `<Class>Test` or `<Class>IT` (Testcontainers integration) | `CatalogServiceTest`, `InventoryServiceIT` |
| Resource files | `kebab-case` | `jasper-invoice-vi.jasper` |

#### TypeScript Code Naming

| Object | Rule | Example |
|---|---|---|
| Files | `PascalCase.tsx` for components, `camelCase.ts` for utilities | `CartItem.tsx`, `formatVnd.ts` |
| Components | `PascalCase` | `CartItem`, `CheckoutForm` |
| Hooks | `use<Thing>` | `useCart`, `useStripePayment` |
| Utilities | `camelCase` | `formatVnd`, `parseAddress` |
| Constants | `SCREAMING_SNAKE_CASE` | `MAX_LINE_ITEMS` |
| Types/Interfaces | `PascalCase` | `CartLine`, `Address` |

#### API Naming

| Object | Rule | Example |
|---|---|---|
| REST endpoint paths | plural nouns, kebab-case | `/api/carts`, `/api/inventory-reservations` |
| BFF endpoint paths | `/bff/<surface>/...` | `/bff/storefront/cart`, `/bff/admin/orders` |
| HTTP method | per REST semantics | `GET /api/carts/{id}` |
| Query params | `snake_case` | `?page_size=20&sort=created_at` |
| Path params | lowercase | `/api/carts/{cart_id}` |
| Headers | `X-Header-Name` or `Header-Name` (RFC 7230) | `X-Tenant`, `X-Request-Id` |

#### Event Naming

| Object | Rule | Example |
|---|---|---|
| Event topic | `<aggregate>.<lifecycle-event>` (kebab-case) | `orders.placed`, `inventory.reserved` |
| Event payload (Avro record) | `<Domain><Event>` (PascalCase) | `OrdersPlaced`, `InventoryReserved` |
| Event field names | `snake_case` in Avro / JSON; `camelCase` when used in Java POJOs | field: `order_id`, POJO: `orderId` |
| Event version | suffix `_v<n>` on topic when breaking; never break, add new topic | `orders.placed_v2` (only if needed) |
| Outbox table column | `event_type` (string), `payload` (JSONB), `created_at` (timestamp) | |

### Structure Patterns

#### Project Organization (Java backend, multi-module Maven)

Per `local-docs/09-project-structure.md`. Each service = one Maven module.

```
ecommerce-reference/
├── pom.xml                          # Parent pom (BOM, plugins, dependencyManagement)
├── util/                            # Existing shared library (Spring Boot auto-config)
│   └── (see local-docs/10)
├── services/
│   ├── catalog/                     # CatalogService
│   │   ├── pom.xml
│   │   └── src/
│   │       ├── main/java/vn/vnpt/catalog/
│   │       │   ├── CatalogApplication.java   # @SpringBootApplication
│   │       │   ├── api/                       # REST controllers (BFF-facing)
│   │       │   ├── domain/                    # Aggregates, value objects, domain events
│   │       │   ├── application/               # Use cases / commands / queries
│   │       │   ├── infrastructure/            # Outbox publisher, ES client, repositories
│   │       │   └── config/                    # Spring config
│   │       └── test/java/vn/vnpt/catalog/...
│   ├── inventory/
│   ├── cart/
│   ├── checkout/
│   ├── payment/
│   ├── order/
│   ├── fulfillment/
│   ├── returns/
│   ├── customer/                    # Customer + Auth
│   ├── search/
│   ├── notification/
│   ├── admin/
│   ├── pricing/
│   └── invoice/                     # Vietnamese tax-invoice
├── bff/
│   ├── storefront-bff/
│   └── admin-bff/
├── frontend/                        # Next.js 15 apps
│   ├── storefront/                  # Customer-facing
│   ├── admin/                       # Staff-facing
│   └── packages/                    # Shared UI components, types
├── platform/                        # Cross-cutting
│   ├── observability/               # OTel config, dashboards, runbooks
│   ├── policies/                    # OPA/Rego admission
│   ├── chaos/                       # Chaos Mesh experiments
│   └── ci-cd/                       # GitHub Actions, ArgoCD config
└── dev/                             # Local dev only
    ├── docker-compose.yml           # Postgres + Kafka KRaft + ES + Redis + Apicurio
    ├── seed-data/                   # Seed scripts per Sprint
    └── chaos/                       # Local chaos experiments
```

#### Test Organization

- **Unit tests:** co-located with the class under test in `src/test/java/<package>/<Class>Test.java`.
- **Integration tests (Testcontainers):** `src/test/java/<package>/<Class>IT.java`. Use Testcontainers for Postgres, Kafka, Redis, ES.
- **Contract tests (Pact or equivalent):** `src/test/java/<package>/<Class>ContractTest.java` for cross-service contracts.
- **End-to-end tests:** `e2e-tests/` module, run against a deployed dev environment.

#### Configuration File Organization

- `application.yml` — defaults
- `application-{profile}.yml` — profile-specific (dev, staging, prod)
- `bootstrap.yml` — for Vault / external config
- All secrets via Vault references; **no `.env` files in repo**.

### Format Patterns

#### API Response Wrapper

All API responses use a standard wrapper (per `util/exception/ResponseResult.java` precedent):

```json
// Success
{
  "code": 200,
  "status": "OK",
  "data": { ... }
}

// Error
{
  "code": 400,
  "status": "BAD_REQUEST",
  "message": "Validation failed: cart must not be empty",
  "details": { "field": "cart_id", "reason": "required" }
}
```

**Error code mapping** (RFC 7231 + custom):
- 200 OK
- 400 BAD_REQUEST (validation)
- 401 UNAUTHORIZED
- 403 FORBIDDEN
- 404 NOT_FOUND
- 409 CONFLICT (idempotency, optimistic concurrency)
- 422 UNPROCESSABLE_ENTITY (business rule violation)
- 429 TOO_MANY_REQUESTS (rate limit)
- 500 INTERNAL_SERVER_ERROR

#### Date/Time Formats

- **Wire format:** ISO 8601 with timezone. Example: `2026-07-06T11:30:00+07:00` (Asia/Ho_Chi_Minh).
- **Storage:** `LocalDateTime` (Asia/Ho_Chi_Minh), `Instant` for UTC, `OffsetDateTime` when timezone matters in transit.
- **Display:** locale-aware via `DateTimeFormatterBuilder` with `Locale` parameter (per NFR-I18N-2; replaces util's `createdAtFormatted` hard-coded `dd/MM/yyyy`).

#### Money Formatting

- **Storage:** always `Long` minor units (cents/đồng). Never `BigDecimal` for amounts in code; never `Double`/`Float`. **Critical invariant** (per domain-research).
- **Wire format:** number with currency code. Example: `{"amount": 199000, "currency": "VND"}`.
- **Display:** locale-aware via Java's `NumberFormat` with `Locale("vi", "VN")` for Vietnamese formatting (1.234.567 ₫).
- **Rounding rule:** display rounding ≠ charge rounding (per NFR-I18N-1, I18N-01). Document the rounding policy in config.

#### JSON Field Naming

- **JSON over the wire:** `snake_case` (matches Java records without annotation).
- **Java records / POJO fields:** `camelCase`.
- **Use Jackson `@JsonProperty`** to bridge if needed; default to `@JsonProperty.Access.AUTO` and configure global Jackson naming strategy.

#### Boolean Representations

- `true` / `false` only. **Never** `1` / `0` over the wire.
- Java entity boolean fields: prefix with `is_` in DB (e.g., `is_active`, `is_deleted`) but use `isActive` / `isDeleted` in code. Map via `@Column(name = "is_active")` on `Boolean` field.

### Communication Patterns

#### Event Payload Structure

Standard envelope (matches `domain-research.md` canonical event schema):

```json
{
  "event_id": "1234567890123456789",        // Snowflake ID, unique per event
  "event_type": "orders.placed",             // topic name
  "event_version": 1,                        // schema version
  "occurred_at": "2026-07-06T11:30:00.123Z",// event time (UTC)
  "aggregate_id": "9876543210987654321",    // Snowflake ID of the aggregate
  "aggregate_type": "order",                 // aggregate name
  "tenant_id": "default",                    // tenant (multi-tenant-ready)
  "correlation_id": "checkout-uuid-here",    // request correlation
  "causation_id": "checkout.started",        // what caused this event
  "payload": { ... },                        // Avro-record-shaped per schema
  "signatures": {                            // HMAC for AT-03 mitigation
    "service": "checkout",
    "hmac_sha256": "..."
  }
}
```

#### State Update Patterns (event handlers)

- Event handlers MUST be idempotent: check `processed_event.event_id` before applying state change.
- Event handler state changes use **optimistic concurrency**: each aggregate has a `version` field; UPDATE includes `WHERE version = ?`; if 0 rows updated, retry with fresh state.
- Failed event handlers retry with **exponential backoff + jitter**, max 5 attempts; then dead-letter to `*.dlq` topic.

#### Logging Formats (structured)

All log lines are JSON, one event per line. Required fields:

```json
{
  "timestamp": "2026-07-06T11:30:00.123Z",
  "level": "INFO",
  "service": "checkout",
  "trace_id": "abc123",                       // OTel trace ID
  "span_id": "def456",                        // OTel span ID
  "thread": "http-nio-8080-exec-1",
  "logger": "vn.vnpt.checkout.CheckoutService",
  "message": "checkout completed",
  "cart_id": "9876...",
  "order_id": "1234...",
  "duration_ms": 234
}
```

- **No PII in logs** by default (per NFR-OBS-5; OTel processor scrubs).
- **No PAN in logs** (R-15 mitigation).
- **No concatenation** of dynamic data into the message; use structured fields.

### Process Patterns

#### Error Handling

- **Java service layer:** throw a domain exception (e.g., `InsufficientStockException`). Do not catch and return error responses from service layer.
- **Java API layer:** use `@RestControllerAdvice` to map domain exceptions to HTTP responses via `util/exception/GlobalExceptionHandler.java`. Each exception class has a corresponding `ErrorCodeEnum` value.
- **Telegram notification:** `util/telegram/TelegramBotAPIUtil` sends error notifications to a configured Telegram bot. Default OFF in prod; ON in dev. (Gated by `telegram.is-send-error` config; per R-12 mitigation in `local-docs/10-util-library.md`.)
- **BFF layer:** transform service-layer errors to user-friendly messages; never expose stack traces.

#### Validation

- **Input validation:** use Jakarta Bean Validation (`@NotNull`, `@Size`, `@Min`, `@Max`, etc.) on DTOs.
- **Domain validation:** business rules enforced in domain entities / aggregates, not in the API layer.
- **Vietnamese-specific validation:** `@SpecialSymbolConstraint` from `util/annotation/` for inputs that should not contain Vietnamese diacritics (e.g., SKU codes, slugs).

#### Authentication Flow

- **Frontend → BFF:** session cookie (httpOnly, secure, sameSite=lax). Login via `/api/auth/login` returns session cookie + CSRF token.
- **BFF → service:** mTLS + JWT bearer token. JWT contains user ID, tenant, roles. JWT signed with RS256, key rotated quarterly.
- **Service-to-service:** mTLS for transport, plus per-request JWT for caller identity (the `service-account` role in `util/icode/`).
- **Outbox event security:** HMAC header per event (per AT-03 root cause mitigation).

#### Retry Implementation

- **HTTP retries:** Resilience4j retry with exponential backoff + jitter, max 3 attempts. Configurable per call site.
- **Kafka consumer retries:** in-memory retry 3x with backoff, then dead-letter. Dead-letter consumer alerts on-call.
- **Saga compensations:** never retry; always compensate. If compensation fails, page on-call.

#### Loading State (frontend)

- TanStack Query for server state with `isLoading`, `isError`, `data` pattern.
- Skeleton components for content placeholders (per UJ-1 / UJ-3 latency expectations).
- Optimistic updates for cart-line additions; pessimistic for checkout submission.

### Enforcement Guidelines

**All AI agents MUST:**

- Use the standard API response wrapper (no raw exceptions, no service-specific shapes).
- Use Avro for all inter-service events; never raw JSON for events.
- Use the `outbox` table for any state change that must publish an event atomically.
- Use the `processed_event` table for event consumer idempotency.
- Use structured JSON logging (no concatenated messages).
- Use OTel propagation headers (`traceparent`, `tracestate`) on every inter-service call.
- Use Jakarta Bean Validation on DTOs.
- Use util's `BaseEntity` for any JPA entity.
- Use util's `SnowflakeIdGenerator.generateId()` for any new aggregate ID.
- Use util's `JasperUtils` (and the bundled Vietnamese fonts) for any report rendering.
- Use `util/annotation/ExcelExport` / `ExcelImport` for any spreadsheet I/O.
- Use util's `GlobalExceptionHandler` pattern (or a service-local extension); don't write ad-hoc error mappers.
- Use `redis.call('TIME')` inside any Redis Lua script that involves time-based math (fixes the local-docs/04 rate-limiter bug per technical-research).
- Never log PAN, CVV, or other PCI-DSS-scope data (R-15).
- Never log customer PII in dev unless behind an explicit `pii-logging-enabled=true` config flag.

**Pattern enforcement:**

- **CI checks:** archunit tests verify package boundaries (Modulith `ApplicationModules.verify()`) and forbidden API patterns.
- **Code review:** every PR must pass a checklist confirming pattern adherence.
- **Style:** Spotless (Java) + Prettier (TypeScript) run on every commit.
- **Lint:** Checkstyle (Java) + ESLint (TypeScript) block PRs with violations.

### Pattern Examples

**Good example: Catalog service emitting an event**

```java
// domain/Product.java
@Entity
@Table(name = "products")
public class Product extends BaseEntity {
    private String name;
    private String sku;
    // ... other fields
}

// application/CreateProductUseCase.java
@Service
public class CreateProductUseCase {
    private final ProductRepository repo;
    private final OutboxPublisher outbox;
    
    @Transactional
    public Product create(CreateProductCommand cmd) {
        var product = Product.create(cmd);
        repo.save(product);
        outbox.append(new CatalogProductCreated(
            product.getUuid(), 
            cmd.name(), 
            cmd.sku(), 
            Instant.now()
        ));
        return product;
    }
}
```

**Good example: Order service handling a payment event idempotently**

```java
// application/PaymentCapturedHandler.java
@ModulithListener("payment.lifecycle")
public class PaymentCapturedHandler {
    private final OrderRepository orders;
    private final ProcessedEventRepository processed;
    
    @Transactional
    public void on(PaymentCaptured event) {
        if (processed.existsByEventId(event.eventId())) {
            log.info("Skipping duplicate event {}", event.eventId());
            return;
        }
        var order = orders.findByUuid(event.aggregateId())
            .orElseThrow(() -> new OrderNotFound(event.aggregateId()));
        order.markPaid(event.amount(), event.paymentId());
        // version-based optimistic concurrency enforced by aggregate
        processed.save(new ProcessedEvent(event.eventId(), "payment.lifecycle", Instant.now()));
    }
}
```

**Anti-pattern (will fail code review):**

```java
// ❌ DO NOT DO THIS
@EventListener
public void on(PaymentCaptured event) {
    var order = orders.findById(event.orderId()).get();
    order.setStatus("PAID");  // ❌ No idempotency check, no optimistic concurrency
    order.setPaymentId(event.paymentId());
    orders.save(order);
    // ❌ No processed_event insert, will replay on Kafka redelivery
    // ❌ No structured logging
    log.info("Order " + order.getId() + " paid via " + event.paymentId());  // ❌ String concat, PII risk
}
```

### Pattern Decision Log

| Decision | Source |
|---|---|
| Spring Modulith outbox | ADR-01 |
| Service boundaries (13 services) | ADR-02 |
| Database-per-service | ADR-03 |
| Event-driven foundation | ADR-04 |
| util's `@SoftUk` for soft-delete uniqueness | ADR-05 |
| API style: REST + BFF | ADR-09 |
| Idempotency-key strategy: `(aggregate_id, saga_step_name)` | ADR-11 |
| Saga = intra-process state machine on aggregate | ADR-12 |
| Rate-limiter Lua with `redis.call('TIME')` | ADR-13 |
| Per-service outbox table, Modulith outbox bridge | ADR-14 |
| Avro strict backward + forward compat | ADR-15 |
| OTel + LGTM + Chaos Mesh | ADR-16 |
| K8s + Helm + ArgoCD GitOps | ADR-17 |
| HashiCorp Vault for secrets | ADR-18 |
| OPA/Rego admission policies | ADR-19 |
| mTLS + per-service HMAC event headers | ADR-20 |
| Stripe webhook dedup on `event.id` | ADR-21 |
| Snowflake strict mode in prod | ADR-22 |
| Stripe Elements iframe + log redaction | ADR-23 |
| Card-testing defense (IP + fingerprint + ASN) | ADR-24 |
| Vietnamese diacritic folding in ES | ADR-25 |
| Vietnamese tax-invoice via Jasper + QR | ADR-26 |

---

## Project Structure & Boundaries

### Complete Project Tree

```
ecommerce-reference/
│
├── pom.xml                                  # Parent pom: BOM, plugin mgmt, dep mgmt
├── README.md                                # Quick start, ADRs index, dev setup
├── .gitignore
├── .editorconfig
├── .gitattributes
├── LICENSE
├── CONTRIBUTING.md
├── CODE_OF_CONDUCT.md
│
├── util/                                    # Existing shared library (see local-docs/10)
│   ├── pom.xml                              # ⚠️ R-01: parent pom broken — fix in Sprint 0
│   ├── README.md
│   └── src/main/java/vn/vnpt/util/          # (already exists; ~95 Java files)
│
├── services/                                # 13 service modules (Modulith)
│   ├── catalog/
│   │   ├── pom.xml
│   │   └── src/
│   │       ├── main/
│   │       │   ├── java/vn/vnpt/catalog/
│   │       │   │   ├── CatalogApplication.java
│   │       │   │   ├── api/                 # CatalogController, BffFacade
│   │       │   │   │   ├── CatalogController.java
│   │       │   │   │   └── dto/             # CreateProductRequest, ProductResponse
│   │       │   │   ├── domain/              # Product, Variant, Price (aggregates)
│   │       │   │   │   ├── Product.java
│   │       │   │   │   ├── Variant.java
│   │       │   │   │   └── event/          # CatalogProductCreated, CatalogPriceChanged
│   │       │   │   ├── application/         # CreateProductUseCase, UpdatePriceUseCase
│   │       │   │   ├── infrastructure/     # ProductRepository, OutboxPublisher
│   │       │   │   └── config/             # CatalogConfiguration (modulith module)
│   │       │   └── resources/
│   │       │       ├── application.yml
│   │       │       ├── application-dev.yml
│   │       │       └── db/migration/       # Flyway: V001__create_products.sql, etc.
│   │       └── test/...
│   │
│   ├── inventory/                           # InventoryService + ReservationSaga
│   │   └── src/main/java/vn/vnpt/inventory/
│   │       ├── api/, domain/, application/, infrastructure/, config/
│   │       └── ... (FR-8 to FR-13)
│   │
│   ├── cart/                                # CartService (FR-14 to FR-18)
│   ├── checkout/                            # CheckoutService (FR-19 to FR-23)
│   ├── payment/                             # PaymentService (FR-24 to FR-29)
│   ├── order/                               # OrderService (FR-30 to FR-34)
│   ├── fulfillment/                         # ShipmentService (FR-35 to FR-39)
│   ├── returns/                             # ReturnsService (FR-40 to FR-44)
│   ├── customer/                            # CustomerService + Auth (FR-45 to FR-50, FR-73 to FR-77)
│   ├── search/                              # SearchService (FR-51 to FR-55)
│   ├── notification/                        # NotificationService (FR-56 to FR-60)
│   ├── admin/                               # AdminService (FR-61 to FR-64)
│   ├── pricing/                             # PricingService (FR-65 to FR-68)
│   └── invoice/                             # InvoiceService + Vietnamese tax (FR-78)
│
├── bff/                                     # Backend-for-Frontend layer
│   ├── storefront-bff/
│   │   ├── pom.xml
│   │   └── src/main/java/vn/vnpt/bff/storefront/
│   │       ├── StorefrontBffApplication.java
│   │       ├── api/                         # /bff/storefront/* endpoints
│   │       ├── client/                      # WebClient-based callers to services
│   │       ├── compositIon/                 # Aggregate responses from multiple services
│   │       └── config/
│   └── admin-bff/
│       └── (similar structure)
│
├── frontend/                                # Next.js 15 apps (TypeScript)
│   ├── storefront/                          # Customer-facing
│   │   ├── package.json
│   │   ├── next.config.mjs
│   │   ├── tsconfig.json
│   │   ├── tailwind.config.ts
│   │   ├── .env.example
│   │   ├── src/
│   │   │   ├── app/                         # App Router
│   │   │   │   ├── layout.tsx
│   │   │   │   ├── page.tsx                 # Home
│   │   │   │   ├── products/[slug]/page.tsx
│   │   │   │   ├── cart/page.tsx
│   │   │   │   └── checkout/page.tsx
│   │   │   ├── components/
│   │   │   │   ├── ui/                     # shadcn-style primitives
│   │   │   │   ├── cart/                   # CartItem, CartSummary
│   │   │   │   ├── checkout/               # CheckoutForm, StripePaymentElement
│   │   │   │   ├── product/                # ProductCard, ProductGallery
│   │   │   │   └── search/                 # SearchBar, SearchResults
│   │   │   ├── lib/                         # BFF client, Stripe.js, OTel browser
│   │   │   ├── hooks/                       # useCart, useStripePayment
│   │   │   ├── i18n/                        # vi.json, en.json
│   │   │   └── types/
│   │   └── public/
│   │
│   ├── admin/                               # Staff-facing (Next.js 15)
│   │   └── (similar structure; role-gated routes /admin/*)
│   │
│   └── packages/                            # Shared frontend packages
│       ├── ui/                              # Shared components
│       ├── types/                           # Shared TS types (mirrors Avro schemas)
│       └── eslint-config/
│
├── platform/                                # Cross-cutting concerns
│   ├── observability/
│   │   ├── otel-config/                     # Java auto-instrumentation
│   │   ├── grafana-dashboards/              # Provisioned dashboards (JSON in Git)
│   │   ├── prometheus-rules/                # Recording + alerting rules
│   │   ├── loki-schemas/                    # Log label schemas
│   │   └── tempo-config/
│   ├── policies/
│   │   └── opa/                             # OPA/Rego admission policies
│   │       ├── kafka-topic-creation.rego
│   │       ├── schema-registration.rego
│   │       └── jdbc-pool-sizing.rego
│   ├── chaos/
│   │   └── chaos-mesh/                      # One experiment per P0 risk
│   │       ├── r-02-inventory-oversell.yaml
│   │       ├── r-03-payment-double-capture.yaml
│   │       ├── r-04-kafka-broker-kill.yaml
│   │       ├── r-05-card-testing-burst.yaml
│   │       ├── r-06-tax-authority-timeout.yaml
│   │       └── r-15-pci-scope-leak.yaml
│   ├── runbooks/                            # One Markdown per alert
│   └── ci-cd/
│       ├── .github/workflows/                # GitHub Actions
│       │   ├── ci.yml                       # Build, test, lint, Avro compat
│       │   ├── cd-staging.yml
│       │   └── cd-prod.yml
│       └── argocd/                          # ArgoCD app-of-apps
│
├── dev/                                     # Local dev only (not deployed)
│   ├── docker-compose.yml                   # Postgres + Kafka KRaft + ES + Redis + Apicurio
│   ├── docker-compose.dev.yml               # Adds Stripe webhook simulator, mailhog
│   ├── seed-data/
│   │   ├── catalog/                         # Seed scripts per Sprint
│   │   ├── inventory/
│   │   └── ...
│   ├── chaos/                               # Local chaos experiments
│   └── localstack/                          # S3 substitute for MinIO
│
├── docs/
│   ├── README.md                            # Docs index
│   ├── adr/                                 # Architecture Decision Records
│   │   ├── 0001-record-architecture-decisions.md
│   │   ├── 0002-saga-architecture-modulith.md
│   │   ├── 0003-event-driven-foundation.md
│   │   ├── 0004-rate-limiter-time-source-fix.md
│   │   ├── 0005-vietnamese-first.md
│   │   └── ...
│   ├── tutorials/
│   │   ├── 01-quick-start.md
│   │   ├── 02-running-saga-test.md
│   │   ├── 03-deploying-locally.md
│   │   └── 04-deploying-to-k8s.md
│   └── diagrams/                            # C4, sequence, state-machine (Mermaid)
│
└── helm/                                    # Helm charts (one per service)
    ├── catalog/
    ├── inventory/
    └── ...
```

### Architectural Boundaries

#### API Boundaries

| Boundary | Direction | Auth | Notes |
|---|---|---|---|
| Frontend → BFF | HTTPS | Session cookie + CSRF | Single BFF per surface (storefront, admin) |
| BFF → Service | HTTPS + mTLS | Service-account JWT | Resilience4j circuit breakers |
| Service → Service | HTTPS + mTLS | Service-account JWT | Few cases; mostly events |
| Service → Kafka | mTLS + SASL | Kafka credentials | Producer per service |
| Service → Postgres | TLS | DB credentials from Vault | Per-service DB |
| Service → Redis | TLS | Password | Per-namespace |
| Service → ES | TLS + basic auth | API key | Per-service index |
| Service → Apicurio | TLS | API key | Schema registration |
| Service → Stripe | HTTPS | API key from Vault | Stripe Elements iframe on frontend |
| Service → OTel collector | OTLP | mTLS | Standard OTel SDK |

#### Service Boundaries (intra-Modulith)

Within the single Modulith deployment, the 13 services are **logical modules** with enforced boundaries:

- Each module exposes a public API (Java interface in `application/` package).
- Cross-module access goes through the public API only; no direct entity or repository access.
- Spring Modulith's `@ApplicationModule` annotation enforces package visibility at runtime.
- The Modulith outbox bridge polls each module's `outbox` table and publishes events to Kafka.

#### Data Boundaries

- **Per-service database** (ADR-03). 13 databases, one per service. No cross-service joins.
- **Read-side projections** (Elasticsearch) are populated via CDC from Postgres; services query ES for read patterns.
- **Outbox table** is the bridge to Kafka. Writes to outbox + business state in the same transaction.
- **Cross-service aggregates** are referenced by Snowflake ID only; no foreign-key coupling across databases.

### Requirements-to-Structure Mapping

**FR → module mapping** (82 FRs):

| FR range | Module | Stories/responsibilities |
|---|---|---|
| FR-1 to FR-7 | `services/catalog/` | Product CRUD, variant graph, price-snapshot read, audit |
| FR-8 to FR-13 | `services/inventory/` | Per-warehouse ledger, reservation saga step, FOR UPDATE locks, soft-delete uniqueness |
| FR-14 to FR-18 | `services/cart/` | Anonymous cart, merge on login, optimistic concurrency, auto-expire |
| FR-19 to FR-23 | `services/checkout/` | Single-page checkout, Stripe PaymentIntent lifecycle, saga orchestrator |
| FR-24 to FR-29 | `services/payment/` | Stripe integration, idempotency keys, webhook handler, 3DS, PCI scope minimization |
| FR-30 to FR-34 | `services/order/` | Event-sourced order, price-snapshot, post-payment lifecycle, edit-after-pay |
| FR-35 to FR-39 | `services/fulfillment/` | Carrier-agnostic shipment, webhook-driven tracking, carrier-degraded UI |
| FR-40 to FR-44 | `services/returns/` | RMA workflow, exchange-first, partial returns, cumulative-refund safety |
| FR-45 to FR-50 | `services/customer/` | Customer aggregate, PDPD export, address book, right-to-be-forgotten |
| FR-51 to FR-55 | `services/search/` | ES index, Vietnamese diacritic search, faceted, recommendation stub |
| FR-56 to FR-60 | `services/notification/` | Event-driven notification, SendGrid wiring, user preferences |
| FR-61 to FR-64 | `services/admin/` | Next.js role-gated routes, audit trail, approval workflows |
| FR-65 to FR-68 | `services/pricing/` | List/sale/promotion, Stripe Coupons, multi-currency scaffolding |
| FR-69 to FR-72 | `services/returns/` | Reviews (verified-purchase, photo upload, helpful-vote) — note: services/returns/ vs separate; FR-69-72 could live in customer/reviews sub-module; see ADR-clarification in epics phase |
| FR-73 to FR-77 | `services/customer/` | Email/password auth, JWT, RBAC, MFA for staff, lockout, security events |
| FR-78 | `services/invoice/` | Vietnamese tax-invoice (Jasper + QR + serialized allocator) |
| FR-79 to FR-82 | Cross-cutting | PCI scope, PDPD, card-testing defense, CDC event injection defense — implemented in respective services per ADR-23/24/20 |

**ADR-clarification needed in epics phase:** FR-69 to FR-72 (Reviews) could live under `services/customer/reviews/` (sub-module of Customer) or as a sibling `services/reviews/` (separate bounded context). Default to `services/customer/reviews/` to honor the brainstorming "Customer + Auth" coupling pattern; revisit in epic-3 review.

**Cross-cutting concerns** (sourced from §6 NFRs):

| Concern | Module/file location |
|---|---|
| Idempotency-key strategy | `services/<each>/infrastructure/ProcessedEventRepository.java` |
| OTel instrumentation | `services/<each>/config/OtelConfig.java` + auto-instrumentation |
| OPA admission | `platform/policies/opa/*.rego` |
| Chaos experiments | `platform/chaos/chaos-mesh/*.yaml` |
| Outbox + Modulith bridge | `services/<each>/infrastructure/OutboxPublisher.java` + shared Modulith config |
| Avro schemas | `services/<each>/src/main/avro/*.avsc` + Apicurio compat CI |
| Idempotency-key strategy | `services/<each>/infrastructure/IdempotencyKey.java` |

### Integration Points

**Internal communication:**

- **Intra-Modulith (synchronous):** Java method calls between modules' public APIs. OTel trace context propagated via Spring's automatic instrumentation.
- **Intra-Modulith (asynchronous):** Spring's `@ApplicationModuleListener` for intra-JVM event delivery. Used when both producer and consumer are in the same Modulith deployment.
- **Cross-service (asynchronous):** Kafka via outbox. Producer writes to local outbox table; Modulith outbox bridge publishes; consumer subscribes via `@KafkaListener`.
- **Cross-service (synchronous, rare):** WebClient call from BFF to service, or from one service to another for live data queries (e.g., CheckoutService querying InventoryService for stock). Resilience4j circuit breaker.

**External integrations:**

| System | Direction | Trigger | Pattern |
|---|---|---|---|
| Stripe | BFF → Stripe.js; service → Stripe API | Payment authorization, capture, refund; webhook | Stripe Elements iframe; webhook dedup |
| SendGrid | Service → SendGrid | Notification events | util's Telegram-equivalent for email |
| GHN / GHTK / Viettel Post | Service → carrier API | Shipment creation, tracking | Adapter pattern; jittered retry |
| FCM (push) | Service → FCM | Notification events | Service-account JWT |
| Apicurio | Service → Apicurio | Schema registration; consumer compat check | REST + Registry Avro SDK |
| Vietnam tax authority | Batch job → portal | Daily | Quartz cron (util's scheduler) |

**Data flow example (placing an order):**

1. User clicks "Place Order" on storefront frontend.
2. Next.js storefront calls `POST /bff/storefront/checkout` with cart ID.
3. BFF authenticates session, calls `POST /services/checkout/start`.
4. CheckoutService creates `Checkout` aggregate, writes to outbox, returns checkout ID.
5. Modulith outbox bridge publishes `checkout.started` to Kafka.
6. Frontend redirects to Stripe Elements iframe; user enters card.
7. Stripe sends webhook to the **gateway's public URL** `POST https://api.example.com/webhooks/stripe` (the only public-internet endpoint exposed by the platform). The gateway authenticates the request by verifying Stripe's `Stripe-Signature` header using a Stripe webhook secret, applies rate limiting (per ADR-24 to defend against card-testing scrapers pretending to be webhooks), and forwards the verified payload to the **internal** `POST /services/payment/webhook` over mTLS. PaymentService never sees the raw public request — it always sees a gateway-validated, rate-limited, mTLS-encrypted payload.
8. PaymentService webhook handler dedupes on `event.id`, calls Stripe API to confirm/capture, writes payment aggregate + outbox `payment.captured`.
9. Outbox bridge publishes `payment.captured` to Kafka.
10. OrderService's `payment.captured` listener (intra-Modulith via `@ApplicationModuleListener`) updates the order aggregate, writes to outbox `order.placed`.
11. Outbox bridge publishes `order.placed` to Kafka.
12. NotificationService listener sends confirmation email.
13. Frontend polls `GET /bff/storefront/order/{id}` and shows confirmation page.

### File Organization Patterns (recap from §6 step-05)

- **Config:** `application.yml` (defaults), `application-{profile}.yml`, `bootstrap.yml` for Vault. **No `.env` in repo** (per ADR-18).
- **Source:** Maven module = one service. Each module has `api/`, `domain/`, `application/`, `infrastructure/`, `config/`.
- **Test:** co-located unit tests; `IT` suffix for Testcontainers integration; `ContractTest` for cross-service contracts; `e2e-tests/` module for end-to-end.
- **Assets:** `frontend/<surface>/public/` for static; `frontend/<surface>/src/app/` for App Router pages.
- **Avro schemas:** `services/<each>/src/main/avro/<Domain><Event>.avsc`. CI gate in `platform/ci-cd/`.

### Development Workflow Integration

- **Dev startup:** `cd dev && docker compose up -d` brings up Postgres, Kafka KRaft, ES, Redis, Apicurio, MinIO. `mvn -pl services/catalog -am spring-boot:run` runs one service.
- **Dev shortcuts:** `dev/seed-data/<service>/` contains idempotent seed scripts. `dev/localstack/` provides S3 substitute.
- **Build:** `mvn -pl services/<each> -am clean install` builds one service with its dependencies. `mvn clean install` at the root builds everything.
- **Test:** `mvn test` runs unit tests; `mvn verify` runs unit + integration (Testcontainers). CI runs both on every PR.
- **Lint:** Spotless (Java) + Prettier (TypeScript) run on every commit. Checkstyle (Java) + ESLint (TypeScript) block PRs with violations.
- **Deploy:** Helm chart per service (`helm/<service>/`). ArgoCD app-of-apps at `platform/ci-cd/argocd/`. Single command to roll forward or back.

---

## Architecture Validation Results

### Coherence Validation ✅

**Decision Compatibility:**

- All technology choices work together:
  - Java 25 + Spring Boot 4.0.0 + Spring Cloud 2025.1: confirmed pair (technical-research §1)
  - Spring Boot 4 + Spring Modulith (ADR-01): Modulith is core Spring, no version conflict
  - Apache Kafka 4 KRaft + Apicurio 2.6: schema registry integrates cleanly
  - Elasticsearch 8.x + Debezium 3 (if chosen): client/server version match enforced
  - Redis 7 + Lua scripts: standard pattern, no version conflict
  - Next.js 15 + React Server Components + Server Actions: framework-native
- All version pairs verified against the technical-research version matrix (addendum A4).
- No contradictory decisions. ADR-01 (Modulith) + ADR-14 (per-service outbox) cohere; ADR-15 (Avro strict) + ADR-04 (event-driven) cohere.

**Pattern Consistency:**

- Naming conventions: snake_case (DB), camelCase (Java), PascalCase (TS), snake_case (JSON wire) — consistently applied.
- Structure patterns: `api/domain/application/infrastructure/config` Java layers per service; consistent across all 13 modules.
- Communication patterns: Kafka events use envelope (event_id, event_type, occurred_at, payload, signatures); consistent across all event-emitting services.
- Process patterns: error handling via util's `GlobalExceptionHandler`; validation via Jakarta Bean Validation; auth via mTLS + JWT — consistent.

**Structure Alignment:**

- Project structure supports all architectural decisions:
  - `services/<each>/` matches the 13-service per-domain breakdown (ADR-02).
  - `bff/<surface>-bff/` matches the API style (ADR-09).
  - `platform/observability/`, `platform/policies/`, `platform/chaos/` match NFR-OBS / NFR-SEC / chaos requirements.
  - `helm/<service>/` per-service deployability; `argocd/` for GitOps.
  - `util/` reuses the existing shared library; the parent-pom blocker (R-01) is the only structural gap.

### Requirements Coverage Validation ✅

**FR Coverage (82 FRs verified):**

| FR Domain | Module | Architectural support |
|---|---|---|
| FR-1 to FR-7 (Catalog) | services/catalog/ | ✓ |
| FR-8 to FR-13 (Inventory) | services/inventory/ | ✓ (FR-9 specifically implements DI-01 root cause) |
| FR-14 to FR-18 (Cart) | services/cart/ | ✓ |
| FR-19 to FR-23 (Checkout) | services/checkout/ | ✓ (FR-22 binds ADR-01) |
| FR-24 to FR-29 (Payment) | services/payment/ | ✓ (FR-25/26 implement DI-02 + R-15) |
| FR-30 to FR-34 (Order) | services/order/ | ✓ |
| FR-35 to FR-39 (Fulfillment) | services/fulfillment/ | ✓ |
| FR-40 to FR-44 (Returns) | services/returns/ | ✓ (FR-43 implements DI-07 root cause) |
| FR-45 to FR-50 (Customer) | services/customer/ | ✓ (FR-46 implements LC-01) |
| FR-51 to FR-55 (Search) | services/search/ | ✓ (FR-52 implements UX-05 / R-07) |
| FR-56 to FR-60 (Notification) | services/notification/ | ✓ |
| FR-61 to FR-64 (Admin) | services/admin/ | ✓ |
| FR-65 to FR-68 (Pricing) | services/pricing/ | ✓ (multi-currency + tiered B2B are P2 non-goal markers) |
| FR-69 to FR-72 (Reviews) | services/customer/reviews/ | ✓ (sub-module, see ADR-clarification in step-06) |
| FR-73 to FR-77 (Auth) | services/customer/ | ✓ |
| FR-78 to FR-82 (Compliance) | services/invoice/ + cross-cutting | ✓ (FR-78 implements LC-03; FR-79 implements R-15; FR-80 implements LC-01; FR-81 implements AT-01; FR-82 implements AT-03) |

**NFR Coverage (27 NFRs verified):**

| NFR Category | NFRs | Architectural support |
|---|---|---|
| NFR-PERF (5) | catalog p99, search p99, hot-product, cache stampede, JDBC pool | services + Redis + ES + OTel |
| NFR-IDEM (3) | event-id-keyed, payment keys, cart merge | processed_event + idempotency-key strategy (ADR-11) |
| NFR-AVAIL (4) | 99.9% SLO, lag alerts, circuit breakers, fail-open | Resilience4j + Prometheus + OPA |
| NFR-OBS (5) | OTel+LGTM, cardinality, span drop, log scrubber | platform/observability/ + OTel SDK config |
| NFR-SEC (4) | trust boundary, mTLS+HMAC, OPA, Vault | ADR-20, ADR-19, ADR-18 |
| NFR-MIG (3) | expand-then-contract, Avro compat CI, ES alias swap | Flyway + Apicurio CI gate + ES alias |
| NFR-I18N (3) | display/charge rounding, locale formatting, RTL config | util/DateTimeUtil + ICU collator + config flag |

**Coverage verdict:** All 82 FRs and 27 NFRs are architecturally supported. No gaps.

### Implementation Readiness Validation ✅

**Decision Completeness:**

- 26 ADRs documented (ADR-01 to ADR-26), each with rationale, version, and effect.
- Implementation patterns comprehensive across naming, structure, format, communication, process.
- Consistency rules clear and enforceable (CI gates + archunit + code-review checklist).
- Examples provided for major patterns (good Catalog event emitter + Order payment handler; anti-pattern for comparison).

**Structure Completeness:**

- Complete project tree defined (services, BFF, frontend, platform, dev, helm, docs).
- All files and directories named with their purpose.
- Integration points clearly specified (table in step-06).
- Component boundaries well-defined (per-service DB, per-module outbox, intra-Modulith public APIs).

**Pattern Completeness:**

- Naming: DB / Java / TS / API / Events — comprehensive.
- Communication: events / state updates / logging / errors / retries — comprehensive.
- Process: validation / auth / loading / error handling — comprehensive.

#### Critical-Risk-to-ADR Binding Table

| Risk ID | Risk | Binding ADR(s) | Where implemented |
|---|---|---|---|
| R-01 | util/ parent pom missing | (operational, Sprint 0 fix) | `util/pom.xml` parent replacement; §7.1 hard technical constraint |
| R-02 | Inventory oversell race | ADR-12 (saga state FOR UPDATE via inventory service) | `services/inventory/` FR-9 |
| R-03 | Payment double-capture | ADR-11 (idempotency key) + ADR-21 (webhook dedup) | `services/payment/` FR-25, FR-26 |
| R-04 | Debezium outbox duplicates | ADR-01 (Modulith outbox default) + ADR-14 (Modulith outbox bridge) | No Debezium in v1 |
| R-05 | Card-testing attack | ADR-13 (rate-limiter Lua fix) + ADR-24 (multi-key rate limit) | gateway + `services/payment/` FR-81 |
| R-06 | Vietnamese tax-invoice compliance | ADR-26 | `services/invoice/` FR-78 |
| R-15 | PCI scope creep | ADR-23 (Stripe Elements + log redaction) | `services/payment/` FR-29 |

The remaining 8 risks (R-07, R-08, R-09, R-10, R-11, R-12, R-13, R-14) are tracked in the brainstorming register with their full mitigations and are not blockers for architecture phase.

### Gap Analysis Results

**Critical Gaps:** **None.** All hard blockers are tracked in the risk register and assigned a Sprint 0 owner.

**Important Gaps (tracked, not blocking):**

1. **R-01 (util/ parent pom missing).** Must be fixed in Sprint 0 story 0.1. Architecture is clear on the two fix options (vendor parent OR inline dependencyManagement).
2. **Service split path.** ADR-01 picks Modulith for v1; v2 split is a planned evolution but not designed in detail. The epics phase should include a "Modulith module extraction playbook" story to capture the pattern.
3. ~~**Q5 — Vietnamese tax-authority registration specifics.** Accountant input needed; Sprint 9 story owner should be flagged.~~ **RESOLVED** by ADR-26 (merchant-credentials schema) + Story 9.2b (accountant-input-collection ceremony). Tracked in §"Open Questions — Resolved by Architecture" above.
4. **Capacity validation.** Addendum A3 lists 50k orders/day as `[ASSUMPTION]`; load testing in architecture phase confirms or refutes.

**Nice-to-Have Gaps:**

- Tutorial for "How to read this architecture doc" (UJ-4 Linh-educator journey) — Sprint 10.
- BFF caching policy details (TTL, key strategy) — defer to BFF story.
- Chaos experiment library beyond the 6 P0 risks — Sprint 10+.

### Architecture Completeness Checklist

**Requirements Analysis**
- [x] Project context thoroughly analyzed
- [x] Scale and complexity assessed
- [x] Technical constraints identified
- [x] Cross-cutting concerns mapped

**Architectural Decisions**
- [x] Critical decisions documented with versions (26 ADRs)
- [x] Technology stack fully specified
- [x] Integration patterns defined
- [x] Performance considerations addressed (NFR-PERF + ADR-13 Lua fix)

**Implementation Patterns**
- [x] Naming conventions established
- [x] Structure patterns defined
- [x] Communication patterns specified
- [x] Process patterns documented

**Project Structure**
- [x] Complete directory structure defined
- [x] Component boundaries established
- [x] Integration points mapped
- [x] Requirements to structure mapping complete (FR → module table)

### Architecture Readiness Assessment

**Overall Status:** **READY FOR IMPLEMENTATION** (with tracked R-01 as Sprint 0 prerequisite)

**Confidence Level:** **High**

**Key Strengths:**

- **Q1 binding decision is clear** (Modulith outbox, ADR-01). No ambiguity for epics phase.
- **All 82 FRs and 27 NFRs are architecturally supported** with explicit FR→module mapping.
- **13 services mapped to 13 modules** in a Modulith deployment; reversibility path to microservice split is documented (ADR-01 reversibility note).
- **All 15 brainstormed risks have architectural mitigations** (full table in `brainstorming-session-2026-07-06-1119.md`; PRD addendum A1 mirrors it); 5 Critical risks (R-01, R-02, R-03, R-04, R-05, R-06, R-15) have explicit binding constraints in this architecture document (PRD §7.1 hard technical constraints + ADR binding via the table above). The architecture does NOT duplicate the full 15-entry risk table; it binds Critical risks via ADRs and relies on the source artifacts for completeness.
- **util/ mandatory reuse** is documented with R-01 fix path.
- **Vietnamese-first is concrete**: Jasper templates, QR code, address hierarchy, diacritic search, tax-invoice, locale formatting.
- **Patterns are agent-enforceable** (CI gates, archunit, code-review checklist).

**Areas for Future Enhancement:**

- BFF caching policy details (Sprint 2).
- v2 microservice split playbook (when needed).
- Load-test-driven capacity validation (architecture phase).
- Modulith-to-microservice split path implementation guide (when load demands it).

### Implementation Handoff

**AI Agent Guidelines:**

- Follow all 26 ADRs exactly as documented in §"Core Architectural Decisions."
- Use implementation patterns consistently across all components (per §"Implementation Patterns & Consistency Rules").
- Respect project structure and boundaries (per §"Project Structure & Boundaries" — `api/`, `domain/`, `application/`, `infrastructure/`, `config/` per service).
- Refer to this document (`_bmad-output/planning-artifacts/architecture.md`) and its addenda (PRD addendum, brainstorming session) for all architectural questions.

**First Implementation Priority:**

Sprint 0, story 0.1 — **Fix util/ parent pom blocker (R-01)**. The two options:

- **Option A:** Vendor the `vn.vnpt:be` parent pom at the project root, then add `<modules>` to it including `util/` and the future service modules.
- **Option B:** Replace `<parent>` in `util/pom.xml` with inline `<dependencyManagement>` referencing the Spring Boot 4.0.0 BOM directly.

**Recommendation:** Option B (inline dependencyManagement) is faster and lower-risk for v1; Option A is the right long-term move but requires the parent pom to be vendored into the repo first.

Sprint 0, story 0.2 — Bootstrap the reference monorepo per the project tree in §"Project Structure & Boundaries."
