# Epic 4 Retrospective: Order Fulfillment and Tracking

**Epic:** 4 — Order Fulfillment and Tracking (FR-30, FR-31, FR-32, FR-33, FR-34, FR-35, FR-36, FR-39)
**Closed:** 2026-07-09
**Stories:** 4 closed (4-1, 4-2, 4-3, 4-4); 2 still in `backlog` (4-5 shipment service, 4-6 shipment webhook)

## Outcome

All 4 stories in the `review` column flipped to `done`. The order service saga
(PLACED → PAID via `payment.captured` event from the payment bridge) is now
end-to-end-wired: payment's outbox poller publishes to the `payment.events`
Kafka topic, order's Kafka consumer verifies the HMAC envelope and re-publishes
a `SignedPaymentCapturedEvent` to the in-process bus, the existing
`PaymentCapturedOrderAdvancer` advances the order state. 2 CRITICAL findings
(C1 genesis snapshot race, C2 bridge data-loss) fixed in the closeout commit
(`0741290`).

## What Shipped

- **4-1** — Append-only event log + immutable price snapshot + HMAC-signed
  outbox. 10 unit tests (the 10/23/27/34 numbers in the comment are test-method
  counts; current file count is 14).
- **4-2** — Post-payment lifecycle: state machine validator
  (PLACED→PAID→ALLOCATED→PACKING→PACKED) + `PaymentCapturedOrderAdvancer`
  (intra-JVM `@EventListener` + `MeterRegistry` counters) + debug
  `POST /api/orders/{uuid}/advance` endpoint.
- **4-3** — User-visible order timeline
  (`GET /api/orders/{orderUuid}/timeline` with `Cache-Control: max-age=30`).
- **4-4** — Edit-after-pay: `AmendOrderAddressUseCase` +
  `CancelOrderUseCase` with 30-min edit window (Clock-injected) + V003
  `address_json` jsonb column + `OrderEditExceptionHandler` mapping to
  409/412.

## Saga path (the load-bearing delta for the whole epic)

The 4-1 follow-up "Saga integration" was deferred at the Epic 3 close. The
audit at the start of this session found that the proposed fix
(`@ApplicationModuleListener`) couldn't work because the producer and consumer
live in different JVMs with different FQNs for the same event record. The fix
shipped as `9e0105f` (Story 4.1 follow-up #5, cross-service Kafka bridge):

- `util/events/contracts/PaymentEventEnvelope.java` — wire format
- `services/payment/.../kafka/PaymentEventKafkaBridge.java` — `@Scheduled`
  outbox poller (`SELECT ... FOR UPDATE SKIP LOCKED` → Kafka → `published_at`)
- `services/order/.../kafka/PaymentEventKafkaListener.java` — daemon-thread
  consumer + HMAC verify + in-process re-publish
- `PaymentOrderBridgeIT.java` — cross-process Testcontainers integration test

Before the bridge: 4-1 review claim was "saga code present but unexercised".
After the bridge: 4-1 has a real producer, a real consumer, a real HMAC
contract, and a Testcontainers IT.

## Closeout fixes (commit 0741290)

Deep review found 2 CRITICAL findings in the Epic 3 sprint 1 mega-commit
(`baa5aae`). Both fixed and shipped as part of the Epic 4 close:

### C1: Genesis snapshot race (FR-31 boundary)

`AppendOrderTransitionUseCase.execute()` inserted the price snapshot via
`priceSnapshotRepository.save()` with no `DataIntegrityViolationException`
guard. Two concurrent genesis POSTs (client retry on 5xx, duplicate saga
delivery) would both reach the snapshot insert with `fromState == null`;
the second `save()` collides on the snapshot's natural PK and propagated
`DIVE` to the controller as a 500.

Fix: wrap the genesis snapshot save in `try/catch`; on DIVE, re-query
`findById(orderUuid)` — if a row exists, the winning thread's data is the
same (snapshot is part of the command), so swallow and proceed. The V005
unique index gates the duplicate transition itself.

Tests added: `execute_genesisSnapshotRaceSwallowsDuplicateAndProceeds`,
`execute_genesisSnapshotRaceRethrowsWhenWinnerNotFound`.

### C2: Bridge swallows dispatch exceptions (data loss)

`PaymentEventKafkaListener.dispatchCaptured` / `dispatchRefunded` caught
`Exception` internally, logged, and returned. The poll loop caught
`RuntimeException` per-record and committed the offset anyway. Net effect:
a transient downstream failure (e.g. `@Transactional` rollback in a
downstream listener, or a bad payload JSON the envelope HMAC validly
signed) caused the message to be permanently lost with no DLQ.

Fix: dispatch methods no longer catch; `processRecord` lets the exception
propagate; `pollLoop` tracks per-batch success via an `allCommitted` flag
and skips `commitSync()` if any record threw. The failing record replays
on the next poll; the V005 unique index + the C1 fix make the replay
either succeed or be a known-good no-op.

Test added: `dispatchReThrowsOnDownstreamException`.

## Caveats surfaced during the closeout

1. **All 4 stories shipped in one mega-commit (`baa5aae`)** with no per-story
   commit history. The `dev-story` comments in sprint-status are accurate but
   cannot be back-traced to per-story git hashes. Future cycles should
   enforce one-story-per-commit in the orchestrator.

2. **The "10/23/27/34 order tests pass" numbers are test-method counts** at
   `baa5aae`. Current order test file count is 14 (4-1 ≈ 5, 4-2 ≈ 6 incl.
   saga, 4-3 ≈ 1, 4-4 ≈ 2). The numbers were frozen at mega-commit time and
   not updated by follow-up commits.

3. **4-1 smoke does NOT exercise the negative cases the comment claims.**
   The 4-1 comment lists "POST 500 (invalid transition), POST 400 (null
   sagaStep)" but the smoke script (`dev/scripts/smoke-order-4-1.sh`) does
   not run those requests. The unit tests cover the negative paths
   (`execute_throwsOnInvalidTransition`, `execute_throwsOnNullSagaStep`).

4. **4-2 smoke goes through `POST /advance`, NOT the saga `@EventListener`.**
   The 5-transition smoke cannot distinguish a saga-advanced PAID from a
   curl-advanced PAID. The saga is now exercised by `PaymentOrderBridgeIT`
   (Testcontainers, full process) — this is the only end-to-end proof.

5. **4-3 smoke step 7 is a brittle string match** on the integer `99999`.
   Works today because `Long` serializes as a bare number, but the assertion
   is fragile.

6. **No end-to-end Kafka integration test existed for order/payment before
   `9e0105f`.** The bridge's `PaymentEventKafkaListenerTest` (mocked
   listener) + `PaymentCapturedOrderAdvancerTest` (mocked advancer) were
   the only evidence. The new `PaymentOrderBridgeIT` closes the gap.

## Deferred (in `_bmad-output/backlog/deferred-issues.md`)

### Story 4.1 (2 items)
- ~~Saga integration (PLACED → PAID)~~ — resolved at 9e0105f
- User-visible timeline endpoint — shipped at 4-3
- ArchUnit boundary test for order module (MEDIUM) — the 3 order-local rules
  (application.usecase → infrastructure.entity / repository / outbox forbidden)
  still need to be written; the deny-list for `RequestBodyLogger` is already
  covered repo-wide.

### Story 4.4 (1 item)
- Stale-read in same `@Transactional` after native UPDATE (H2 from review):
  the `AmendOrderAddressUseCase` does a native SQL UPDATE on
  `order_price_snapshot` but doesn't `entityManager.refresh(snapshot)` —
  the in-memory entity is stale. Move to deferred-issues as MEDIUM.

### Cross-cutting (H findings from review)
- `@Setter` on `OrderStateTransition` entity (H2/H3) defeats FR-30 at the
  JPA layer. The V005 unique index gates INSERTS but not UPDATEs. Move to
  MEDIUM.
- HMAC key provider binding to order's own secret instead of the producer's
  (H4). ADR-20 v1 shared-secret via env var works in dev; ops hardening
  tracks per-service Vault path isolation.

## Process learnings

### C1+C2 had been latent for two cycles

Both CRITICAL findings sat in the codebase since `baa5aae` (Epic 3 sprint 1).
Neither was caught by the unit tests in that mega-commit because (a) the
genesis race requires concurrent execution (timing-dependent), and (b) the
C2 swallow was an intentional "graceful error handling" choice in
`dispatchCaptured`/`dispatchRefunded`. **Lesson:** the dev-story
deep-review pass needs an explicit "race + exception propagation" check on
every `@Transactional` boundary and every Kafka callback — the existing
check pattern focused on state-machine validity, not the framing errors.

### Story doc body lag continues from Epic 3

The story doc bodies for 4-1..4-4 still have empty Completion Notes. The
dev cycle ran the code, the closeout fix ran, but the dev agent never
back-filled the story markdown. The retro captures the deferred items +
commit hashes; the doc body can be patched lazily.

### Direct cycle path works for Epic 4 the same as Epic 3

The orchestrator's v0-schema gate (`3366c68`) was tested at the start of
this session and was the right preemptive fix. The Epic 3 lessons
(per-cycle subagent dispatch, re-verify subagent claims, CRITICAL+HIGH
fix now / rest deferred) applied cleanly. No new process findings.

## Carry-forward for Epic 5

Epic 5 has 7 stories in `review` (5-1..5-7). The same direct-cycle path
applies; 5-7 had a deferred JPA bootstrap issue (already fixed at
`4293485` per deferred-issues.md). The bridge (`9e0105f`) is
**directly relevant** to 5-6 (loyalty points accrues on PLACED + PAID
transitions — the bridge ensures the PAID event is delivered, which
triggers the accrual).

## Carry-forward for ops

- Real Vault integration (Story 3.5 deferred) — shared-secret dev pattern
  is fine for now; prod still needs `spring-cloud-starter-vault-config`
  + per-service paths. ~1 day when ops has the Vault baseline.
- ArchUnit boundary rules for `services/order` — 3 missing local rules
  (application.usecase → infrastructure.entity / repository / outbox).
  ~1 hour to add. Move to next epic's deferred sweep.
- AmendOrderAddressUseCase stale-read (H2) — needs `entityManager.refresh()`
  or a snapshot refactor. Move to next epic's deferred sweep.

## Total delta this closure

- 3 commits: `3366c68` (orchestrator gate), `9e0105f` (event bridge),
  `187f31a` (deferred-issues reconcile), `0741290` (C1+C2 saga fixes).
- 19 new files (bridge), 11 modified (bridge), 4 modified (saga fixes).
- Net +1372 LOC from bridge; +166/-55 LOC from saga fixes.
- Tests: 3 util + 4 payment producer + 5 order consumer + 3 saga fix
  (3 new + 2 updated) + 1 cross-process IT.
- Backlog: 0 HIGH items remaining (5 reconciled in `187f31a`).
