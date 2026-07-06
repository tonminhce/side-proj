---
title: 'PRD — Ecommerce Reference Implementation (side-project)'
status: final
created: 2026-07-06
updated: 2026-07-06
authoring_mode: fast-path
intent: create
project: side-project
reviewStatus: 'q-status-sync-complete'
reviewCycle: 4
---

# PRD — Ecommerce Reference Implementation

> **Status:** Draft (Fast-path). Generated 2026-07-06. Will be marked `status: final` after user confirmation.
>
> **Run folder:** `_bmad-output/planning-artifacts/prds/prd-side-project-2026-07-06/`
> **Decision log:** `_bmad-output/planning-artifacts/prds/prd-side-project-2026-07-06/.decision-log.md`
> **Source artifacts:** brainstorming session (189 ideas, 10/10 quality), 3 research files, 10 local-docs.

---

## 1. Vision and Why Now

A production-grade, event-driven microservice ecommerce platform built as a **reference implementation** for senior Java developers, architects, platform/SRE teams, and educators. It showcases how to assemble modern, observable, compliance-aware microservice patterns on a current, supported stack — without inheriting the dead-weight decisions of legacy Java ecommerce projects.

**Why now:**

- The Java ecosystem has **no actively maintained, Spring Boot 4 / Kafka KRaft / Debezium / Elasticsearch 8 / Redis / OpenTelemetry reference ecommerce** in 2025–2026 (per market research). The closest competitor (Shopizer) is on a legacy 2022 branch with its v2.0 microservice rewrite still pending; Broadleaf has shifted to a source-available commercial license. The reference niche is **open**.
- Spring Boot 4.0.0 went GA on **10 June 2026**. Spring Cloud 2025.1 ("Oakwood") pairs with it. A reference that demonstrates the *new* baseline (records, sealed types, pattern matching, virtual threads, structured concurrency) is needed now — before the field's accumulated how-tos rot.
- **MACH/composable, headless, event-driven + CDC, AI-agent-friendly** are the dominant 2025–2026 commerce trends. This stack covers all four out of the box.
- **Vietnam-first** is a defensible niche: VN ecommerce is large and growing, but the Java reference shelf is empty there. Vietnamese-language support (diacritic-tolerant search, tax-invoice compliance, address hierarchy, locale-formatted currency) is a competitive moat, and the user's `util/` library already ships Vietnamese fonts and geography DTOs.

**What this is not:** a commercial product. No sales motion, no merchant onboarding, no payment-processor choice beyond Stripe.

## 2. Jobs to Be Done

When engineers reach for a reference ecommerce platform, they want answers to questions like:

- *JTBD-1: Pattern grounding.* "How do I assemble Spring Boot 4 + Kafka KRaft + Debezium outbox in a way that doesn't blow up under real load?" — Provide a working monorepo with all the wiring done, so the reader can clone, run `./mvn verify`, and see it move.
- *JTBD-2: Failure-mode literacy.* "Where do these stacks actually break? What does an oversell race look like in code? How do I detect duplicate Kafka deliveries?" — Surface 13 deep-drilled root causes from the brainstorming session, with concrete fix sketches in the architecture phase.
- *JTBD-3: Compliance onboarding.* "What does Vietnamese PDPD + tax-invoice + PCI-DSS look like in Spring?" — Deliver working integrations (Stripe Elements, Jasper-rendered invoices with QR codes, PDPD export endpoint) rather than a checklist.
- *JTBD-4: Reusability.* "What can I lift wholesale into my own greenfield?" — Publish a `util/`-style shared library (already exists) plus the service-level code, with a clear copy-paste path.
- *JTBD-5: Educational clarity.* "Can my team use this in onboarding?" — Diagrams + tutorials that map every ADR to the code that implements it.

The reference succeeds when a senior engineer can fork it, delete the parts they don't want, and still trust the parts they keep.

## 3. User Journeys (engineering)

> **Note:** Per BMad convention, engineering personas (An, Bao, Mai, Linh) are captured inline at the moments that matter, not in a standalone section. The audience is operators and builders, not shoppers.

### UJ-1: An the architect evaluates the stack for a new project

An is a staff engineer at a Series-B SaaS company. She is considering Kafka + Debezium for a new B2B catalog service. She lands on the GitHub repo, scrolls the README, looks for "what runs where," and finds a sequence diagram of the checkout flow. She follows it: catalog → cart → checkout (saga) → payment → order. She reads the `docs/adr/` directory and sees the Spring Modulith-outbox-vs-Debezium ADR. She runs `docker compose up` and the system comes up. She clones the saga test suite and adapts it to her domain. Two hours, she's prototyped.

**Engineering context inline:** senior engineer; values traceable decisions; expects integration tests not just unit tests; suspicious of "magic."

### UJ-2: Bao the senior Java dev onboards a new team

Bao is leading a 5-person team picking up the reference as the basis for an internal commerce platform. He runs through the Quick Start, hits a docker-compose port conflict on Kafka, opens an issue, and gets a working fix within hours because the project has a "Good First Issue" label and the contribution guide explains the local dev loop. He walks his team through Sprint 1 of the brainstorming session (catalog + inventory) as a tutorial. His team owns the `CatalogService` and `InventoryService` as their first stories, mirroring the reference implementation. After 3 sprints, his team has shipped a working catalog that the rest of the platform can integrate with.

**Engineering context inline:** team lead; needs predictable on-ramp; values working tests over documentation; expects the reference to demonstrate good engineering hygiene (lint, type checks, security advisories).

### UJ-3: Mai the SRE keeps the reference healthy in production

Mai operates the reference at a customer site running 50k orders/day. She needs: clear SLI/SLO definitions, runbooks for each P0 risk (R-01 through R-15), alert thresholds tied to user-impacting symptoms (not internal metrics), and a chaos test suite she can run on a Friday afternoon. The reference ships: a Grafana dashboard bundle (provisioned from Git, not imported by hand), a `chaos/` directory with Litmus/Chaos Mesh experiments aligned to each P0 risk, a `runbook/` directory with one Markdown per alert, and `policies/` with OPA/Rego rules for admission control (e.g., every Kafka topic must have a retention policy declared in code).

**Engineering context inline:** ops engineer; values runnable, observable, reversible changes; expects chaos tests and game-days; trusts dashboards that come from Git.

### UJ-4: Linh the educator uses the reference in a course

Linh teaches a graduate course on distributed systems. She needs reproducible demos. The reference's `dev/seed-data` and `dev/chaos` modules let her run an in-class drill: "Here is the catalog running normally. Now I kill the Kafka broker." Students watch the saga compensate, the dashboard alert fire, and the order replay correctly. The reference is the textbook; Linh's slides reference specific ADRs and root-cause deep-dives.

**Engineering context inline:** educator; needs stable, reproducible demos; values the link between code and ADR; needs the project to *teach* its own decisions, not just ship them.

## 4. Functional Requirements

Grouped by domain per `domain-research.md` (13 services) and the brainstorming P0 list. IDs are stable (FR-N); cross-cutting NFRs in §6.

### 4.1 Catalog (FR-1 to FR-7)

- **FR-1.** Maintain a product catalog with product → option → variant graph. Each variant has a SKU generated as a hash of its attribute combination (per brainstorming `[CAT-S]`).
- **FR-2.** Each variant carries: SKU, display name, attributes (color, size, etc.), price (list + sale), image set, weight, dimensions, isActive flag.
- **FR-3.** Price changes do not retroactively affect existing orders. Each order stores a JSONB `priceSnapshot` (immutable per brainstorming `[PRC-S]`).
- **FR-4.** Product attributes stored as JSONB columns; new attributes do not require schema migration (brainstorming `[CAT-M]`).
- **FR-5.** Catalog changes publish `catalog.*.lifecycle` events (created, updated, deleted, price-changed) with backward/forward Avro-compatibility enforced in CI.
- **FR-6.** CatalogService owns its Postgres database; reads are served primarily from Elasticsearch via CDC propagation (acceptable drift 95% < 5s, alert at 30s).
- **FR-7.** Admin UI (Next.js, role-gated) provides CRUD over catalog; every mutation logs to an immutable `audit_trail` table (brainstorming ADM-P).

### 4.2 Inventory (FR-8 to FR-13)

- **FR-8.** Per-warehouse stock movement is a double-entry ledger; `on_hand` is a sum-derivation, not a mutable field (brainstorming `[INV-S]`).
- **FR-9.** Reservation with TTL: `inventory.reserve()` runs inside a Postgres transaction using `SELECT ... FOR UPDATE`; reservations auto-expire after a configurable TTL (default 15 min) and a sweeper job emits `inventory.released` events (brainstorming `[INV-M]`).
- **FR-10.** Multi-warehouse support from day one — each variant has per-warehouse `on_hand`; reservation picks the closest warehouse to the shipping address (per `R-02` mitigation in brainstorming).
- **FR-11.** Inventory emits `inventory.reserved`, `inventory.released`, `inventory.allocated`, `inventory.shipped`, `inventory.adjusted` events.
- **FR-12.** Soft-delete uniqueness enforced via util's `@SoftUk` annotation; CI gate that fails if a new entity uses soft-delete without `@SoftUk` (brainstorming DI-09).
- **FR-13.** InventoryService is the *only* service that writes to `inventory_ledger`; CDC propagates read-side projections.

### 4.3 Cart (FR-14 to FR-18)

- **FR-14.** Anonymous cart via cookie-bound UUID; merge on login via `cart.merged` event (idempotent on `(guest_cart_id, user_id)` — brainstorming `[CART-S]`).
- **FR-15.** Cart line carries `sellerId` (null in B2C; populated in marketplace v2) and `variantId`; cart total computed on read.
- **FR-16.** Optimistic concurrency: `cart.version` field; concurrent edits return 409 with the latest state.
- **FR-17.** Cart `line.added` event feeds real-time recommendation service.
- **FR-18.** Cart entries auto-expire after 30 days; sweep job emits `cart.expired`.

### 4.4 Checkout (FR-19 to FR-23)

- **FR-19.** Single-page checkout: one route, one POST. All steps (address, shipping, payment, review) live in one component (per brainstorming `[CHK-S]`; Baymard data: single-page cuts drop-off 10–25%).
- **FR-20.** CheckoutService owns the Stripe PaymentIntent lifecycle (create, update, confirm, capture) directly — no separate PaymentService hop (brainstorming `[CHK-C]`).
- **FR-21.** CheckoutService exposes `POST /checkout/start` returning a `checkoutId`; client polls `GET /checkout/{id}` for status. On success, client is redirected to the order page.
- **FR-22.** Saga orchestrator is the v1 implementation. Two candidate patterns are eligible:
  - **Default (Green-Hat):** Spring Modulith outbox + `@ApplicationModule` boundaries.
  - **Alternate:** Spring Statemachine + Debezium outbox + Kafka transactions (carries Critical residual risk R-04).
  - **Final selection deferred to architecture phase** (Open Question Q1). This PRD does not lock the choice. The architecture-phase decision is the binding FR for v1; the candidate names are preserved here for traceability.
- **FR-23.** Saga compensations emit `checkout.compensated` events with idempotency keys; saga state is recoverable after a crash.

### 4.5 Payment (FR-24 to FR-29)

- **FR-24.** Stripe-only integration in v1. Defer Adyen/Braintree to v2 (brainstorming `[PAY-S]`).
- **FR-25.** Every payment operation has a stable idempotency key derived from `(order_id, saga_step_name)` — not per-retry (per brainstorming `[PAY-A]` / `DI-02` root cause).
- **FR-26.** Webhook handler dedupes on Stripe `event.id` via a `webhook_dedup` table; handler is idempotent under redelivery (brainstorming `[PAY-A]`).
- **FR-27.** 3DS step-up only for risk-flagged transactions; SCA exemption logic per EU/UK PSD2.
- **FR-28.** PaymentService emits `payment.captured`, `payment.refunded`, `payment.failed`, `payment.disputed` events.
- **FR-29.** PCI scope: Stripe Elements iframe only; PAN never touches our servers; OpenTelemetry log redaction matches any field with `\d{13,19}`; lint rule denies any request-body logger by default (per `R-15` mitigation).

### 4.6 Order (FR-30 to FR-34)

- **FR-30.** Order aggregate is **append-only event log**; current state is a projection. Replayable for debugging (brainstorming `[ORD-S]`).
- **FR-31.** `order.priceSnapshot` is a JSONB column capturing list price, applied promotion(s), tax, shipping at order time; immutable after order.placed.
- **FR-32.** OrderService owns post-payment lifecycle: allocation → picking → packing → shipping → delivery.
- **FR-33.** `order.timeline` endpoint returns a single user-visible timeline (placed → paid → packed → shipped → delivered) for the support flow (brainstorming `[ORD-A]`).
- **FR-34.** Edit-after-pay: address edits and cancel allowed within a configurable TTL (default 30 min after `order.placed`); locks via `order.version`; emits `order.amended` events.

### 4.7 Fulfillment (FR-35 to FR-39)

- **FR-35.** Carrier-agnostic `ShipmentService` interface; adapters for GHN, GHTK, Viettel Post, DHL, FedEx (adapter pattern; switch without code change).
- **FR-36.** Tracking updates polled via webhook; emits `shipment.dispatched`, `shipment.in_transit`, `shipment.out_for_delivery`, `shipment.delivered`, `shipment.exception` events.
- **FR-37.** Estimated delivery window computed at checkout using carrier SLA + warehouse distance.
- **FR-38.** Carrier-degraded UI state when adapter fails health check; user sees "delivery may be delayed" message; checkout still completes.
- **FR-39.** Fulfillment pollers use jittered retry; no thundering-herd on carrier API recovery.

### 4.8 Returns (RMA) (FR-40 to FR-44)

- **FR-40.** Reason taxonomy (defective, wrong-item, no-longer-needed, etc.); per-reason refund policy.
- **FR-41.** Default flow is **exchange-first** before refund (brainstorming `[RET-R]`); user opts into refund path explicitly.
- **FR-42.** Partial returns supported: return N of M items, prorated refund (brainstorming `[RET-M]`).
- **FR-43.** Cumulative-refund safety: refund issuance runs `SELECT SUM(amount_cents) FROM refund WHERE order_id = ? FOR UPDATE` and aborts if total > order amount (per `DI-07` root cause).
- **FR-44.** RMA reason + photo evidence → quality dashboard (brainstorming `[RET-P]`).

### 4.9 Customer + Address (FR-45 to FR-50)

- **FR-45.** Customer aggregate is separate from auth User aggregate. Linked by `customer.userId` (B2B future-proof; brainstorming `[CUS-S]`).
- **FR-46.** Customer profile is GDPR/PDPD-exportable: a `customer_data_registry` table lists (service, table, columns, format) per data category; export job joins via customer aggregate ID and emits a single JSON/ZIP bundle (per `LC-01` root cause).
- **FR-47.** Address book uses util's existing `ProvinceDto` / `DistrictDto` / `CommuneDto` for Vietnamese address hierarchy.
- **FR-48.** Address autocomplete UI uses Elasticsearch geo-index tuned to Vietnamese address.
- **FR-49.** Right-to-be-forgotten: hard-delete PII + anonymize order history (per LC-04).
- **FR-50.** Loyalty points accrue per order; redeemable as discount line items at checkout (P1 feature; brainstorming `[CUS-M]`).

### 4.10 Search & Recommendation (FR-51 to FR-55)

- **FR-51.** Elasticsearch 8.x primary search index; per-locale index naming (`catalog_vi`, `catalog_en`).
- **FR-52.** Vietnamese diacritic-tolerant search: tokenizer applies diacritic folding + phonetic fallback (per `UX-05` / `R-07`).
- **FR-53.** Faceted search: category, brand, price range, color, size (Elasticsearch aggregations).
- **FR-54.** Recommendations v1: "popular in category" + "frequently bought together" — rule-based, no ML. ML deferred to P2.
- **FR-55.** Search auto-complete via single service with both query and prefix-suggest; one ranking model.

### 4.11 Notification (FR-56 to FR-60)

- **FR-56.** Event-driven: NotificationService subscribes to all `*.lifecycle` events; templates per channel.
- **FR-57.** Channels: email (SendGrid, already wired in util), push (FCM). SMS deferred to P2.
- **FR-58.** User preferences: per-channel opt-in/out, quiet hours, digest mode.
- **FR-59.** Templates are SendGrid dynamic templates; marketing-team editable without code change.
- **FR-60.** Locale picked from customer profile; templates per locale.

### 4.12 Admin (FR-61 to FR-64)

- **FR-61.** Admin UI is the Next.js storefront with role-gated routes (`/admin/*`) — no separate Thymeleaf admin (brainstorming ADM-S).
- **FR-62.** Read-first delivery in Sprint 8: catalog view, order view, customer view. Write/edit rolled in Sprint 8b.
- **FR-63.** Every admin mutation logs to immutable `audit_trail` with actor ID, action, before/after diff (brainstorming ADM-P).
- **FR-64.** Approval workflows for high-impact changes: price changes, promotions, refunds > threshold (brainstorming `[ADM-M]`).

### 4.13 Pricing & Promotion (FR-65 to FR-68)

- **FR-65.** Single PricingService owns list price, sale price, promotion discount (brainstorming `[PRC-C]`).
- **FR-66.** Stripe Coupons for v1: codes, % off, fixed amount, BOGO, first-time-buyer. One model, no tiered B2B pricing.
- **FR-67.** Multi-currency display via static FX rate (not real-time feed); settlement in base currency (VND). Multi-currency beyond VND is an explicit **non-goal for v1** (P2 marker).
- **FR-68.** Pricing events emitted for analytics: `pricing.changed`, `pricing.promotion_applied`.

### 4.14 Reviews & Ratings (FR-69 to FR-72)

- **FR-69.** Verified-purchase only: review requires `order.id` reference. Unverified reviews opt-in by user choice.
- **FR-70.** Photo upload as evidence; Q&A entity shares the same table with `type` discriminator (`review` vs `question`) (brainstorming `[REV-C]`).
- **FR-71.** Helpful-vote ranking: weighted by reviewer tenure (brainstorming `[REV-M]`).
- **FR-72.** Burst-of-5-star pattern detection → fraud queue (brainstorming `[REV-P]`).

### 4.15 Identity & Auth (FR-73 to FR-77)

- **FR-73.** Email + password primary auth; magic-link (passwordless) optional. Social login (Google/Facebook) deferred to P2 (brainstorming CUS-E).
- **FR-74.** Util's existing `CustomSecurityExpressionHandler` + `ICodeJwtGrantedAuthoritiesConvertor` provide RBAC; roles: `customer`, `staff`, `admin`, `service-account` (brainstorming `[AUT-A]`).
- **FR-75.** MFA TOTP mandatory for `staff` and `admin`; optional for `customer` (deferred to P2).
- **FR-76.** Session timeout, account lockout (after N failed logins), CAPTCHA on suspicious bursts (brainstorming `[AT-02]`).
- **FR-77.** Auth events emit to a security-events topic: `login.success`, `login.failure`, `account.locked`, `mfa.challenge` (brainstorming `[AUT-P]`).

### 4.16 Compliance (FR-78 to FR-82)

- **FR-78.** **Vietnamese tax-invoice compliance:** InvoiceService issues serialized invoices per Vietnam tax-authority registration. Number allocator, Jasper template (util's Vietnamese fonts already shipped), QR code (util's `QRCodeUtil`), daily batch job to publish invoice register (per `LC-03` root cause).
- **FR-79.** **PCI-DSS scope:** Stripe Elements iframe-only; no PAN logging; OTel log-redaction matches `\d{13,19}`; default request-body logger is deny-listed (per `R-15`).
- **FR-80.** **Vietnam PDPD:** customer-data export endpoint; consent capture; right-to-be-forgotten (per `LC-01` root cause).
- **FR-81.** Card testing defense: gateway rate-limiter keys on `IP + card-fingerprint + ASN`; BIN velocity check across all users (per `AT-01` root cause).
- **FR-82.** CDC event injection defense: mTLS between Kafka and services; per-service HMAC-signed event headers; Avro schema strict evolution; consumer re-verifies signature (per `AT-03` root cause).

## 5. Out of Scope (v1)

Per brainstorming "What we did NOT explore" + P2 non-goal markers:

- **Multi-currency beyond VND** (P2 marker — see brainstorm `[PRC-M]` / I18N-01).
- **Tiered B2B pricing** (P2 marker — see brainstorm `[PRC-E]`).
- **Marketplace seller tenancy** (brainstorm "B2C vs marketplace"; deferred to v2).
- **Native mobile apps** (Next.js web + responsive only).
- **Social login** (Google/Facebook — brainstorming CUS-E).
- **SMS notifications** (brainstorming NOT-E).
- **MFA for customer accounts** (brainstorming AUT-M, deferred).
- **ML recommendations** (brainstorming SRH-E).
- **ML product discovery (LLM-assisted)** (brainstorming SRH-R).
- **Pre-order / made-to-order** (brainstorming INV-R).
- **Click-and-collect** (brainstorming FUL-R).
- **Custom CMS for marketing content** (brainstorming ADM-E).
- **Returns self-service portal** (brainstorming FUL-E; admin-assisted in v1).
- **Inventory batch/lot/serial tracking** (brainstorming INV-E).
- **A/B testing framework** (P2 nice).
- **Auction/bid mode** (brainstorming PRC-R).
- **Image-search via CLIP** (brainstorming CAT-R).
- **Helpdesk integration, agent tooling, refund-workflow UI** (not designed; admin + email covers v1).
- **Marketing automation** (abandoned-cart, segmentation — not in v1).

## 6. Cross-Cutting NFRs

### 6.1 Performance

- **NFR-PERF-1.** Catalog read p99 latency < 100ms (served from Elasticsearch).
- **NFR-PERF-2.** Search query p99 latency < 300ms.
- **NFR-PERF-3.** Hot-product thundering-herd protection: per-SKU request coalescing + cached stock counter with short TTL fed by CDC (per `PERF-01`).
- **NFR-PERF-4.** Cache stampede defense: single-flight refresh + jittered TTL on category pages (per `PERF-02`).
- **NFR-PERF-5.** Separate JDBC pool for CDC; long-running snapshots use REPEATABLE READ isolation (per `PERF-03`).

### 6.2 Idempotency

- **NFR-IDEM-1.** All event consumers are idempotent on `event.id` (or `event.id` + step-name for saga); backed by `processed_event` table.
- **NFR-IDEM-2.** All payment operations use stable idempotency keys derived from `(order_id, saga_step_name)`.
- **NFR-IDEM-3.** Cart merge is idempotent on `(guest_cart_id, user_id)`.

### 6.3 Availability and SLOs

- **NFR-AVAIL-1.** Target 99.9% availability for checkout + payment (3 nines).
- **NFR-AVAIL-2.** Kafka consumer lag alert: p95 lag > 30s triggers warning; > 2min triggers page.
- **NFR-AVAIL-3.** Circuit breakers on all cross-service HTTP calls (Resilience4j defaults per technical-research).
- **NFR-AVAIL-4.** Rate-limiter fail-open policy: explicit — fail open with metric + alert (per `OP-04`); default fail-closed on payment endpoints.

### 6.4 Observability

- **NFR-OBS-1.** OpenTelemetry traces across all services; W3C trace context propagated via Kafka headers.
- **NFR-OBS-2.** Metrics via Prometheus; logs via Loki; traces via Tempo. Dashboards provisioned from Git (per UJ-3).
- **NFR-OBS-3.** Metric label cardinality bounded: per-customer-id labels rejected at collector boundary (per `OBS-01`).
- **NFR-OBS-4.** Span drop counter metric on OTel batch processor; never silent drop (per `OBS-02`).
- **NFR-OBS-5.** Loki log fields structured, not concatenated; log-scrubber strips any `stripe_customer_id` patterns from message bodies (per `OBS-03`).

### 6.5 Security

- **NFR-SEC-1.** All services behind gateway; trust-boundary: gateway owns `X-Real-IP` from trusted hops.
- **NFR-SEC-2.** mTLS between Kafka and services; per-service HMAC-signed event headers (per `AT-03`).
- **NFR-SEC-3.** OPA/Rego admission policies for Kafka topic creation, schema registration, JDBC pool sizing.
- **NFR-SEC-4.** Secrets in HashiCorp Vault; no `.env` files in repo; rotation runbook.

### 6.6 Backwards Compatibility and Migrations

- **NFR-MIG-1.** Postgres schema migrations: expand-then-contract; large tables via `pg_repack` (per `MIG-01`).
- **NFR-MIG-2.** Avro schema evolution: backward + forward compatibility enforced in Apicurio; CI gate fails on incompatible PRs (per `MIG-02`).
- **NFR-MIG-3.** Elasticsearch index mapping changes: alias-swap pattern with dual-write (per `MIG-03`).

### 6.7 Internationalization Beyond VN (scaffolding only)

- **NFR-I18N-1.** Display-currency rounding rule separate from charge-currency rounding (per `I18N-01`).
- **NFR-I18N-2.** Date/number formatting via `DateTimeFormatterBuilder` with `Locale` parameter (replaces util's `createdAtFormatted` hard-coded `dd/MM/yyyy`).
- **NFR-I18N-3.** RTL-readiness config flag; ICU collator for name sort (deferred unless i18n is selected).

## 7. Constraints and Guardrails

### 7.1 Hard technical constraints

- **Java 25** (LTS). Records, sealed types, pattern matching, virtual threads, structured concurrency are first-class.
- **Spring Boot 4.0.0** (GA 10 Jun 2026) + Spring Cloud 2025.1 "Oakwood".
- **Apache Kafka 4** in KRaft mode (no Zookeeper).
- **Debezium 3** for CDC OR **Spring Modulith outbox** (default — see Open Question Q1).
- **Apicurio Registry 2.6** with Avro.
- **Elasticsearch 8.x** as primary search index.
- **Redis 7** with centralized token-bucket rate limiting.
- **PostgreSQL 16+** (or latest LTS at install time) as the system-of-record per service.
- **OpenTelemetry, Prometheus, Grafana, Loki, Tempo** as observability stack (LGTM).
- **util/** shared library is mandatory reuse. No parallel utilities. The util/ parent-pom blocker (R-01) must be resolved before Sprint 0 — either vendor the parent `vn.vnpt:be` pom at the project root, or replace `<parent>` with inline `<dependencyManagement>` in `util/pom.xml`. No new code may be merged before this is fixed.
- **Vietnamese-first** language and locale defaults.

### 7.2 Hard non-technical constraints

- **Not a commercial product.** No sales motion; no merchant onboarding; no SLA to a paying customer.
- **No multi-currency beyond VND** in v1.
- **No tiered B2B pricing** in v1.
- **B2C only** in v1 (not marketplace).
- **No native mobile** in v1.
- **Stripe-only** payment integration in v1.

### 7.3 Non-goals explicitly accepted

- 100% test coverage is not the goal; critical-path coverage (payment, inventory, auth, tax-invoice) is. Tests that exist must pass.
- Zero-downtime deployment is a goal but not a v1-launch-blocker; rolling-update with brief downtime is acceptable if the launch bar is met.
- Multi-region active-active is a v3+ goal; v1 is single-region with cold standby.

## 8. Risk and Mitigations (carried from brainstorming)

The 15-entry risk register lives in the brainstorming session (§"Six Thinking Hats — Black Hat"). This PRD carries the *constraints* (which risks the design must mitigate) but does not duplicate full mitigation text — that belongs in the architecture phase ADR. Risks the PRD body *explicitly binds to* are listed below; remaining risks (R-07, R-08, R-09, R-10, R-11, R-13, R-14) are tracked in the brainstorming register and re-surfaced in the architecture phase.

| ID | Severity | Risk | Binding constraint in this PRD | Sprint |
|---|---|---|---|---|
| **R-01** | Critical | util/ parent pom missing — `mvn install` blocked | Must be fixed before Sprint 0. (Hard constraint, §7.) | 0 |
| **R-02** | Critical | Inventory oversell race | FR-9 (FOR UPDATE + reservation TTL) | 1 |
| **R-03** | Critical | Payment double-capture on saga replay | FR-25, NFR-IDEM-1/2 | 3 |
| **R-04** | Critical | Debezium outbox duplicate events | FR-22 default = Spring Modulith outbox; if Debezium chosen, RF=broker-count in dev too | arch-decision |
| **R-05** | Critical | Card-testing via residential proxies | FR-81 (gateway rate-limiter keys) | 3 |
| **R-06** | Critical | Vietnamese tax-invoice compliance | FR-78 (InvoiceService) | 9 |
| **R-12** | Low | Stripe API version drift | §10 Integration table (pin version + integration test on upgrade) | 3 |
| **R-13** | Low | GHN/GHTK carrier downtime | FR-38, FR-39 (carrier-degraded UI + jittered retry) | 4 |
| **R-15** | Critical | PCI scope creep | FR-29, NFR-SEC-1/2 | 3 |

See `addendum.md` for the full 15-entry table with all severities, likelihoods, owners, and detailed mitigations.

## 9. Operational Requirements

- **Deployment:** Kubernetes (single-region, multi-AZ). Helm charts per service. ArgoCD for GitOps.
- **CI/CD:** GitHub Actions. PR checks: build, unit tests, integration tests (Testcontainers for Kafka/Postgres/Redis/ES), lint, type-check, OPA policy check.
- **Chaos testing:** Chaos Mesh experiments in `chaos/` directory, one per P0 risk. Run weekly in staging; game-days quarterly.
- **Runbooks:** one Markdown per alert in `runbook/`. Linked from Grafana dashboard annotations.
- **Capacity planning:** separate workflow post-architecture; for now, **[ASSUMPTION]** target ~50k orders/day at p95 < 300ms checkout, with the exact number to be confirmed by stakeholders and load-tested in architecture phase. See `addendum.md` A3 for the full capacity-planning assumptions table.

## 10. Integration and Dependencies

| External | Purpose | Risk |
|---|---|---|
| **Stripe** | Payment processing, webhooks, customer vault | API version drift — pin and integration-test (R-12) |
| **SendGrid** | Email delivery (already wired in util) | Deliverability depends on SPF/DKIM (INT-05) |
| **GHN / GHTK / Viettel Post** | Vietnam domestic carriers | Outages common; jittered retry + carrier-degraded UI (R-13) |
| **FCM** | Push notifications | Standard |
| **Apicurio Registry** | Avro schema registry | SPOF — cache schemas client-side (R-14) |
| **Vietnam tax authority** | Daily invoice register upload | External dependency; daily batch with retry (LC-03) |

## 11. Data Governance

- **Per-service database** (database-per-service pattern). No cross-service joins.
- **Customer PII** lives in CustomerService only. Other services reference by aggregate ID.
- **Right-to-be-forgotten** hard-deletes PII + anonymizes order history (FR-49).
- **Retention windows:** orders 7 years (Vietnam tax requirement); logs 90 days; metrics 13 months.
- **Cross-region replication:** disabled in v1; cold standby only.

## 12. Compliance and Regulatory

- **Vietnam PDPD** (Decree 13/2023/NĐ-CP): consent capture, data export, right-to-be-forgotten.
- **Vietnamese tax-invoice** (Circular 78/2021/TT-BTC + Decree 123/2020/NĐ-CP): serialized invoices, daily register, QR verification.
- **PCI-DSS v4.0**: scope minimized via Stripe Elements iframe; no PAN in our environment.
- **PSD2 / SCA** (EU/UK customers only when added): 3DS step-up for risk-flagged transactions.
- **GDPR** (when expanding beyond Vietnam): data subject rights, lawful basis, data protection impact assessment.

## 13. Open Questions (now resolved by architecture)

All 5 Open Questions were deferred to architecture for binding. Status now reflects architecture resolution:

- **Q1.** Saga architecture — ✅ **RESOLVED** (architecture ADR-01): **Spring Modulith outbox** (Green-Hat default binding). Was the phase-blocker; binding is now in place for Sprint 2 (saga implementation in Story 2.5).
- **Q2.** Multi-warehouse scope — ✅ **RESOLVED** (ADR-06): **Single-warehouse v1 default**; multi-warehouse is P1 stretch.
- **Q3.** B2C vs marketplace — ✅ **RESOLVED** (ADR-07): **B2C v1**; marketplace v2.
- **Q4.** Soft-delete uniqueness mechanism — ✅ **RESOLVED** (ADR-05): **util's `@SoftUk` annotation** as default; CI lint enforces.
- **Q5.** Vietnamese tax-authority registration specifics — ✅ **RESOLVED** (ADR-26 binds the implementation pattern: Jasper template + serialized allocator + QR + daily batch). Merchant credentials collected via structured schema `vietnam_tax_authority_credential` table + accountant-input template (see architecture §"Detail: ADR-26 → Merchant-credentials schema"). Sprint 9 Story 9.2b tracks the credential-collection ceremony. Not a phase-blocker.

See `_bmad-output/planning-artifacts/architecture.md` §"Core Architectural Decisions" for full ADR rationale.

## 14. Success Metrics (with counter-metrics)

**Primary (signal):**

- **Time-to-first-successful-deploy** for a new engineer: target < 2 hours from `git clone` to checkout completing locally.
- **Integration test pass rate**: 100% in CI on every PR.
- **Documentation coverage of P0 risks**: every R-XX in the brainstorming register has a runbook in `runbook/`.

**Secondary (signal):**

- **GitHub stars** proxy for community interest.
- **Tutorial completion rate** (if published) — measures how well the reference teaches.
- **Issue close time** on `good-first-issue` — measures community health.

**Counter-metrics (signal-of-failure to watch):**

- **Tutorial abandonment rate** — if high, the reference fails to teach.
- **P0-risk-related incident count** in production (where deployed) — should be zero per quarter if mitigations are real.
- **Drift between brainstorming intent and shipped code** — measured by architecture-phase traceability audit (semi-annual).

## 15. Glossary

- **Saga** — long-running transaction broken into local transactions across services with compensating actions on failure.
- **Outbox pattern** — service writes domain event to an outbox table in the same transaction as the business state; a separate process publishes outbox rows to the message broker.
- **CDC** — change data capture; observing row-level changes in a database and publishing them as events.
- **KRaft** — Kafka Raft consensus protocol; replaces Zookeeper dependency.
- **JTBD** — Jobs to be Done; framing customer needs as the job a person is trying to get done.
- **MACH** — Microservices, API-first, Cloud-native, Headless; commerce architecture paradigm.
- **PDPD** — Vietnamese Personal Data Protection Act (Decree 13/2023/NĐ-CP).
- **PSD2 / SCA** — EU Payment Services Directive 2 / Strong Customer Authentication.
- **Soft-delete uniqueness** — database constraint ensuring that among non-deleted rows, the unique-key still holds; doesn't fire across soft-deleted rows.
- **Saga step name** — name of a saga action (e.g., `payment.authorize`); used as part of idempotency key.
- **VND** — Vietnamese đồng; base currency in v1.
- **VN address hierarchy** — Province → District → Commune (util's existing DTOs).

## 16. References (source artifacts)

- Brainstorming session: `_bmad-output/brainstorming/brainstorming-session-2026-07-06-1119.md` (189 ideas, 10/10 quality)
- Market research: `_bmad-output/planning-artifacts/market-research.md`
- Domain research (13 services, 8 invariants): `_bmad-output/planning-artifacts/domain-research.md`
- Technical research: `_bmad-output/planning-artifacts/technical-research.md`
- Architecture docs: `local-docs/01..09.md`
- util library: `local-docs/10-util-library.md`
- Decision log: `_bmad-output/planning-artifacts/prds/prd-side-project-2026-07-06/.decision-log.md`
- **Addendum** (rejected alternatives, options-considered matrices, full 15-entry risk table, capacity assumptions, version matrix, traceability): `_bmad-output/planning-artifacts/prds/prd-side-project-2026-07-06/addendum.md`

## 17. What This PRD Does Not Cover

- **Technical how** (transport choices, library versions beyond constraints, deployment topology detail) — feeds architecture phase.
- **Visual / interaction design** — feeds `bmad-ux` if/when invoked.
- **Story-level requirements with acceptance criteria** — feeds `bmad-create-epics-and-stories` (next BMad step).
- **Sprint-by-sprint task assignments** — feeds `bmad-sprint-planning`.

---

**[ASSUMPTION] tags placed inline where inference was needed:**
- NFR-PERF-1/2/3 latency targets inferred from typical reference-implementation benchmarks; should be validated in architecture phase via load tests.
- 50k orders/day capacity target is inferred from UJ-3 (Mai's customer site) — should be confirmed with stakeholders.
- 13-service count is per `domain-research.md`: Catalog, Inventory, Cart, Checkout, Order, Fulfillment, Returns, Customer, Search, Notification, Admin, Pricing, Payment. **Auth is folded into Customer** as a single bounded context (per brainstorming "Customer + Auth" coupling, with the Auth group under §4.15 *Identity & Auth* delivering cross-cutting auth requirements). Architecture phase confirms.
- All FR-N IDs are stable within this document but the global numbering convention assumes the architecture phase may add more NFRs.
- Sprint numbers (1–10) in risk-mitigation columns are inherited from brainstorming Blue Hat; the sprint-planning phase may re-order.
