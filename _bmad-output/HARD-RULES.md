---
audience: all-agents
project: side-project
date: 2026-07-06
how-to-use: 100% non-negotiable rules. Read before any code. Cross-checked across all 40 quickref docs.
---

# Hard Rules — side-project

> **TL;DR:** All rules here are **non-negotiable**. Code review must reject PRs that violate. CI must enforce where possible.
> **Source:** Consolidated from `SECURITY-MODEL.md` §12, `CONTRIBUTING.md` §8, `CONVENTIONS.md` §15, `CONVENTIONS.md` §10, plus architectural constraints from `architecture.md` and `RISK-REGISTER.md`.
> **Grouped by concern for easy lookup.**

---

## 0. The 4 absolute-most-critical rules (NEVER violate under any circumstance)

These 4 have **catastrophic** consequences if violated. Always check first.

1. ❌ **NEVER log a PAN (Primary Account Number)** or any card-shaped field (`\d{13,19}`). OTel log redaction must catch this — if it doesn't, your service is in PCI scope violation (R-15).
2. ❌ **NEVER store a PAN in any database column.** Card data flows through Stripe Elements iframe only (per ADR-23).
3. ❌ **NEVER commit `.env` files or hardcoded secrets** (API keys, passwords, tokens). All secrets in HashiCorp Vault (per ADR-18).
4. ❌ **NEVER use a per-retry Stripe idempotency key.** Use stable `sha256(order_id + ":" + saga_step_name)` (per ADR-11, mitigates DI-02).

---

## 1. Security rules (R-15, ADR-23)

- ❌ NEVER log a PAN, CVV, or card numbers (R-15)
- ❌ NEVER store a PAN in any database column (R-15)
- ❌ NEVER enable default request-body logger (R-15)
- ❌ NEVER use `localStorage` for auth tokens (PCI compliance)
- ❌ NEVER put card-shaped data in URLs (logged in proxy logs)
- ❌ NEVER log a per-retry Stripe idempotency key (audit only the first)
- ❌ NEVER skip Stripe webhook signature verification
- ❌ NEVER skip CSRF protection on state-changing endpoints
- ❌ NEVER use HMAC event signing without a per-service Vault key
- ❌ NEVER skip HMAC verification on inbound events (mitigates AT-03)
- ❌ NEVER use `dangerouslySetInnerHTML` without sanitization
- ❌ NEVER use string concatenation to build SQL
- ❌ NEVER store passwords in plain text (use Argon2id per Story 5.4)
- ❌ NEVER use basic auth in production (JWT only)
- ❌ NEVER log client-side role checks (server-side RBAC per FR-74)

---

## 2. Secrets + configuration (ADR-18)

- ❌ NEVER commit `.env` files
- ❌ NEVER hardcode API keys, passwords, or tokens in code
- ❌ NEVER hardcode URLs, ports, or service names
- ❌ NEVER add a library without checking architecture compatibility (Boot 4 + Spring Cloud 2025.1 + Java 25)
- ❌ NEVER put secrets in environment files that are committed to git
- ❌ NEVER bypass Vault (no hardcoded fallback secrets)
- ❌ NEVER log a Vault token
- ❌ NEVER commit a `*.env*` file (only `.env.example` is allowed)
- ❌ NEVER use `localStorage` for sensitive data (auth tokens, PII, etc.)

---

## 3. Saga + idempotency (ADR-11, mitigates DI-02, R-03, AT-03)

- ❌ NEVER use a per-retry idempotency key (use stable `sha256(order_id + ":" + saga_step_name)`)
- ❌ NEVER skip the `processed_event` dedup check on consumer (use idempotency on every event handler)
- ❌ NEVER skip saga compensation — if a step fails, compensate
- ❌ NEVER use a per-retry timing for `WEBHOOK_DEDUP` lookup
- ❌ NEVER skip event signature verification (HMAC per ADR-20)
- ❌ NEVER modify `outbox` rows directly from application code (use Modulith outbox bridge)
- ❌ NEVER publish to Kafka directly from business code (always via outbox)
- ❌ NEVER use a non-stable identifier for idempotency (UUID.randomUUID per retry = WRONG)
- ❌ NEVER skip state transitions in saga (always go through the 10-state machine per ADR-12)

---

## 4. PCI-DSS scope (R-15, ADR-23)

- ❌ NEVER log a PAN (R-15)
- ❌ NEVER store a PAN in any database column (R-15)
- ❌ NEVER enable default request-body logger (R-15)
- ❌ NEVER skip card data flow through Stripe Elements iframe (R-15)
- ❌ NEVER process card data in our servers (Stripe handles)
- ❌ NEVER store card BIN + last 4 separately (per PCI — store only what's necessary)
- ❌ NEVER use a default-deny authorization policy (whitelist, not blacklist)
- ❌ NEVER skip rate-limit on payment endpoints (R-05)
- ❌ NEVER use `localStorage` for any auth-related data
- ❌ NEVER trust client-side input without server-side validation
- ❌ NEVER log full request bodies (use sampled, structured logging)

---

## 5. Code style (per CONVENTIONS.md + CONTRIBUTING.md)

- ❌ NEVER use `System.out.println` in production code (use SLF4J logger)
- ❌ NEVER commit code without running `mvn spotless:apply` first
- ❌ NEVER commit code without running `npm run format` first (frontend)
- ❌ NEVER push directly to main — always use PRs
- ❌ NEVER merge your own PR — always have a reviewer
- ❌ NEVER skip tests ("we'll add later")
- ❌ NEVER mix layers (API layer with business logic; see CONVENTIONS §15)
- ❌ NEVER use `any` type in TypeScript (use proper types)
- ❌ NEVER use `null` in TypeScript (use `undefined` or `?` for optional)
- ❌ NEVER commit code with merge conflicts unresolved

---

## 6. CI / git workflow (per CONTRIBUTING.md)

- ❌ NEVER push directly to main — always use PRs
- ❌ NEVER merge your own PR — always have a reviewer
- ❌ NEVER skip CI (force-push to bypass)
- ❌ NEVER commit without a meaningful commit message (per Conventional Commits)
- ❌ NEVER modify ADRs in `architecture.md` without following `DECISION-LOG-CHEATSHEET.md`
- ❌ NEVER commit without running Avro compat check (if Avro schema changed)
- ❌ NEVER commit without running ArchUnit (Modulith boundary check)
- ❌ NEVER commit without running Spotless + Prettier
- ❌ NEVER commit code that doesn't compile or fails tests
- ❌ NEVER bypass the review process (even for "trivial" changes)

---

## 7. Data + schema (per DATA-MODEL.md + addendum)

- ❌ NEVER mix plural and singular table names (use `users` not `user`; `orders` not `order`)
- ❌ NEVER use mixed case in DB columns (use `snake_case` per DATA-MODEL §3)
- ❌ NEVER hard-delete (use `is_deleted` per `@SoftUk` per ADR-05)
- ❌ NEVER drop a column without expand-then-contract migration (per NFR-MIG-1)
- ❌ NEVER change an Avro schema's type without CI compat check (per ADR-15)
- ❌ NEVER add a column without a default (can break old code)
- ❌ NEVER use a SELECT * in production (select explicit columns)
- ❌ NEVER cross-DB JOIN (per ADR-03; use aggregate IDs + events)
- ❌ NEVER use string-concat SQL (parameterized queries; see SECURITY-MODEL §10)
- ❌ NEVER put PII in URL paths (logged in proxy logs)
- ❌ NEVER skip the `customer_data_registry` (per FR-46 / LC-01)

---

## 8. Vietnamese compliance (per COMPLIANCE-VN.md + FR-78 / LC-03 / Q5)

- ❌ NEVER issue a paper tax invoice (illegal in Vietnam since 1 July 2022)
- ❌ NEVER issue a tax invoice without a `vietnam_tax_authority_credential` row (per Q5 closure)
- ❌ NEVER issue a tax invoice without the QR code (per Circular 78/2021)
- ❌ NEVER issue a tax invoice with the wrong merchant tax code (MST)
- ❌ NEVER use a per-retry `tax_invoice_id` (use stable natural key)
- ❌ NEVER hard-delete a PII field if the user has an R2F request pending (per FR-49)
- ❌ NEVER log a customer's PAN + their order together (PDPD + PCI)
- ❌ NEVER transfer customer data outside Vietnam without explicit consent (PDPD)
- ❌ NEVER keep PII longer than 7 years (VN tax law) unless anonymized
- ❌ NEVER issue a tax invoice without Vietnamese fonts (per util)

---

## 9. Code quality (per CONVENTIONS.md §15)

- ❌ NEVER share mutable state between tests (use fresh data per test)
- ❌ NEVER use real PII in tests (use Vietnamese placeholder names; per addendum §A3)
- ❌ NEVER use real phone numbers in tests (use `+84 9XX-XXX-XXX` random pattern)
- ❌ NEVER use real email addresses in tests (use `@example.com` domain)
- ❌ NEVER use real credit card numbers in tests (use Stripe test cards `4242 4242 4242 4242`)
- ❌ NEVER use real bank accounts in tests (even test mode)
- ❌ NEVER use real names in tests (use Vietnamese placeholders like `Nguyen Van Test`)
- ❌ NEVER use `sk_live_*` in tests (use `sk_test_*` only)
- ❌ NEVER use `thrd.sleep` in tests (use Awaitility with timeout)
- ❌ NEVER use H2 in tests (use Testcontainers Postgres, per INTEGRATION-TEST-CHEATSHEET §2)
- ❌ NEVER share state across test classes (each test is hermetic)
- ❌ NEVER depend on a specific test execution order (each test self-contained)
- ❌ NEVER mutate global state in tests (TimeZone, env vars, etc. — save + restore)

---

## 10. Observability + monitoring (per OBSERVABILITY-RUNBOOK.md)

- ❌ NEVER log a PAN in any log (per R-15)
- ❌ NEVER log structured data without traceId + spanId (for OTel correlation)
- ❌ NEVER use `localStorage` for any monitoring data
- ❌ NEVER disable health checks in production
- ❌ NEVER skip OTel instrumentation on a new endpoint
- ❌ NEVER add an alert without a runbook (per ALERTING-RUNBOOK.md)
- ❌ NEVER ignore a Critical alert (Sev-0/1)
- ❌ NEVER have a metric without a name, type, and unit
- ❌ NEVER use high-cardinality labels in metrics (e.g., `user_id`)
- ❌ NEVER skip OTel log redaction for card-shaped fields

---

## 11. Performance + SLO (per CAPACITY-PLANNING.md + NFR-PERF)

- ❌ NEVER break an SLO without an SLO breach ticket + post-mortem
- ❌ NEVER skip performance testing before a 10x scale
- ❌ NEVER run with `--insecure-skip-tls` or similar in production
- ❌ NEVER ignore Kafka consumer lag (alert at p95 > 30s)
- ❌ NEVER skip auto-scaling configuration
- ❌ NEVER use synchronous calls in a hot path (use events or async)
- ❌ NEVER skip connection pool sizing (HikariCP max ~ 2-3x concurrent request volume)

---

## 12. Operational (per DEVOPS-RUNBOOK.md)

- ❌ NEVER skip K8s liveness + readiness probes
- ❌ NEVER use `latest` tag in production (pin to specific version)
- ❌ NEVER deploy without proper health checks
- ❌ NEVER skip Helm chart versioning
- ❌ NEVER bypass Vault for secrets
- ❌ NEVER commit K8s secrets in repo
- ❌ NEVER run a service without proper resource limits (CPU + memory)
- ❌ NEVER run with `hostNetwork: true` in production (security risk)
- ❌ NEVER skip ArgoCD sync (drift detection)

---

## 13. VN-specific (per COMPLIANCE-VN.md)

- ❌ NEVER issue a paper invoice (since 1 July 2022, all e-invoices)
- ❌ NEVER issue without a serial number (allocated by tax authority)
- ❌ NEVER issue without a QR code
- ❌ NEVER issue without MST (Mã Số Thuế) on the invoice
- ❌ NEVER use a tax rate outside Vietnam's VAT rules (10% standard, 5% reduced, 0% zero-rated)
- ❌ NEVER process sensitive personal data (political, religious, biometric, health) without explicit separate consent (PDPD)
- ❌ NEVER transfer customer data outside Vietnam without consent (PDPD)
- ❌ NEVER sell user data (PDPD)

---

## 14. Accessibility (per A11Y-CHECKLIST.md)

- ❌ NEVER use `localStorage` for accessibility state
- ❌ NEVER disable browser zoom (per addendum A3)
- ❌ NEVER use color alone to convey meaning (use icons + text)
- ❌ NEVER use color contrast below 4.5:1 (WCAG AA)
- ❌ NEVER use placeholder text as a label
- ❌ NEVER use auto-playing audio
- ❌ NEVER disable keyboard navigation
- ❌ NEVER use raw `<div onClick>` instead of `<button>`
- ❌ NEVER use `dangerouslySetInnerHTML` without sanitization
- ❌ NEVER use color as the only status indicator

---

## 15. Feature flags (per FEATURE-FLAGS.md)

- ❌ NEVER use a flag without an `expires_after` date
- ❌ NEVER use a flag for permanent config (use config / Vault)
- ❌ NEVER use one flag for two unrelated features
- ❌ NEVER ship a flag without testing BOTH on and off states
- ❌ NEVER use a flag for A/B testing at scale (use a real A/B platform)
- ❌ NEVER skip flag ownership assignment
- ❌ NEVER forget to remove expired flags (technical debt)

---

## 16. Per role — quick lookup

### For Dev (Amelia)

Read §1 (security), §2 (secrets), §3 (idempotency), §5 (code style), §7 (data), §9 (test quality).

### For Frontend dev

Read §5 (code style), §14 (accessibility), §10 (observability).

### For QA agent

Read §9 (test quality), §5 (test patterns).

### For Architect (Winston)

Read §7 (data), §10 (observability), §11 (performance), §12 (operational).

### For SRE

Read §10 (observability), §11 (performance), §12 (operational), §2 (secrets).

### For PM (John)

Read §1 (security), §8 (VN compliance), §13 (VN-specific), §3 (saga impact).

### For Vietnamese accountant (L4)

Read §8 (VN compliance), §13 (VN-specific).

### For Senior dev / Reviewer

Read §1 (security), §3 (idempotency), §5 (code style), §6 (CI), §15 (flags).

### For Security

Read §1 (security), §2 (secrets), §4 (PCI), §8 (VN compliance).

### For On-call SRE

Read §10 (observability), §11 (performance), §12 (operational).

---

## 17. Enforceability summary

| Rule | Enforced how |
|---|---|
| §0 #1 no PAN in logs | OTel log redaction processor (must catch `\d{13,19}`) + SpotBugs rule + pen-test |
| §0 #2 no PAN in DB | Schema check (grep column names) + pen-test + R-15 risk |
| §0 #3 no .env / no secrets | git-ignore pre-commit + Vault policy + pen-test + per-service check |
| §0 #4 stable idempotency key | CI lint that rejects `UUID.randomUUID()` in payment paths |
| §1 no PAN in logs | OTel processor + R-15 alerting per `ALERTING-RUNBOOK.md` |
| §1 no PAN in DB | Per-service DB permission check + schema review |
| §1 Stripe webhook sig verify | Per-endpoint integration test (per `INTEGRATION-TEST-CHEATSHEET.md`) |
| §2 no .env in repo | `.gitignore` pre-commit + git-hooks |
| §2 no hardcoded secrets | git-secrets + SpotBugs rule + pen-test |
| §3 stable idempotency key | CI lint + per-service unit test |
| §3 no Kafka direct publish | CI lint that rejects `kafkaTemplate.send` outside the outbox |
| §4 Stripe Elements iframe only | Per-page UI test |
| §5 no System.out.println | SpotBugs / Checkstyle rule |
| §5 Spotless on every commit | Pre-commit hook + CI |
| §6 no direct push to main | GitHub branch protection rules |
| §6 no merge own PR | GitHub branch protection + CODEOWNERS |
| §7 no cross-DB JOIN | Code review (per `REVIEWER-GUIDE.md` §3 Q2) |
| §7 no string-concat SQL | SpotBugs SQL injection rule + code review |
| §8 no paper invoice | N/A (no code change) — process check |
| §8 no tax invoice without credential | CI gate per Q5 closure |
| §8 no tax invoice without QR | Jasper template check |
| §10 no PAN in logs | (covered in §0 / §1) |
| §11 no SLO breach without ticket | Alertmanager → PagerDuty → ticket |
| §12 no latest tag | Helm chart review (per `DEVOPS-RUNBOOK.md` §10) |

---

## 18. When in doubt

- **Check `RISK-REGISTER.md`** for the 15 risks + their mitigations
- **Check `SECURITY-MODEL.md` §12** for security hard rules
- **Check `CONTRIBUTING.md` §8** for PR-time hard rules
- **Check `CONVENTIONS.md` §15** for code-style hard rules
- **Ask the orchestrator** (per `AGENT-INTERACTION.md`)

---

## 19. The BMad agent cast — what each must read

| Agent | Read at start of work |
|---|---|
| All agents | This file (HARD-RULES.md) + AGENT-ONBOARDING.md |
| PM (John) | + PR-INVENTORY-PRD-CONTENT-AUDIT-CHECKLIST.md + COMPLIANCE-VN.md |
| Architect (Winston) | + ARCHITECTURE-QUICKREF.md + DECISION-LOG-CHEATSHEET.md |
| Dev (Amelia) | + SPRINT-1-DEV-HANDBOOK.md + CONVENTIONS.md + INTEGRATION-TEST-CHEATSHEET.md |
| Frontend dev | + FRONTEND-HANDBOOK.md + A11Y-CHECKLIST.md + CONVENTIONS.md |
| QA agent | + QA-AGENT-HANDBOOK.md + INTEGRATION-TEST-CHEATSHEET.md + TEST-DATA-MANAGEMENT.md |
| SRE | + DEVOPS-RUNBOOK.md + OBSERVABILITY-RUNBOOK.md + ALERTING-RUNBOOK.md + ON-CALL-ROSTER.md |
| Security | + SECURITY-MODEL.md + COMPLIANCE-AUDIT-CHECKLIST.md |
| Reviewer | + REVIEWER-GUIDE.md + CONVENTIONS.md |
| Tech Writer | + AGENT-ONBOARDING.md + CONVENTIONS.md (style) |
| Vietnamese accountant | + COMPLIANCE-VN.md + ARCHITECTURE-DETAIL.md §"Detail: ADR-26" |
| Vietnamese tax reviewer | + ARCHITECTURE-DETAIL.md §"Detail: ADR-26" + COMPLIANCE-VN.md |

---

## 20. Cross-references

- **Source of all hard rules:**
  - `SECURITY-MODEL.md` §12 (security)
  - `CONTRIBUTING.md` §8 (PR-time)
  - `CONVENTIONS.md` §15 (code style)
  - `CONVENTIONS.md` §10 (anti-patterns)
  - `architecture.md` (ADRs)
  - `RISK-REGISTER.md` (risk mitigations)
- **Onboarding:** `AGENT-ONBOARDING.md`
- **Glossary:** `GLOSSARY.md`
- **All 40 quickref:** see `AGENT-ONBOARDING.md` for index
