---
audience: PM, SRE, Architect, Risk Owner
project: side-project
date: 2026-07-06
canonical-source: _bmad-output/brainstorming/brainstorming-session-2026-07-06-1119.md (full context)
how-to-use: complete risk inventory with mitigations + owners. PM/SRE reference for sprint planning.
---

# Risk Register — side-project

> **Source of truth:** `brainstorming-session-2026-07-06-1119.md` (full traceability per risk + per Five Whys drill). This file is a flat actionable index.

> **Total risks:** 15
> **Critical (P0):** 7 → must be mitigated before launch
> **High:** 3 → mitigated with caveats
> **Medium:** 3 → tracked, Sprint 0/1 quality work addresses
> **Low:** 2 → deferred to monitoring

---

## 1. Critical Risks (P0) — Must be mitigated

### R-01 — util/ parent pom missing

| Field | Value |
|---|---|
| **Severity** | Critical (blocks Sprint 0) |
| **Likelihood** | Certain (already present in repo) |
| **Root cause** | `<parent>vn.vnpt:be:0.0.1-SNAPSHOT</parent>` in `util/pom.xml` references `../pom.xml` which doesn't exist in this repo |
| **Mitigation** | Sprint 0 Story 0.1: either (a) vendor `vn.vnpt:be` parent pom at project root, OR (b) replace `<parent>` with inline `<dependencyManagement>` in `util/pom.xml` |
| **Owner** | Build Eng |
| **Status** | **OPEN — blocking** until Story 0.1 ships |
| **ADR binding** | None (operational) |
| **Story** | 0.1 |
| **Verification** | `mvn -pl util -am clean install` succeeds |

---

### R-02 — Inventory oversell race

| Field | Value |
|---|---|
| **Severity** | Critical (revenue + customer trust) |
| **Likelihood** | High (concurrent checkouts are common) |
| **Root cause (DI-01)** | Saga step retries from Kafka redeliver can double-reserve stock if the reservation step isn't atomic |
| **Mitigation** | Sprint 1 Story 1.6: `inventory.reserve()` runs inside Postgres transaction with `SELECT ... FOR UPDATE` on the variant row; reservations auto-expire after 15 min via sweeper; idempotency-key strategy |
| **Owner** | InventoryService |
| **Status** | **Mitigation scoped to Story 1.6** (not yet built) |
| **ADR binding** | ADR-12 (saga state machine + crash recovery) |
| **Story** | 1.6 |
| **Verification** | 100x concurrent reservation tests; concurrent reservations of last unit return 1 success + N-1 409 |

---

### R-03 — Payment double-capture on saga replay

| Field | Value |
|---|---|
| **Severity** | Critical (financial correctness) |
| **Likelihood** | Medium (depends on Kafka redelivery frequency) |
| **Root cause (DI-02)** | Saga step retries from Kafka redelivery trigger Stripe `PaymentIntent.capture` twice with different idempotency keys |
| **Mitigation** | Sprint 3 Story 3.1: stable idempotency key derived from `(order_id, saga_step_name)` (NOT per-retry); same key on retry = same Stripe response. Story 3.2: webhook handler dedupes on `Stripe.event.id` via `webhook_dedup` table |
| **Owner** | PaymentService |
| **Status** | **Mitigation scoped to Stories 3.1 + 3.2** (not yet built) |
| **ADR binding** | ADR-11 (idempotency key strategy) + ADR-21 (webhook dedup) |
| **Story** | 3.1, 3.2 |
| **Verification** | Replay test: same idempotency key → same Stripe response; webhook redelivery → no double-processing |

---

### R-04 — Debezium outbox duplicate events on restart

| Field | Value |
|---|---|
| **Severity** | Critical |
| **Likelihood** | High (default outbox mode is at-least-once, not exactly-once) |
| **Root cause (DI-03)** | Debezium offset commit isn't transactional with the outbox row read; on restart, same outbox row is read twice |
| **Mitigation (architectural)** | **ADR-14: NO Debezium in v1.** Use Spring Modulith outbox bridge instead. Saga is intra-process; the outbox bridge publishes within 500ms with `published_at` dedup. If Kafka outbox-write succeeds but bridge-ack fails, the row is republished — consumer-side `processed_event` dedup handles that. |
| **Owner** | Platform |
| **Status** | **RESOLVED via ADR-01 + ADR-14** (no Debezium chosen for v1) |
| **Story** | 1.3 (catalog outbox) + 10.2 (chaos validates) |
| **Verification** | Chaos test: kill Modulith bridge mid-publish; restart; verify no double-consume via `processed_event` |

---

### R-05 — Card-testing attack via residential proxies

| Field | Value |
|---|---|
| **Severity** | Critical (direct revenue loss + Stripe account risk) |
| **Likelihood** | High (industry-standard attack) |
| **Root cause (AT-01)** | Standard rate-limit by IP is bypassable via residential proxy pools. Attacker makes 1000s of small failed payments to validate stolen cards |
| **Mitigation** | Sprint 3 Story 3.4: gateway rate-limiter key combines `IP + card-fingerprint + ASN` (not just IP). BIN velocity check across all users flags cards from same BIN used >N times in M minutes. ADR-13: Lua uses `redis.call('TIME')` (Redis-server time, not gateway wall-clock — fixes the time-source bug flagged in `local-docs/04`) |
| **Owner** | Gateway + Payment |
| **Status** | **Mitigation scoped to Story 3.4** (not yet built) |
| **ADR binding** | ADR-13 (Lua time fix) + ADR-24 (multi-key rate limit) |
| **Story** | 3.4 |
| **Verification** | Penetration test: 1000+ requests with rotating IPs same card → rate-limited |

---

### R-06 — Vietnamese tax-invoice compliance

| Field | Value |
|---|---|
| **Severity** | Critical (legal/regulatory) |
| **Likelihood** | Certain (Vietnam market) |
| **Root cause (LC-03)** | Vietnam requires serialized tax-invoices per Circular 78/2021/TT-BBC + Decree 123/2020/NĐ-CP. Wrong serialization = non-compliant invoices. |
| **Mitigation (Q5 closed)** | Sprint 9 Stories 9.2 + 9.2b: InvoiceService uses `vietnam_tax_authority_credential` schema (per ADR-26); sequence acquired from `tax_invoice_sequence` via `SELECT FOR UPDATE`; Jasper template uses util's Vietnamese fonts; QR code via util's `QRCodeUtil`; daily batch via Quartz cron uploads to Vietnam tax authority. **Story 9.2b** is the accountant-input-collection ceremony (closes Q5) |
| **Owner** | InvoiceService + Vietnamese accountant (external) |
| **Status** | **Mitigation scoped to Stories 9.2 + 9.2b** (not yet built; Story 9.2b requires calendar-time conversation with accountant before Sprint 9 begins) |
| **ADR binding** | ADR-26 (full implementation) + ADR-08 (Q5 binding) |
| **Story** | 9.2, 9.2b |
| **Verification** | CI gate: InvoiceService fails-fast without credential row; daily batch uploads succeed |

---

### R-15 — PCI scope creep

| Field | Value |
|---|---|
| **Severity** | Critical (compliance + Stripe account risk) |
| **Likelihood** | High (one logged PAN = scope expansion) |
| **Root cause** | Default request-body loggers + unredacted OTel + naive card form embedding = PAN-leak path |
| **Mitigation** | Sprint 3 Story 3.3: Stripe Elements iframe-only (PAN never touches our servers). OTel log redaction matches any field with `\d{13,19}` (PAN-shaped). Default request-body logger is deny-listed. Lint rule denies new request-body loggers. |
| **Owner** | Security + PaymentService |
| **Status** | **Mitigation scoped to Story 3.3** (not yet built) |
| **ADR binding** | ADR-23 (PCI scope) |
| **Story** | 3.3 |
| **Verification** | Pen test: try to log a PAN-shaped field; expect redaction in logs; OTel collector shows no PAN |

---

## 2. High-Priority Risks

### R-07 — Vietnamese diacritic search zero-results

| Field | Value |
|---|---|
| **Severity** | High (search is core UX in VN) |
| **Likelihood** | Certain (Vietnamese market) |
| **Root cause (UX-05)** | Default ES tokenizer treats "ao" and "áo" as distinct tokens; no diacritic folding |
| **Mitigation** | Sprint 6 Stories 6.1 + 6.2: per-locale ES index `catalog_vi_<env>`; Vietnamese analyzer with asciifolding + diacritic folding + metaphone phonetic. Per architecture "Elasticsearch read-side" sub-section. |
| **Owner** | SearchService |
| **Status** | **Mitigation scoped to Stories 6.1 + 6.2** |
| **Story** | 6.1, 6.2 |
| **Verification** | Search "ao so mi" returns "áo sơ mi" products |

---

### R-08 — Snowflake worker-id collision silent

| Field | Value |
|---|---|
| **Severity** | Medium (data corruption in production) |
| **Likelihood** | Medium (only happens if K8s deploy mislabels POD_NAME) |
| **Root cause (OP-05)** | `getWorkerIdFromPod()` falls back to `SecureRandom` silently if `POD_NAME` missing; collisions go undetected |
| **Mitigation** | Sprint 0 Story 0.5: in non-dev profiles, throw `WorkerIdMissingException` at boot if `POD_NAME` missing; dev profile still uses `SecureRandom` with WARN log. Metric `snowflake.worker.id.source` exposed. |
| **Owner** | util library |
| **Status** | **Mitigation scoped to Story 0.5** |
| **Story** | 0.5 |
| **Verification** | Deploy without `POD_NAME` env var → boot fails; metric shows `source=podname` for proper deploys |

---

### R-09 — Spring Boot 4 ecosystem immaturity

| Field | Value |
|---|---|
| **Severity** | High (technical risk) |
| **Likelihood** | High (Boot 4 GA was Jun 2026) |
| **Root cause** | Spring Cloud + 3rd-party libraries lag 4–8 weeks behind Boot 4 GA |
| **Mitigation** | Per technical-research §1: pin versions; integration-test every upgrade; add a known-matrix table. Spring Cloud 2025.1 "Oakwood" pairs with Boot 4.0.x |
| **Owner** | Build Eng |
| **Status** | **Tracked — version matrix in `addendum.md` A4** |
| **Verification** | Every CI run includes a "compat smoke test" of all Boot 4 + Spring Cloud 2025.1 + key library combinations |

---

## 3. Medium-Priority Risks

### R-10 — Soft-delete uniqueness regression

| Field | Value |
|---|---|
| **Severity** | Medium |
| **Likelihood** | Low |
| **Root cause (DI-09)** | Forgetting `@SoftUk` on a new soft-deletable entity breaks unique constraints across soft-deletes |
| **Mitigation** | Sprint 1 Story 1.8: CI lint rejects new entities with soft-delete fields but missing `@SoftUk` (ADR-05) |
| **Owner** | Base infra (util) |
| **Status** | **Mitigation scoped to Story 1.8** |
| **Story** | 1.8 |
| **Verification** | CI: create a soft-deletable entity without `@SoftUk` → lint fails |

---

### R-11 — Redis OOM during sale

| Field | Value |
|---|---|
| **Severity** | Medium (rate-limiter + cache share Redis) |
| **Likelihood** | Medium (flash-sale patterns) |
| **Root cause (OP-04)** | Rate-limiter cache + token-bucket state grow unbounded under burst; Redis evicts LRU keys |
| **Mitigation** | Per architecture NFR-AVAIL-4: explicit fail-open policy with metric + alert. Per architecture §"Outbox bridge operational details": backpressure thresholds. Per R-11 ADR-13 (rate-limiter Lua): tokens stored in Redis sorted set with TTL. |
| **Owner** | Platform |
| **Status** | **Mitigation scoped to NFR-AVAIL-4 + R-11 ADR-13** |
| **Story** | Platform/observability (Sprint 10) |
| **Verification** | Synthetic load test: 100x normal traffic → rate-limiter stays under 50% Redis memory |

---

### R-12 — Stripe API version drift

| Field | Value |
|---|---|
| **Severity** | Low |
| **Likelihood** | Medium (Stripe rolls new API versions yearly) |
| **Root cause** | Stripe rolls new API versions; if we don't pin, the SDK auto-upgrades and may break our integration |
| **Mitigation** | Pin Stripe API version in code (e.g., `Stripe.apiVersion = "2025-XX-acacia"`); integration-test on upgrade before merging |
| **Owner** | PaymentService |
| **Status** | **Tracked — not yet pinned** (Sprint 3 setup) |
| **Story** | 3.1 (integration) |
| **Verification** | Pin version is in code + test uses pinned version |

---

## 4. Low-Priority Risks

### R-13 — GHN / GHTK carrier downtime

| Field | Value |
|---|---|
| **Severity** | Low (degraded UX, not a fail-stop) |
| **Likelihood** | High (Vietnamese carriers have occasional downtime) |
| **Root cause** | Single-carrier adapter; no failover |
| **Mitigation** | Sprint 4 Story 4.6: carrier-degraded UI state (FR-38) + jittered retry + circuit breaker |
| **Owner** | FulfillmentService |
| **Status** | **Mitigation scoped to Story 4.6** |
| **Story** | 4.6 |
| **Verification** | Inject carrier API failure → UI shows "delivery may be delayed" within 5s |

---

### R-14 — Apicurio Registry single point of failure

| Field | Value |
|---|---|
| **Severity** | Low (registry down doesn't break running services; only breaks new schema registration) |
| **Likelihood** | Low (Apicurio is reliable) |
| **Root cause (OP-06)** | Apicurio down → consumers can't fetch new schemas; running services are unaffected if cached |
| **Mitigation** | Cache schemas client-side with 24h TTL; OPA admission policy requires schema registration before topic creation (so you can't accidentally publish a topic without a schema). If Apicurio down, fail-fast at publish time (not at consume time) |
| **Owner** | Platform |
| **Status** | **Tracked — mitigation documented** |
| **Verification** | Chaos test: take Apicurio down; running services still consume (cached); new schema registration fails with clear error |

---

## 5. Risks outside the 15 above (out of v1 scope)

These are explicitly deferred and not in the v1 risk register:
- Multi-warehouse stock migration (Q2 — single-warehouse v1)
- Marketplace seller tenancy (Q3 — B2C v1)
- Real-time fraud ML model (deferred)
- Cross-region replication (v3+)

---

## 6. Cross-references

- **Full 5-Whys drills** for each critical risk: `brainstorming-session-2026-07-06-1119.md` §"Phase 3 — Five Whys"
- **Architecture binding table** (R-XX → ADR): `architecture.md` §"Critical-Risk-to-ADR Binding Table"
- **PRD risk section**: `prd.md` §8 (5 risks listed for PRD binding)
- **Addendum full register**: `addendum.md` §A1 (same 15 entries with mitigation detail)

---

## 7. Risk Lifecycle

| Phase | Status | Owner action |
|---|---|---|
| **Sprint 0** | Open until Story 0.1 (R-01) and 0.5 (R-08) ship | Build Eng |
| **Sprint 1** | R-02 mitigated by Story 1.6; R-10 by Story 1.8 | InventoryService + Base infra |
| **Sprint 2** | R-04 mitigated (architectural — no Debezium) | Architect |
| **Sprint 3** | R-03, R-05, R-12, R-15 mitigated | PaymentService + Security |
| **Sprint 4** | R-13 mitigated | FulfillmentService |
| **Sprint 5** | AT-02 (account lockout) mitigated by Story 5.4 | CustomerService |
| **Sprint 6** | R-07 mitigated | SearchService |
| **Sprint 9** | R-06 mitigated (Story 9.2 + 9.2b) | InvoiceService |
| **Sprint 10** | Chaos validates R-01..R-15 mitigations under failure | Platform/observability |
| **Post-launch** | R-08 (Snowflake), R-09 (Boot 4 ecosystem), R-11 (Redis OOM), R-14 (Apurio SPOF) — ongoing monitoring | Platform/SRE |
