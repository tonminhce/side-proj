---
audience: future-agents
project: side-project (production-grade event-driven microservice ecommerce reference implementation)
date: 2026-07-06
how-to-use: read me first → then jump to the canonical doc for your role
---

# Agent Onboarding — side-project

You are reading this because you (the agent) were spawned to work on the **side-project** reference implementation. This page is the **single entry point** for understanding the project state and what to do next.

---

## 1. What this project is

A production-grade, event-driven microservice ecommerce platform built as a **reference implementation** for senior Java developers, architects, SREs, and educators. Goal: clone, run, learn.

**Stack (binding):** Java 25, Spring Boot 4.0.0 GA, Spring Cloud 2025.1, Kafka 4 KRaft, Spring Modulith outbox saga, Elasticsearch 8.x, Redis 7, PostgreSQL 16+, Stripe, Next.js 15, util/ shared library.

**Vietnamese-first:** tax-invoice compliance, diacritic search, address hierarchy.

**Audience for the code itself:** engineers learning patterns — not merchants. No sales motion.

---

## 2. The 5-stage BMad pipeline (READ IN THIS ORDER)

```
brainstorm → PRD → architecture → epics-and-stories → implementation-readiness → sprint-planning → story-automator
```

Each stage produces **one canonical artifact**. Each artifact is the input to the next stage. **Don't skip stages.** Don't read a later artifact before reading its input.

| Stage | Output file | What it answers | Who reads it |
|---|---|---|---|
| **Brainstorm** | `_bmad-output/brainstorming/brainstorming-session-2026-07-06-1119.md` | "What ideas exist? What risks? What questions are open?" | Founder / PM (exploration phase) |
| **PRD** | `_bmad-output/planning-artifacts/prd.md` | "What does the system DO? What NFRs? What's the vision?" | PM, Architect, downstream agents |
| **Addendum (companion)** | `_bmad-output/planning-artifacts/addendum.md` | "What alternatives were considered? What was rejected and why?" | Architect (when designing) |
| **Architecture** | `_bmad-output/planning-artifacts/architecture.md` | "What are the BINDING technical decisions?" | Architect, Dev agents |
| **Architecture-detail (companion)** | `_bmad-output/planning-artifacts/architecture-detail.md` | "Show me the deep dive on each ADR" | Deep implementer |
| **Epics & Stories** | `_bmad-output/planning-artifacts/epics.md` | "What are the dev units? What's the AC for each?" | Dev agents (the most-read file during build) |
| **Implementation-readiness report** | `_bmad-output/planning-artifacts/implementation-readiness-report-2026-07-06.md` | "Is everything aligned? Any unresolved gaps?" | All roles (gate check) |
| **Sprint plan (yaml)** | `_bmad-output/implementation-artifacts/sprint-status.yaml` | "Where are we now? What's the priority?" | Sprint Lead, Story Automator |
| **local-docs** | `local-docs/00..10.md` | "What's the existing context?" | Architect (background) |

---

## 3. File conventions — DO NOT VIOLATE

### BMad rules

1. **One canonical file per workflow.** The file name in `planning-artifacts/` matches the workflow that produced it. Don't create siblings (`my-prd.md`, `prd-v2.md`, etc.).
2. **Companion files are companions, not duplicates.** `addendum.md` and `architecture-detail.md` extend, they don't replace.
3. **PRD run-folder** `prds/prd-{project}-{date}/` holds metadata (`.decision-log.md`) + duplicates of canonical files. The flat path is canonical; the run-folder is audit trail.
4. **Frontmatter is required** on every artifact: `title`, `status`, `created`, `updated`, `reviewCycle`. Update `reviewCycle` whenever you make a substantive change.

### Status state machine

| File | Status values |
|---|---|
| prd.md | `draft` → `final` |
| architecture.md | `in-progress` → `complete` |
| epics.md | stepsCompleted `[1]` … `[1,2,3,4]` |
| implementation-readiness-report | (no explicit status) |
| sprint-status.yaml | per-key status: `backlog` / `ready-for-dev` / `in-progress` / `review` / `done` |

---

## 4. The 11-Sprint plan (binding)

Per architecture §"Decision Impact Analysis":

| Sprint | Domain | Stories | Risks solved |
|---|---|---|---|
| **0** | Foundations | 5 — R-01 util parent pom, monorepo bootstrap, dev docker-compose, CI scaffold, Snowflake strict mode | R-01, R-22 |
| **1** | Catalog + Inventory | 8 — products, variants, Avro events, FOR UPDATE reservation | DI-01 |
| **2** | Cart + Checkout | 5 — anonymous cart merge, single-page checkout, Spring Modulith outbox saga | Q1 binding |
| **3** | Payment + Idempotency | 5 — Stripe Elements iframe, `(order_id, saga_step_name)` idempotency, gateway Lua rate-limiter, HMAC event signing | DI-02, R-03, R-05, R-15 |
| **4** | Order + Fulfillment | 6 — append-only log, price-snapshot, carrier-agnostic shipment | — |
| **5** | Customer + Auth | 7 — Customer ≠ User aggregate, PDPD export, RBAC, MFA, loyalty, PricingService stub | AT-02, LC-01 |
| **6** | Search | 4 — per-locale ES `catalog_vi_<env>`, Vietnamese analyzer, faceted, rule-based recs | R-07 |
| **7** | Returns | 3 — exchange-first RMA, partial returns, cumulative-refund safety | DI-07 |
| **8** | Admin UI | 4 — role-gated Next.js routes, audit trail, approval workflows, reviews module | — |
| **9** | Notification + VN Tax | 5 — incl. **Story 9.2b** accountant-input-collection (Q5 closure) | LC-03, R-06 |
| **10** | Observability + Chaos | 5 — LGTM, Chaos Mesh per P0 risk, OPA admission, runbooks, e2e saga test | validates R-01..R-15 |

---

## 5. The 26 ADRs — what they bind (read BEFORE coding)

| ADR | Decision | Read this if you're coding… |
|---|---|---|
| ADR-01 | **Spring Modulith outbox** saga (Q1 binding) | Order/checkout/payment side |
| ADR-02 | 13 services + Auth folded into Customer | Module structure |
| ADR-03 | Per-service DB | Repository / outbox design |
| ADR-04 | Event-driven foundation + per-locale ES | Any service that emits / consumes events |
| ADR-05 | `util/@SoftUk` for soft-delete uniqueness | Any new JPA entity |
| ADR-06 | Single-warehouse v1 | InventoryService |
| ADR-07 | B2C v1 | Cart, pricing |
| ADR-08 (→ ADR-26) | VN tax-invoice pattern + `vietnam_tax_authority_credential` schema | InvoiceService |
| ADR-09 | REST + BFF | Frontend integration |
| ADR-10 | Next.js 15 App Router + Server Actions | Frontend |
| ADR-11 | Idempotency key `(aggregate_id, saga_step_name)` | Any saga event handler |
| ADR-12 | Saga = intra-process state machine on order aggregate; 10-state enum + `order_state_transition` log | Order service |
| ADR-13 | Rate-limiter Lua with `redis.call('TIME')` (fixes local-docs/04 bug) | Gateway |
| ADR-14 | Per-service outbox + Modulith outbox bridge (no Debezium in v1) | Any service that publishes events |
| ADR-15 | Avro strict backward+forward compat, CI gate | Avro schema authors |
| ADR-16 | OTel + LGTM + Chaos Mesh, one chaos per P0 risk | Platform/observability |
| ADR-17 | K8s + Helm + ArgoCD GitOps | Deployment |
| ADR-18 | HashiCorp Vault secrets | All secrets |
| ADR-19 | OPA/Rego admission for Kafka + schema + DB pool | Platform/ops |
| ADR-20 | HMAC event signing (HS256, Vault-backed) | Event publisher/consumer |
| ADR-21 | Webhook dedup on Stripe `event.id` | PaymentService |
| ADR-22 | Snowflake strict mode (throw if `POD_NAME` missing) | util library |
| ADR-23 | PCI scope (Stripe Elements + OTel log redaction) | Payment + observability |
| ADR-24 | Card-testing defense (`IP + card-fingerprint + ASN`) | Gateway + Payment |
| ADR-25 | Vietnamese diacritic search | SearchService |
| ADR-26 | Vietnamese tax-invoice (Jasper + QR + serialized) | InvoiceService |

**Deep-dive** for ADR-01, ADR-04, ADR-09, ADR-10, ADR-20, ADR-26 lives in `architecture-detail.md` (companion file).

---

## 6. Risk-binding map (which story mitigates which root cause)

| Risk / Root cause | Story that mitigates |
|---|---|
| DI-01 oversell race | Story 1.6 (FOR UPDATE + reservation TTL) |
| DI-02 payment double-capture | Story 3.1 (idempotency key) + 3.2 (webhook dedup) |
| DI-07 cumulative refund safety | Story 7.3 (FOR UPDATE in refund issuance) |
| AT-02 credential stuffing | Story 5.4 (account lockout) |
| AT-03 CDC event injection | Story 3.5 (HMAC signing) |
| LC-01 PDPD export | Story 5.2 (data registry + export endpoint) |
| LC-03 VN tax-invoice | Story 9.2 + 9.2b (Q5 closure) |
| R-01 util parent pom | Story 0.1 (must fix before Sprint 0 ships anything else) |
| R-03 webhook dedup | Story 3.2 |
| R-05 card-testing | Story 3.4 (gateway Lua rate-limit + BIN velocity) |
| R-06 VN tax-invoice compliance | Story 9.2 |
| R-15 PCI scope creep | Story 3.3 (Stripe Elements + log redaction) |
| R-22 Snowflake strict mode | Story 0.5 |

**Full 15-entry risk register** lives in `addendum.md` (PRD companion) §A1.

---

## 7. What to do if you're a specific role

### If you're the **Sprint Lead / PM**
1. Open `_bmad-output/implementation-artifacts/sprint-status.yaml` to see what's done.
2. Skim the **Introduction** in `prd.md` for vision; **§12 Compliance and Regulatory** for hard rules (Vietnam PDPD, tax-invoice, PCI).
3. Promote a story from `backlog` → `ready-for-dev` when its full epics.md story block is ready to be handed to Dev.

### If you're the **Architect**
1. Open `architecture.md` → see the 26 ADRs + the critical-risk-binding table.
2. Decisions are BINDING. If you need to change one, write a new ADR or update with rationale + update `reviewCycle`.
3. Detail pages in `architecture-detail.md`.

### If you're the **Dev agent** (Amelia)
1. Open **`epics.md`** — find the story you're implementing (Story IDs: `E.S`). Read its AC in Given/When/Then form.
2. Open **`architecture.md`** for the relevant ADR (cross-ref in epics.md points you to which ADR to read).
3. Open **`sprint-status.yaml`** and update your story key from `backlog` → `in-progress` → `review` → `done`.
4. **Mandatory patterns** to follow (per §"Implementation Patterns"): structured JSON logging, Given/When/Then tests, EventListener with `processed_event` dedup, util BaseEntity for new JPA entities.

### If you're the **QA agent**
1. Sprint 10 Story 10.5 is the **end-to-end saga test under failure** — read it first.
2. Use Testcontainers for integration tests; one chaos experiment per P0 risk (in `platform/chaos/chaos-mesh/`).

### If you're the **Reviewer**
1. Read the story's `**Implements:**` line in `epics.md` — that's the FR contract.
2. Look for: are the AC tested? Does the implementation follow the patterns in `architecture.md` §"Implementation Patterns"? Did the architect's risks (R-XX) get mitigated?

---

## 8. Workflow continuation

If you've been spawned to **continue the BMad pipeline**:

| If you're at stage… | Next skill to invoke |
|---|---|
| Brainstorm | `bmad-prd` |
| PRD | `bmad-create-architecture` |
| Architecture | `bmad-create-epics-and-stories` |
| Epics | `bmad-check-implementation-readiness` |
| IR report | `bmad-sprint-planning` |
| Sprint plan | `bmad-story-automator` (last step; builds dev artifacts) |
| Any state | `bmad-help` (recovery / next-step advice) |

---

## 9. Hard rules — DO NOT VIOLATE (see also `HARD-RULES.md`)

For the **full consolidated hard rules** (4 absolute-most-critical + 13 categories including Security, Idempotency, PCI-DSS, VN Compliance, Code Style, A11y, Feature Flags, etc.), see **`HARD-RULES.md`**. The 4 absolute-most-critical (NEVER violate):

1. **NEVER log a PAN** (Primary Account Number) or any card-shaped field (`\d{13,19}`). R-15.
2. **NEVER store a PAN in any database column.** R-15.
3. **NEVER commit `.env` files or hardcode secrets.** All secrets in HashiCorp Vault. ADR-18.
4. **NEVER use a per-retry Stripe idempotency key.** Use stable `sha256(order_id + ":" + saga_step_name)`. ADR-11, mitigates DI-02.

Additionally, BMad workflow rules:

1. **Don't split** a workflow into multiple files. One workflow = one canonical file (+ companion if warranted).
2. **Don't create** files in `prds/prd-.../` except for the canonical PRD family (prd.md, addendum.md, .decision-log.md). Architecture / epics belong in `planning-artifacts/` flat.
3. **Don't change** an ADR without a rationale comment + `reviewCycle` bump.
4. **Don't skip** binding risk mitigations in stories; each `R-`, `DI-`, `AT-`, `LC-` prefix in a story's description is a constraint, not a mention.
5. **Don't add** debian-jvm dependent dependencies without consulting architecture — Boot 4 + Spring Cloud 2025.1 versions are pinned.
6. **Don't use Debezium** — out of scope per ADR-14 (Modulith outbox bridge covers this).
7. **Vietnamese-first is non-negotiable** — locale formatting, diacritic search, tax-invoice all bind per PRD.
8. **Card-testing / PCI scope**: never log PAN; never enable default request-body logger.

For the full list (~83 hard rules), see `HARD-RULES.md`.

---

## 10. Quick command reference

```bash
# Check sprint status
cat _bmad-output/implementation-artifacts/sprint-status.yaml | head -30

# Read all ADRs (summary)
grep "^| \*\*ADR-" _bmad-output/planning-artifacts/architecture.md | head -30

# Find a story's AC
grep -A 20 "^### Story 1.6:" _bmad-output/planning-artifacts/epics.md

# Find an ADR detail (in companion)
grep -A 5 "^### Detail: ADR-20" _bmad-output/planning-artifacts/architecture-detail.md

# See full risk register
grep -E "^\| (R|DI|AT|LC|OP)" _bmad-output/planning-artifacts/addendum.md

# Find a hard rule
grep -B 1 "NEVER" _bmad-output/HARD-RULES.md
```

---

## 11. Quickref index (41 quickref + onboarding docs)

All 41 quickref docs in `_bmad-output/`. Use this index to find the right doc for your role.

### Entry point (3)

| Doc | When to read |
|---|---|
| **`AGENT-ONBOARDING.md`** (this file) | First-time setup, full state |
| **`LOCAL-DEV-SETUP-CHECKLIST.md`** | 30-min dev platform setup |
| **`GLOSSARY.md`** | Acronyms + terms (200+) |

### Hard rules (1)

| Doc | When to read |
|---|---|
| **`HARD-RULES.md`** | 100% non-negotiable rules (consolidated from all docs) |

### Architecture (5)

| Doc | When to read |
|---|---|
| **`ADR-INDEX.md`** | 26 ADRs 1-paragraph each |
| **`ARCHITECTURE-QUICKREF.md`** | 1-page digest |
| **`ARCHITECTURE-DIAGRAMS.md`** | C4 + sequence + state machine (Mermaid) |
| **`DECISION-LOG-CHEATSHEET.md`** | How to add/update ADRs |
| **`PROBLEM-DOMAINS-MAP.md`** | 14 services + events + boundaries |

### Domain (4)

| Doc | When to read |
|---|---|
| **`API-CONTRACT.md`** | REST endpoints per service |
| **`DATA-MODEL.md`** | ERD + per-service schema |
| **`SECURITY-MODEL.md`** | Trust boundaries + auth + PCI |
| **`COMPLIANCE-VN.md`** | VN-specific compliance deep-dive |

### Risk + observability (3)

| Doc | When to read |
|---|---|
| **`RISK-REGISTER.md`** | 15 risks with mitigations |
| **`OBSERVABILITY-RUNBOOK.md`** | OTel + LGTM + chaos |
| **`ALERTING-RUNBOOK.md`** | Per-alert playbooks |

### Dev / QA / Ops / Frontend (10)

| Doc | When to read |
|---|---|
| **`SPRINT-0-ONBOARDING.md`** | Sprint 0 day-1 (R-01 fix) |
| **`SPRINT-1-DEV-HANDBOOK.md`** | Sprint 1 step-by-step |
| **`EPIC-1-STORIES-QUICKREF.md`** | Per-story cards |
| **`INTEGRATION-TEST-CHEATSHEET.md`** | Testcontainers + JUnit |
| **`FRONTEND-HANDBOOK.md`** | Next.js 15 |
| **`DEVOPS-RUNBOOK.md`** | Local + K8s + Vault |
| **`QA-AGENT-HANDBOOK.md`** | Chaos + e2e + regression |
| **`CONTRIBUTING.md`** | PR conventions + CI |
| **`CACHING-STRATEGY.md`** | Redis patterns + key naming |
| **`TEST-DATA-MANAGEMENT.md`** | Factory pattern + NO PII + Vietnamese data |

### Last BMad step (1)

| Doc | When to read |
|---|---|
| **`STORY-AUTOMATOR-CHEATSHEET.md`** | How to invoke bmad-story-automator (last BMad step) |

### Governance (3)

| Doc | When to read |
|---|---|
| **`REVIEWER-GUIDE.md`** | Senior dev code review |
| **`FEATURE-FLAGS.md`** | Flag policy + kill switch |
| **`CONVENTIONS.md`** | Naming + style + anti-patterns (consolidated) |

### Release / Incident (3)

| Doc | When to read |
|---|---|
| **`RELEASE-PROCESS.md`** | v1.0 launch procedure + rollback |
| **`BUG-TRIAGE.md`** | Post-launch incident handling |
| **`COMPLIANCE-AUDIT-CHECKLIST.md`** | Pre-launch audit (PCI + PDPD + VN tax) |

### Ops / SRE (3)

| Doc | When to read |
|---|---|
| **`CAPACITY-PLANNING.md`** | 50k → 500k orders/day scale plan |
| **`DISASTER-RECOVERY.md`** | 10 disaster scenarios + RTO/RPO |
| **`ON-CALL-ROSTER.md`** | Rotation + escalation + handoff |

### Infrastructure (3)

| Doc | When to read |
|---|---|
| **`KAFKA-TOPIC-LIFECYCLE.md`** | Add/evolve/retire topics + Avro compat |
| **`METRICS-DICTIONARY.md`** | 50+ per-metric reference |
| **`A11Y-CHECKLIST.md`** | WCAG 2.1 AA + per-component |

### Meta (2)

| Doc | When to read |
|---|---|
| **`AGENT-INTERACTION.md`** | Multi-agent collaboration + handoff patterns |
| **`CHANGELOG.md`** | Keep-a-Changelog format + conventional commits |

### Total: 41 quickref + onboarding docs (~17,500 lines / ~78K words)

---

## 12. Last update

- **Date:** 2026-07-06
- **Status:** All 7 BMad planning artifacts at 10/10 quality; 41 quickref docs covering every persona + topic; Sprint 0 ready to start.
- **Next BMad step:** `bmad-story-automator` (the only remaining step in the pipeline).
- **Sprint 0 critical path:** Story 0.1 (fix util/ parent pom — R-01) is the only true blocker for everything else.

If you can't find an answer here, ask the human: "Should I use bmad-help?"
