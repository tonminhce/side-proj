---
audience: future-agents
project: side-project
date: 2026-07-06
canonical-source: _bmad-output/planning-artifacts/architecture.md + architecture-detail.md
how-to-use: 1-paragraph summary per ADR. When you need depth, jump to the matching Detail section in architecture-detail.md.
---

# ADR Index — Quick Reference

> **Source of truth:** `_bmad-output/planning-artifacts/architecture.md` (main) + `architecture-detail.md` (companion). This file is a navigable summary, not a replacement.

---

## ADR-01 — Spring Modulith outbox (Q1 binding)

**One-liner:** Use Spring Modulith outbox for v1 saga; all 13 services are logical modules in a single deployment. Kafka transactions + Debezium are explicitly out of v1.

**Read this when:** Implementing order, checkout, payment, or any saga participant. Also when debating microservice split (default: don't split).

**Detail:** `architecture-detail.md` §"Detail: ADR-01" (Q1 resolution, reversibility path to microservice split).

---

## ADR-02 — 13 services; Auth folded into Customer

**One-liner:** 13 bounded contexts. `services/customer/auth/` sub-module. **13 is final for v1** (no further split).

**Read this when:** Deciding which service owns a new entity. Also when arguing for/against auth-as-separate-service.

**Detail:** `architecture.md` §"Project Structure & Boundaries" — full project tree.

---

## ADR-03 — Per-service database

**One-liner:** Each service has its own Postgres DB. No cross-service joins. Cross-service refs by aggregate ID only.

**Read this when:** Designing a new entity, query, or migration. Repository patterns must scope to one service's DB.

---

## ADR-04 — Event-driven foundation: Kafka 4 KRaft + Avro + Apicurio 2.6

**One-liner:** Per-service outbox table → Modulith outbox bridge → Kafka topic. Avro with strict backward+forward compat enforced in CI.

**Read this when:** Publishing or consuming any event. Schema evolution is CI-gated.

**Detail:** `architecture-detail.md` §"Detail: ADR-04" — per-locale ES index strategy + outbox bridge operational details.

---

## ADR-05 — Soft-delete uniqueness via util's `@SoftUk`

**One-liner:** Use `util/annotation/SoftUk` for any soft-deletable entity. CI lint rejects new entities that have soft-delete fields but no `@SoftUk`.

**Read this when:** Creating any JPA entity with `isDeleted` or `deletedAt` fields.

---

## ADR-06 — Single-warehouse v1 (Q2 binding)

**One-liner:** v1 has one warehouse. Multi-warehouse is P1 stretch only. InventoryService assumes one logical warehouse with per-variant stock.

**Read this when:** Implementing inventory, shipping, or warehouse-distance logic.

---

## ADR-07 — B2C v1 (Q3 binding)

**One-liner:** B2C only. Marketplace (sellers, multi-tenant) deferred to v2. `cart_line.sellerId` is null in v1.

**Read this when:** Implementing cart, pricing, or order logic that might suggest multi-seller.

---

## ADR-08 — Vietnamese tax-invoice (Q5 binding; Q5 closure sub-detail)

**One-liner:** VN tax-invoice via Jasper + serialized allocator + QR. **Q5 closure**: requires `vietnam_tax_authority_credential` row from accountant before any invoice is issued.

**Read this when:** Implementing or modifying the InvoiceService. **Always** check the credential row before generating invoices.

**Detail:** `architecture-detail.md` §"Detail: ADR-26" — full schema + Q5 closure procedure.

---

## ADR-09 — REST + BFF (Backend-for-Frontend)

**One-liner:** Each frontend surface (storefront, admin) has a thin BFF that aggregates from services, handles auth translation, and applies rate-limiting.

**Read this when:** Building BFF endpoints or cross-service aggregation logic.

**Detail:** `architecture-detail.md` §"Detail: ADR-09".

---

## ADR-10 — Next.js 15 App Router + Server Components + Server Actions

**One-liner:** TypeScript strict. Tailwind + shadcn/ui. TanStack Query for server state. next-intl with `vi` default. OTel browser SDK → LGTM.

**Read this when:** Building any frontend surface.

**Detail:** `architecture-detail.md` §"Detail: ADR-10".

---

## ADR-11 — Idempotency-key: stable `(aggregate_id, saga_step_name)`

**One-liner:** Idempotency keys are derived from `(aggregate_id, saga_step_name)`, NOT per-retry. Same key on retry = same Stripe response.

**Read this when:** Implementing Stripe calls, webhooks, or any retryable external API.

---

## ADR-12 — Saga = intra-process state machine on order aggregate

**One-liner:** Order aggregate has 10-state enum + `version` column + `order_state_transition` log table. Crash-recovery routine on startup replays stuck orders.

**Read this when:** Implementing Order service or any saga step.

**Detail:** `architecture-detail.md` §"Saga state storage + transition table (ADR-12 binding detail)".

---

## ADR-13 — Rate-limiter: Redis Lua with `redis.call('TIME')`

**One-liner:** The Lua script uses `redis.call('TIME')` (Redis-server time, NOT gateway wall-clock). Fixes the time-source bug flagged in `local-docs/04`.

**Read this when:** Modifying or auditing the gateway rate-limiter. **Do not** revert to gateway wall-clock.

---

## ADR-14 — Per-service outbox + Modulith outbox bridge (no Debezium in v1)

**One-liner:** Outbox is per-service. Modulith outbox bridge polls + publishes. **No Debezium in v1** — saves R-04 risk for now.

**Read this when:** Configuring outbox infrastructure, or arguing for/against Debezium in v2.

**Detail:** `architecture-detail.md` §"Outbox bridge operational details (ADR-14)".

---

## ADR-15 — Avro strict backward+forward compat, CI gate

**One-liner:** Every Avro PR runs `apicurio-registry compatibility check` against the prior version. Incompatible = PR rejected.

**Read this when:** Modifying any Avro schema.

---

## ADR-16 — OTel + LGTM + Chaos Mesh, one chaos per P0 risk

**One-liner:** Grafana + Prometheus + Loki + Tempo for observability. Chaos Mesh experiments in `platform/chaos/chaos-mesh/` aligned to R-XX risks.

**Read this when:** Adding observability or chaos engineering.

---

## ADR-17 — K8s + Helm + ArgoCD GitOps

**One-liner:** Single-region multi-AZ. Helm chart per service. ArgoCD app-of-apps. Single command roll-forward/roll-back.

**Read this when:** Working on deployment or infrastructure.

---

## ADR-18 — HashiCorp Vault for secrets

**One-liner:** **No `.env` files in repo.** All secrets via Vault. `secret/events/hmac/<service-name>` for HMAC keys (per ADR-20).

**Read this when:** Adding any secret or credential.

---

## ADR-19 — OPA/Rego admission policies

**One-liner:** OPA/Rego for Kafka topic creation, schema registration, JDBC pool sizing. Bad config rejected at admission.

**Read this when:** Adding a new Kafka topic or changing pool sizes.

---

## ADR-20 — HMAC event signing (HS256, Vault-backed)

**One-liner:** Every event carries `signatures.service` + `signatures.hmac_sha256` (HS256, base64url). 32-byte key per service in Vault. Quarterly rotation with 7-day overlap. **Producer fails loud** if Vault unreachable.

**Read this when:** Implementing event publish/consume anywhere.

**Detail:** `architecture-detail.md` §"Detail: ADR-20 (HMAC event signing scheme)".

---

## ADR-21 — Webhook dedup on Stripe `event.id`

**One-liner:** `webhook_dedup` table keyed by `event.id`. Idempotent under Stripe's 3-day retry storm.

**Read this when:** Implementing PaymentService webhook handler.

---

## ADR-22 — Snowflake strict mode (throw if `POD_NAME` missing)

**One-liner:** `getWorkerIdFromPod()` throws in non-dev profiles if `POD_NAME` missing. Dev profile still uses `SecureRandom` fallback with WARN log.

**Read this when:** Deploying services to staging/prod.

---

## ADR-23 — PCI scope: Stripe Elements + OTel log redaction

**One-liner:** Stripe Elements iframe-only. OTel log redaction matches `\d{13,19}` (PAN-shaped). Default request-body logger is deny-listed.

**Read this when:** Adding any logging, especially around payment.

---

## ADR-24 — Card-testing defense (IP + card-fingerprint + ASN)

**One-liner:** Gateway rate-limiter keys on `IP + card-fingerprint + ASN`. BIN velocity check across all users.

**Read this when:** Tuning rate-limits or fraud detection.

---

## ADR-25 — Vietnamese diacritic search

**One-liner:** Per-locale ES index `catalog_<locale>_<env>`. Vietnamese analyzer with asciifolding + diacritic folding + metaphone phonetic.

**Read this when:** Configuring search analyzer or ES index template.

**Detail:** `architecture-detail.md` §"Detail: ADR-04" → "Elasticsearch read-side".

---

## ADR-26 — Vietnamese tax-invoice (Jasper + QR + serialized)

**One-liner:** InvoiceService acquires sequence from `tax_invoice_sequence` via `SELECT FOR UPDATE`. Jasper template uses util's Vietnamese fonts. QR via `QRCodeUtil`. Daily batch uploads to Vietnam tax authority.

**Read this when:** Implementing InvoiceService.

**Detail:** `architecture-detail.md` §"Detail: ADR-26" — full schema + Q5 closure procedure.

---

## Quick stats

- **26 ADRs** binding implementation
- **6 detail sections** live in `architecture-detail.md` (ADR-01, 04, 09, 10, 20, 26)
- **All 5 Open Questions (Q1..Q5)** resolved
- **All 15 brainstormed risks (R-01..R-15)** either mitigated by an ADR or tracked as a known gap
- **`reviewCycle: 5`** (was 4; bumped when split into main + companion)

## How to use this file

```
# I'm implementing a Stripe webhook handler
→ grep "ADR-21\|webhook" ADR-INDEX.md
→ read ADR-21 detail in architecture-detail.md (none — summary only)
→ read full architecture.md §"Core Architectural Decisions" table for ADR-21 row
→ read epic Story 3.2 AC for the binding test
```

If the ADR-INDEX summary is insufficient, follow the "Detail" pointer to `architecture-detail.md` for the deep-dive, or read `architecture.md` for the full table.
