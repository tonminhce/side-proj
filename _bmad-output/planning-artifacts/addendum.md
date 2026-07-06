# Addendum — PRD Ecommerce Reference Implementation

> **Purpose:** Per BMad workflow convention, this addendum captures material that informs the PRD but does not belong in the PRD itself: rejected alternatives, options-considered matrices, technical-mechanism decisions, capacity-planning assumptions, full risk-register table, glossary expansions. Kept close to the PRD for traceability; intended to be read alongside it.

**PRD:** `_bmad-output/planning-artifacts/prd.md`
**Decision log:** `_bmad-output/planning-artifacts/prds/prd-side-project-2026-07-06/.decision-log.md`

---

## A1. Full 15-entry Risk Register (carried from brainstorming)

Verbatim from brainstorming session §"Six Thinking Hats — Black Hat," preserved here for downstream workflow continuity. Architecture phase owns the mitigation-by-design; the PRD binds only the constraints listed in §8.

| ID | Risk | Severity | Likelihood | Owner | Mitigation |
|---|---|---|---|---|---|
| **R-01** | util/ parent pom missing — `mvn install` blocked | Critical | Certain | Build Eng | Vendor parent pom OR inline dependencyManagement (see INT-01 root cause) |
| **R-02** | Inventory oversell race condition | Critical | High | InventoryService | SELECT FOR UPDATE + reservation TTL + saga atomic reserve (DI-01 root cause) |
| **R-03** | Payment double-capture on saga replay | Critical | Medium | PaymentService | Stable idempotency key per (order, step); webhook dedup table (DI-02 root cause) |
| **R-04** | Debezium outbox duplicate events | Critical | High | Platform | Kafka transactions + RF=broker-count in dev too; or Spring Modulith outbox; default = Modulith (DI-03 root cause) |
| **R-05** | Card-testing attack via residential proxies | Critical | High | Gateway + Fraud | Trust-boundary fix at gateway; card-fingerprint hash + BIN velocity (AT-01 root cause) |
| **R-06** | Vietnamese tax-invoice compliance | Critical | Certain (VN market) | InvoiceService | Serialized number allocator + Jasper template + QR + daily batch (LC-03 root cause) |
| **R-07** | Vietnamese diacritic search zero-results | High | Certain (VN market) | SearchService | analysis-vn plugin + diacritic folding (UX-05) |
| **R-08** | Snowflake worker-id collision silent | Medium | Medium | Platform | Throw if POD_NAME missing in prod; metric + alert (OP-05) |
| **R-09** | Spring Boot 4 ecosystem immaturity | High | High | Build Eng | Pin versions; integration test before upgrade; technical-research.md has matrix; Spring Cloud 2025.1 libraries lag 4–8 weeks |
| **R-10** | Soft-delete uniqueness regression | Medium | Low | Base infra | Enforce @SoftUk via @EntityListeners; lint check (DI-09) |
| **R-11** | Redis OOM during sale | Medium | Medium | Platform | Circuit breaker; explicit fail-open policy; rate-limit metrics (OP-04) |
| **R-12** | Stripe API version drift | Low | Medium | PaymentService | Pin API version; integration test on upgrade |
| **R-13** | GHN/GHTK carrier downtime | Low | High | FulfillmentService | Retry + jitter + carrier-degraded UI |
| **R-14** | Apicurio Registry single point of failure | Medium | Low | Platform | Cache schemas client-side; failover policy (OP-06) |
| **R-15** | PCI scope creep | Critical | High | Security | Stripe Elements iframe only; lint to forbid PAN in logs; OTel log redaction for any field matching `\d{13,19}`; deny-list the default request-body logger |

**Phase-blocker status for architecture phase:**
- R-01, R-02, R-03, R-04, R-05, R-06, R-15 — must have explicit mitigations in the architecture ADR before any sprint that touches the affected service.
- R-08, R-09, R-10, R-11, R-12, R-13, R-14 — tracked but not architecture-phase-blockers.

---

## A2. Q1 Saga Architecture — Options Considered Matrix

The PRD carries FR-22 with two candidate patterns; the binding decision is deferred to architecture phase. This section preserves the options-considered detail for that decision.

### Option A: Spring Modulith outbox (Green-Hat default)

**Mechanism:** Spring Modulith's `@ApplicationModule` boundaries enforce module-package visibility; outbox pattern is implemented via `@ApplicationModuleListener` + Modulith's outbox support. Single deployment unit, but logical modules are enforced. CDC out to Kafka is achieved via Modulith's outbox bridge.

**Pros:**
- Single deployable = simpler ops for a reference implementation
- Outbox + idempotent handlers built-in; no need to wire Debezium + Kafka transactions
- Lower operational cost (one JVM, one DB, one process)
- Easier local dev: `mvn spring-boot:run` and the whole thing works
- Smaller blast radius for a reference reader

**Cons:**
- Scaling is module-bound; can't scale `InventoryService` independently
- A bug in one module can crash the whole process
- Module boundaries are convention-enforced, not JVM-enforced
- Multi-region requires splitting later — the architecture ADR must plan for that

**Risk profile:**
- R-04 (Debezium outbox duplicates) → not applicable if Modulith outbox chosen
- R-09 (Boot 4 ecosystem) → Modulith is core Spring, lower risk than Debezium+Statemachine

### Option B: Spring Statemachine + Debezium + Kafka transactions (alternate)

**Mechanism:** Each service is a separate Spring Boot deployment with its own Postgres DB. Debezium reads the per-service `outbox` table and publishes to Kafka with `transactional.id` configured. Spring Statemachine models the saga state machine per service.

**Pros:**
- True service independence: scale, deploy, fail each in isolation
- Module boundaries enforced by JVM (not convention)
- More representative of production microservice patterns

**Cons:**
- Higher operational cost: 14+ JVMs, 13+ Postgres instances, Kafka cluster, Debezium Connect cluster, Apicurio, Elasticsearch, Redis
- Debezium + Kafka transactions carry operational complexity (RF=broker-count must hold in dev, `__transaction_state` topic config, offset commit semantics)
- Spring Statemachine adds a learning curve for the reference reader
- More failure modes to debug

**Risk profile:**
- R-04 (Debezium outbox duplicates) → directly applicable; mitigation requires Kafka transactions + dev/prod parity
- R-09 (Boot 4 ecosystem) → Debezium 3 + Kafka 4 + Spring Cloud 2025.1 have inter-version lag; pinning required

### Decision criteria (architecture phase)

- **Default to Option A** unless explicit user signal to otherwise. The reference-implementation audience benefits more from a working monolith than from a fragile microservice.
- **Plan a split path:** the architecture ADR must include an "Option C: hybrid" — start Modulith, extract a service when load demands it. Modulith is not a permanent commitment; it is a Phase 0.

---

## A3. Capacity Planning Assumptions

The PRD mentions 50k orders/day in §9 (Operational Requirements) and §14 (Success Metrics). This is a working assumption inherited from UJ-3; the architecture phase must validate it via load testing. Working numbers:

| Tier | Orders/day | Concurrent checkouts (peak) | Notes |
|---|---|---|---|
| **Staging (dev)** | 100 | 5 | Local docker-compose |
| **Internal demo** | 1,000 | 50 | Single-AZ, single-region |
| **Production v1 (assumed)** | 50,000 | 500 | Multi-AZ, single-region; per-section ASSUMPTION |
| **Production v2 (stretch)** | 500,000 | 5,000 | Multi-AZ, multi-region active-active (v3+ scope) |

Latency targets (production v1):
- p50 catalog read: < 30ms (served from ES)
- p95 catalog read: < 100ms (NFR-PERF-1)
- p99 catalog read: < 300ms
- p95 search query: < 300ms (NFR-PERF-2)
- p95 checkout completion: < 800ms
- p99 checkout completion: < 1,500ms

These are inferred from typical reference-implementation benchmarks and should be validated under load in architecture phase.

---

## A4. Version Matrix (from technical-research)

Pin these in architecture-phase ADRs. Boot 4 ecosystem has documented 4–8 week library-lag risk (R-09); integration-test each upgrade.

| Component | Recommended version | Pairing constraint |
|---|---|---|
| Java | 25 (LTS) | Required by Spring Boot 4 |
| Spring Boot | 4.0.0 (GA 10 Jun 2026) | Latest 4.0.x patch |
| Spring Cloud | 2025.1 "Oakwood" | Pairs with Boot 4.0.x |
| Spring Cloud Gateway | 5.x | 4.x is EOL |
| Spring Statemachine | (latest 4.x) | If Option B chosen |
| Apache Kafka | 4.x | KRaft mode only (no Zookeeper) |
| Debezium | 3.x | If Option B chosen |
| Apicurio Registry | 2.6.x | Avro compatibility |
| Elasticsearch | 8.x | Client/server version match required |
| Redis | 7.x | Lua scripts for rate-limiter (R-05 mitigation) |
| PostgreSQL | 16+ (LTS) | Per-service DB |
| Stripe API | pin specific version | Pin in code + test on upgrade (R-12) |
| Lombok | 1.18.x | MapStruct 1.5+ for DTO mapping |

---

## A5. Rejected Alternatives (preserved for traceability)

### Rejected: Adyen / Braintree in v1

Stripe covers 90%+ of card + wallet scenarios. Adding a second PSP in v1 doubles PCI scope review, multiplies integration test matrix, and offers no reference-impl value beyond "two PSPs are wired." Deferred to v2 if user demand exists.

### Rejected: Two-phase commit (2PC/XA) for checkout

Brainstorming DI section flags 2PC as an anti-pattern. Locks across the saga would make the platform undeployable under real load. Outbox + idempotent handlers is the chosen pattern.

### Rejected: Spring Statemachine as the *only* saga mechanism (Option B without fallback)

Statemachine adds learning overhead and DB schema complexity. For a reference implementation, a hand-rolled saga with a state field on the aggregate is more readable. The architecture phase picks the right tool per service.

### Rejected: Social login in v1

OAuth2 surface area is non-trivial and the `util/` library already provides the auth primitives. Defer to P2; revisit if a customer deployment demands it.

### Rejected: Marketplace seller tenancy in v1

Multi-tenant + seller onboarding + payout ledger is a 6-month effort on its own. The reference proves B2C first; marketplace is the obvious v2 follow-on.

### Rejected: Native mobile (iOS / Android) in v1

Next.js 15 with App Router + React Native is the obvious v2 path. Forcing native in v1 doubles platform surface for the team.

### Rejected: ML-driven recommendations in v1

"Popular in category" + "frequently bought together" rules cover 80% of conversion lift without ML. ML deferred to v2; the architecture includes a recommendation event-subscription point so ML can be slotted in later without re-architecture.

---

## A6. Glossary Expansions

- **ApplicationModule** — Spring Modulith annotation that marks a Java package as a logical module with enforced boundary.
- **Avro IDL** — Avro schema definition language; Avro Schema Registry stores versions and enforces backward/forward compatibility.
- **Baymard** — independent UX research institute; their checkout-completion-rate data is a common reference for ecommerce design.
- **Boot 4 GA** — Spring Boot 4.0.0 was released 10 June 2026.
- **Cold standby** — single-region active + a passive replica that can be promoted on failure; lower cost than active-active.
- **Debezium Outbox Event Router SMT** — Kafka Connect Single Message Transform that routes outbox events to per-aggregate Kafka topics.
- **EUR** — Euro; PSD2/SCA is EU/UK specific.
- **Golden path** — happy-path test that exercises the most important business flow end-to-end.
- **HashiCorp Vault** — secrets manager; default reference-impl deployment target.
- **mTLS** — mutual TLS; both client and server present certificates.
- **PNL** — promotional notification lifecycle event.
- **RR (rules of reference)** — informal "how a reference is meant to be used" — a reference shows *how*, the reader is expected to *adapt* to their context.
- **Tax authority registration** — Vietnam: each merchant registers with the General Department of Taxation and gets a tax code; the tax-code is the prefix for serialized invoice numbers.
- **Thundering herd** — a cache miss + traffic spike causing every request to fall through to the upstream resource simultaneously.
- **VNDS** — Vietnamese diacritic folding.
- **WebHook dedup** — pattern of storing `event.id` in a dedup table and rejecting redeliveries.

---

## A7. PRD-to-Brainstorming Traceability

For traceability, here is the inverse: every FR/NFR in the PRD and where it came from in the brainstorming session.

| FR/NFR | Source in brainstorming | Notes |
|---|---|---|
| FR-1 to FR-7 | SCAMPER items `[CAT-*]` (7 lenses) | |
| FR-8 to FR-13 | SCAMPER items `[INV-*]`; Five Whys DI-01 (root cause) | |
| FR-14 to FR-18 | SCAMPER items `[CART-*]` | |
| FR-19 to FR-23 | SCAMPER items `[CHK-*]`; Six Hats Green-Hat (Modulith outbox) | FR-22 = Q1 |
| FR-24 to FR-29 | SCAMPER items `[PAY-*]`; Five Whys DI-02 (idempotency) | |
| FR-30 to FR-34 | SCAMPER items `[ORD-*]` | |
| FR-35 to FR-39 | SCAMPER items `[FUL-*]` | |
| FR-40 to FR-44 | SCAMPER items `[RET-*]`; Five Whys DI-07 (cumulative refunds) | |
| FR-45 to FR-50 | SCAMPER items `[CUS-*]`; Five Whys LC-01 (PDPD export) | |
| FR-51 to FR-55 | SCAMPER items `[SRH-*]`; Five Whys UX-05 (diacritic search) | |
| FR-56 to FR-60 | SCAMPER items `[NOT-*]` | |
| FR-61 to FR-64 | SCAMPER items `[ADM-*]` | |
| FR-65 to FR-68 | SCAMPER items `[PRC-*]` | Multi-currency is P2 non-goal |
| FR-69 to FR-72 | SCAMPER items `[REV-*]` | |
| FR-73 to FR-77 | SCAMPER items `[AUT-*]`; EC AT-02 (credential stuffing) | |
| FR-78 | Five Whys LC-03 (Vietnamese tax invoice) | |
| FR-79 | Risk R-15 (PCI scope creep) | |
| FR-80 | Five Whys LC-01 (PDPD export) | |
| FR-81 | Five Whys AT-01 (card testing) | |
| FR-82 | Five Whys AT-03 (CDC event injection) | |
| NFR-PERF-* | EC PERF-01..03 (added in deep-review) | |
| NFR-IDEM-* | Five Whys DI-02, OP-01 | |
| NFR-AVAIL-* | Risk register R-11, EC OP-04 (Redis OOM), R-15 (fail-closed) | |
| NFR-OBS-* | EC OBS-01..03 (added in deep-review) | |
| NFR-SEC-* | Five Whys AT-01, AT-03 | |
| NFR-MIG-* | EC MIG-01..03 (added in deep-review) | |
| NFR-I18N-* | EC I18N-01..03 (added in deep-review) | |

---

## A8. PRD-to-Domain-Research Traceability

The 13-service breakdown in §4 (PRD) and the 13 service boundaries in `domain-research.md` agree. Architecture phase must confirm: if Q1 selects Option B (Statemachine + Debezium), the 13 services map 1:1 to 13 deployable units; if Option A (Modulith outbox), the 13 services are 13 logical modules in a single deployment unit.

Service count: **13** (Catalog, Inventory, Cart, Checkout, Order, Fulfillment, Returns, Customer, Search, Notification, Admin, Pricing, Payment). Auth folded into Customer as a single bounded context per brainstorming "Customer + Auth" coupling.
