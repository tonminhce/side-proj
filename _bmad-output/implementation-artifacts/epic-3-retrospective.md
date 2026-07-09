# Epic 3 Retrospective: Pay Securely

**Epic:** 3 — Pay Securely (FR-24, FR-25, FR-26, FR-27, FR-28, FR-29, FR-78, FR-79, FR-81, FR-82; solves DI-02, R-03, R-05, R-15, AT-03 root cause)
**Closed:** 2026-07-09
**Stories:** 5 + 2 follow-ups (3-1, 3-2, 3-3, 3-4, 3-5, 3-5-follow-up-2, 3-5-follow-up-3ds-risk-decision)

## Outcome

All 7 Epic-3 stories moved to `done`. 4 services affected (payment, gateway, checkout, util). One new Maven module (`services/gateway`). Two event buses wired (payment.captured, payment.refunded). HMAC event signing (ADR-20) production-ready in payment service. Card-testing defense (R-05) shipping in gateway.

## What Shipped

- **3-1** — Payment service stable idempotency key (DI-02 root cause). 36/36 tests.
- **3-2** — Stripe webhook dedup (R-03). 11/11 ACs. ArchUnit 3 rules.
- **3-3** — Stripe Elements iframe integration (R-15 boundary). 75 tests. Repo-wide deny-list ArchUnit.
- **3-4** — Gateway rate-limiter + BIN velocity (R-05 / AT-01 root cause). 6 Lua tests + 5 filter tests. New module.
- **3-5** — 3DS step-up + HMAC event signing (AT-03 root cause). Producer-side HMAC + util primitives.
- **3-5 follow-up #2** — Producer-side `payment.captured` / `payment.refunded` outbox (FR-28). Unblocks Story 4.1 saga integration.
- **3-5 follow-up 3DS-risk-decision** — RiskLevel + ThreeDSecureDecision pure function + 23 tests; RealStripePaymentAdapter wires `request_three_d_secure=ANY` on EEA + amount>=€30 + ELEVATED/HIGHEST.

## Deferred (in `_bmad-output/backlog/deferred-issues.md`)

### Story 3.3 (6 items, MEDIUM+LOW)
- StripeClient migration off `Stripe.apiKey` static global (MEDIUM)
- Per-service logback-spring.xml for catalog / inventory / cart / checkout (MEDIUM)
- Smoke-script positive PAN injection test (LOW)
- Cross-service smoke coverage (LOW)
- Story doc AC #1 narrative drift (LOW)
- `application-test.yml` Stripe config verification (LOW)

### Story 3.4 (3 open, 1 closed)
- ✅ Filter integration tests HIGH-2 (closed at commit `91670b7`)
- Re-add util compile dep for PanRedactingAppender coverage (MEDIUM)
- Lua `min_remaining` honesty (LOW)
- gateway.bin-velocity.* config keys surfaced via `@ConfigurationProperties` (LOW)

### Story 3.5 (5 items, MEDIUM)
- `OrderStatus.PAYMENT_REQUIRES_ACTION` enum + saga transition (MEDIUM) — separate story
- Real Vault integration (replacing env-var substitute) (MEDIUM)
- `PaymentMetrics.java` Micrometer counters (security.event.signature.mismatch, hmac.vault.unavailable, payment.requires_3ds) (MEDIUM)
- Consumer-side HMAC verifier wiring in checkout's outbox listener (wontfix-intra-jvm per cycle 5 structural finding)
- Order-side PaymentRefundedOrderAdvancer — already shipped at `c1062be`

## Process Learnings

### Story doc body lag

Stories 3-4 and 3-5 had **all task checkboxes still `[ ]`** and **Completion Notes empty** despite code being committed and tested. Sprint-status tracked the work, but the story doc body didn't reflect it. Cause: the dev cycle ran, review ran, fix ran, commit ran — but the dev agent never back-filled the story markdown.

**Action for next epic:** retro is the right place to back-fill. Don't block close on doc body update. The retro captures deferred items + commit hashes; the story doc body can be patched lazily.

### Orchestrator state drift

The orchestrator's marker file (`pid: 0`, identical `createdAt == heartbeat`) and the disk log (`stepsCompleted: []`, `currentStory: 3.1`, `currentStep: step-02-preflight` — a step that doesn't exist in v1's `sequence: [create, dev, auto, review, retro]`) were both stale. The state was bootstrapped with v0-schema frontmatter and never reconciled against the v1 policy.

**Action for next epic:** before invoking `story-automator`, reconcile the orchestration log + marker against `sprint-status.yaml`. If `agentsFile.stories == []`, the orchestrator can't drive `create` — fall back to direct cycle. Add a startup check: "if currentStep is not in workflow.sequence, abort and fall back to manual."

### Direct cycle path

When the orchestrator's plumbing breaks, the substantive work is still do-able via the memory's cycle pattern: dev → gitnexus analyze → smoke+curl → deep-review agent → apply fixes → retrospective → sprint-status. **Subagent dispatch for the heavy verification phases** (test runs, gitnexus analyze) keeps the main loop's tokens for the decisions.

### Defensive reading of audit reports

A subagent reported "Story 3.5 working-tree code not committed" — but `git log -- <path>` showed commits under `baa5aae`. The subagent's git command was narrower than the umbrella commit's scope. **Lesson:** when a subagent makes a load-bearing claim ("not committed", "not on disk"), re-verify with the simplest possible check (`git status`, `find`) before letting it shape the plan. Cost of verification is low; cost of acting on a wrong claim is high.

## Carry-forward for Epic 4 (Order Fulfillment)

Epic 4 has 4 stories in `review` (4-1, 4-2, 4-3, 4-4) — same review-state-lag pattern as Epic 3. The orchestrator is going to hit the same reconcile wall there. Recommended: same direct-cycle approach, with subagent dispatch for each story's heavy verification.

Story 4.1 (orderservice append-only event log) is **already unblocked** by Epic 3 follow-up #2 (payment.captured/payment.refunded producer). The saga PLACED→PAID integration is ready to land.

## Carry-forward for Epic 3 → ops

- Real Vault integration (Story 3.5 deferred) → sprint planning: 1 day when ops has spring-cloud-starter-vault-config baseline.
- `OrderStatus.PAYMENT_REQUIRES_ACTION` → separate story; needs UX input for 3DS challenge handoff shape.
- `PaymentMetrics.java` → either wire now (4 lines via `MeterRegistry` injection) or drop from the story; it's not load-bearing.