---
audience: senior-dev reviewing PRs
project: side-project
date: 2026-07-06
how-to-use: senior dev / reviewer. Read this BEFORE reviewing any PR. Pair with CONTRIBUTING.md.
---

# Reviewer Guide — side-project

> **Goal:** Reviews should be thorough but fast. Average review time: 30-60 min. Balance blocking vs non-blocking feedback. Approve when ready.
> **You are the last gate before code merges.** A missed bug here means it ships. Take it seriously.

---

## 1. Review SLA

| Severity | SLA | Action |
|---|---|---|
| **Critical** (P0 risk not mitigated, security flaw) | Same day | Block merge + page author |
| **High** (NFR breach, data correctness, edge case) | 1 business day | Block merge |
| **Medium** (idiomatic / patterns / test coverage) | 2 business days | Block merge if simple fix, else approve with follow-up issue |
| **Low** (stylistic, naming) | 1 week | Approve with non-blocking comment |

If you can't meet the SLA, reassign in PR review settings.

---

## 2. Pre-review checklist (5 min)

Before reading code:

- [ ] PR title matches convention: `<type>(<scope>): <description> [story N.M]`
- [ ] PR description references a story + FRs
- [ ] Linked issue / Sprint / Risk (if applicable)
- [ ] CI is green (don't review broken code)
- [ ] No `.env` files, no hardcoded secrets
- [ ] No `package-lock.json` or `node_modules/` changes that aren't intentional
- [ ] Diff size is reasonable (<800 lines, excluding generated files)

---

## 3. The 5-question review framework

For every PR, ask these 5 questions in order. Most PRs pass Q1-Q4; Q5 is the high-leverage one.

### Q1: Does it do what the story says?

- Read the story's AC in `epics.md`
- Read the PR's "What" / "Why" / "Changes" sections
- Verify the code matches the AC (Given/When/Then)
- Spot-check 1-2 G/W/T conditions against the implementation
- If the PR claims "fixes DI-01" — does the test actually exercise the race?

### Q2: Does it follow architecture patterns?

Per `architecture.md` §"Implementation Patterns":

- [ ] **Structured JSON logging** with MDC traceId + spanId
- [ ] **util `BaseEntity`** for new JPA entities
- [ ] **util `SnowflakeIdGenerator`** for new aggregate IDs
- [ ] **util `JasperUtils`** for any report rendering
- [ ] **Outbox pattern** for atomic state + event publish (NEVER direct Kafka publish)
- [ ] **HMAC event signing** (per ADR-20) for any event
- [ ] **processed_event dedup** for event consumers
- [ ] **Per-service DB**, no cross-service JOINs
- [ ] **Stripe webhook signature** verified (not skipped)
- [ ] **Saga idempotency key** stable `(order_id, saga_step_name)`, not per-retry
- [ ] **Vietnam-first**: locale formatting, diacritic search, tax-invoice schema

### Q3: Is it safe?

Per `SECURITY-MODEL.md` §12 (Hard rules):

- [ ] **No PAN-shaped fields** in any log (R-15)
- [ ] **No `.env` files** committed
- [ ] **No hardcoded secrets** (use Vault, per ADR-18)
- [ ] **No `System.out.println`** (use logger)
- [ ] **No default request-body logger** (R-15)
- [ ] **No string concatenation** in SQL (parameterized queries)
- [ ] **No `dangerouslySetInnerHTML`** without sanitization
- [ ] **No `localStorage`** for auth tokens (PCI)
- [ ] **No per-retry Stripe idempotency key** (DI-02)
- [ ] **No card-shaped data in URLs** (logged in proxy)
- [ ] **No CSRF skip** on state-changing endpoints
- [ ] **No HMAC skip** on inbound events

If any safety rule is violated: **block merge immediately** + tag as security issue.

### Q4: Are tests adequate?

- [ ] Unit tests cover the AC (Given/When/Then)
- [ ] Integration tests for cross-component interactions
- [ ] For critical-path tests (saga, reservation, refund): 100x consecutive pass
- [ ] No flaky tests (anything time-dependent should be controlled)
- [ ] No `Thread.sleep` in tests (use Awaitility)
- [ ] No H2 in tests (use Testcontainers Postgres for `FOR UPDATE` etc.)
- [ ] Coverage didn't drop (check CI report)
- [ ] Test data is hermetic (no shared state between tests)

### Q5: Will this work in production?

This is the high-leverage question. Most reviewers skip it.

- [ ] **Latency impact** — does this add to a hot path? Measure p99.
- [ ] **Memory footprint** — any large object allocation? Risk of OOM under load?
- [ ] **Concurrency safety** — any shared mutable state? Idempotency under retry?
- [ ] **Failure modes** — what if Kafka is down? What if DB is down? What if a third-party API times out?
- [ ] **Observability** — does the change have OTel spans + metrics? Can an SRE debug this in Tempo at 2am?
- [ ] **Rollback** — if this goes to prod and breaks, how do we roll back?
- [ ] **Config** — does it require new env vars, Vault entries, or feature flags?
- [ ] **Migration** — does it require a DB migration? Is the migration reversible?

---

## 4. Reviewer's specific role: which files trigger extra scrutiny

| File path | Why | What to look for |
|---|---|---|
| `services/payment/**` | R-15 PCI scope | No PAN in logs, Stripe webhook signature verified, idempotency key stable |
| `services/checkout/**` | R-02/DI-01 saga | FOR UPDATE on inventory, idempotency key, saga state stored on aggregate |
| `services/order/**` | R-30 saga state | State transitions are validated, no out-of-order events |
| `services/inventory/**` | R-02/DI-01 reservation | FOR UPDATE + TTL, sweeper runs |
| `services/customer/auth/**` | AT-02 credential stuffing | Account lockout, MFA TOTP, RBAC server-side |
| `services/invoice/**` | LC-03/Q5 tax-invoice | Credential check, fail-fast without credentials, Jasper template + QR |
| `platform/chaos/**` | New chaos experiments | Targets a real P0 risk, has a verification step |
| `util/**` | Shared library | New code affects all 13 services; backwards-compat critical |
| `architecture.md`, `prd.md` | Source of truth | These are binding; small wording changes have big impact |
| `helm/**` | Production deploy | K8s manifests, secrets via Vault (not in repo) |
| `frontend/storefront/**` | R-15 PCI scope | Stripe Elements iframe, no raw card data |
| `.env*` | SECRETS | These should NEVER be committed |

---

## 5. Comment style

### Good comment

```markdown
🔴 Blocking — Line 47: this loop has an O(n²) worst case. With 10k orders in cart, that's 100M operations per checkout. Suggest:
```java
.mapToLong(this::calculate)
.sum();
```
over a stream, or precompute once. Same correctness, 1000x faster.
```

### Bad comment

```markdown
This is wrong. Fix it.
```

### Use the right emoji to indicate severity

| Emoji | Severity | Action |
|---|---|---|
| 🔴 | Blocking | Must fix before merge |
| 🟡 | Non-blocking suggestion | Author can defer (open issue) |
| 🟢 | Nit / nice to have | Optional |
| ❓ | Question | Author should answer, not necessarily fix |

### Avoid

- ❌ "This is bad code" (no actionable feedback)
- ❌ "I would have done it differently" (not actionable)
- ❌ "Why didn't you use X?" (question without context)
- ✅ "Consider using X here because Y. Not blocking."
- ✅ "🔴 This will deadlock under load. Suggest Z."

---

## 6. Approving vs requesting changes

### Approve when

- All 🔴 issues resolved
- All ❓ questions answered
- CI green
- You would be comfortable shipping this to production today

### Request changes when

- Any 🔴 issue (block merge)
- High-severity bug
- Missing tests for the AC

### Comment + approve when

- All 🔴 resolved, but 🟡/🟢 remain
- Comment explicitly: "Approved with non-blocking suggestions for follow-up"

### Approve + request follow-up

For issues that don't block this PR but should be addressed:

```markdown
Approved ✅

Follow-ups (please open issues):
- Consider extracting [X] to a separate method
- Add load test for this path before Sprint N
- Document the new pattern in architecture.md §"Implementation Patterns"
```

---

## 7. Critical-pattern check (per R-XX)

For each Critical risk, ensure the PR's pattern matches the binding ADR:

### R-01 (util parent pom) — anyone touching `util/pom.xml`

- [ ] `<parent>` is gone OR replaced with inline `<dependencyManagement>`
- [ ] No new external `<parent>` referencing missing files
- [ ] `mvn -pl util -am clean install` succeeds

### R-02/DI-01 (inventory oversell) — anyone touching `services/inventory/**`

- [ ] `SELECT FOR UPDATE` is used for reservation
- [ ] Reservation has TTL + sweeper
- [ ] Concurrent test passes 100x
- [ ] `InsufficientStockException` is the error class

### R-03/DI-02 (payment double-capture) — anyone touching `services/payment/**`

- [ ] Idempotency key is `(order_id, saga_step_name)`, NOT per-retry
- [ ] Stripe webhook signature is verified
- [ ] `webhook_dedup` table is checked on every event
- [ ] No per-retry `UUID.randomUUID()` keys

### R-04 (Debezium outbox duplicates) — anyone touching outbox / Kafka

- [ ] `outbox` table is written in same transaction as business state
- [ ] No direct Kafka publish (always via outbox bridge)
- [ ] `processed_event` dedup table for every consumer
- [ ] HMAC signature verified on every consumed event

### R-05/AT-01 (card-testing) — anyone touching gateway / rate-limiter

- [ ] Rate-limiter key includes `IP + card-fingerprint + ASN`, not just IP
- [ ] Lua uses `redis.call('TIME')`, not gateway wall-clock
- [ ] BIN velocity check
- [ ] No per-user hardcoded thresholds

### R-06/LC-03/Q5 (tax-invoice) — anyone touching `services/invoice/**`

- [ ] `vietnam_tax_authority_credential` row check before issuing
- [ ] Service fails-fast at startup if no credentials
- [ ] Jasper template uses util's Vietnamese fonts
- [ ] QR code via util's `QRCodeUtil`
- [ ] Idempotent on retry (use `tax_invoice_id` as natural key)

### R-15/AT-03 (PCI scope) — anyone touching payment / logging

- [ ] No PAN-shaped fields in any log
- [ ] OTel log redaction is configured
- [ ] Default request-body logger is deny-listed
- [ ] No `System.out.println` in production paths
- [ ] Stripe Elements iframe (NOT custom card form)

---

## 8. Special review situations

### Hot fix / critical bug fix

- Faster review SLA: within hours
- Less formal: comment approval is enough; merge without second reviewer
- Always do a follow-up review + post-mortem after the fact

### New dependency

- Verify architecture compatibility (Boot 4 + Spring Cloud 2025.1 + Java 25)
- Check license compatibility (per CI license-check)
- Check for known CVEs (per OWASP dependency check)
- Update `version matrix` in `addendum.md` A4

### New event schema

- Avro compat check passes
- HMAC signature bound to producer
- Consumer-side signature verification
- Backward + forward compat (per ADR-15)

### New Avro schema with breaking change

- Bumps `architecture-detail.md` §"Detail: ADR-04"
- Bumps `reviewCycle` in `architecture.md`
- New ADR in `architecture.md` §"Core Architectural Decisions" if needed
- Full regression test

### PR that touches multiple services

- Verify cross-service event flow still works
- E2E saga test passes
- All consumer signatures updated
- Per-service integration test passes

---

## 9. Code-style enforcement

CI enforces style; you don't need to police it:

- Java: Spotless + Checkstyle
- TypeScript: Prettier + ESLint
- Markdown: prettier-markdown
- YAML: yamllint

If CI style check fails, the author must fix it. Don't spend review time on style.

---

## 10. Post-approval

- Approve the PR via GitHub UI
- Merge the PR (squash-and-merge is default)
- If you have a follow-up issue, file it now (don't forget)
- Update `sprint-status.yaml` to mark story as `done` (usually done by author or Sprint Lead)
- Communicate in Slack if it's a critical-risk fix

---

## 11. Cross-references

- **PR conventions:** `CONTRIBUTING.md`
- **Architecture patterns to follow:** `architecture.md` §"Implementation Patterns"
- **Security model:** `SECURITY-MODEL.md` §12 (hard rules)
- **Test patterns:** `INTEGRATION-TEST-CHEATSHEET.md`
- **Risk-binding:** `RISK-REGISTER.md`
- **Sprint status:** `_bmad-output/implementation-artifacts/sprint-status.yaml`
- **Architecture review:** `DECISION-LOG-CHEATSHEET.md` (how to add/update ADRs)
- **Local setup:** `LOCAL-DEV-SETUP-CHECKLIST.md`
- **Onboarding:** `AGENT-ONBOARDING.md`
