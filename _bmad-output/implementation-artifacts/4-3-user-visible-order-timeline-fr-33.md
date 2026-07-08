---
baseline_commit: 6b6d952
---

# Story 4.3: User-visible order timeline (FR-33)

Status: review

## Story

As a shopper,
I want a single timeline endpoint showing placed → paid → packed → shipped → delivered,
so that I don't have to call customer support for "where is my order".

## Acceptance Criteria

1. **Given** Story 4.1 ships `services/order/.../infrastructure/repository/OrderStateTransitionRepository.findByOrderUuidOrderByIdAsc(...)` and Story 4.2 ships the saga listener that appends `PLACED → PAID → ALLOCATED → PACKING → PACKED` transitions, **When** Story 4.3 lands, **Then** a new endpoint `GET /api/orders/{orderUuid}/timeline` returns a `timeline` array of `[{ state, timestamp }]` objects — one entry per transition, in chronological order. The shape is the user-facing representation of the append-only log. The endpoint reuses the existing repository method; no new DB write path.

2. **Given** AC #1 mandates the timeline is "cached for 30 seconds (CDN-friendly)" (`epics.md:743` verbatim), **When** Story 4.3 lands, **Then** the response sets `Cache-Control: public, max-age=30` (the standard 30s CDN cache directive). The BFF layer (out of scope for this story) can layer additional cache headers; this story's surface is the `services/order` service. The `Vary: Accept-Encoding` header is also set for CDN correctness. The endpoint also sets `Content-Type: application/json; charset=utf-8`.

3. **Given** the order may have 0 transitions (a fresh `orderUuid` that was never created), **When** the endpoint receives a request for an unknown orderUuid, **Then** the response is HTTP 200 with `{"timeline": []}` (NOT 404 — the per-epic BFF contract returns 200 with an empty array; Story 4.4's edit-after-pay can also probe for the order's existence via the same endpoint). The decision is documented as a one-line javadoc citing the BFF contract.

4. **Given** the timeline is the canonical user-facing view of the order state, **When** Story 4.3 lands, **Then** the response shape is:

   ```json
   {
     "orderUuid": 12345,
     "timeline": [
       { "state": "PLACED",  "timestamp": "2026-07-08T01:00:00Z" },
       { "state": "PAID",    "timestamp": "2026-07-08T01:05:00Z" },
       { "state": "ALLOCATED", "timestamp": "2026-07-08T01:10:00Z" },
       { "state": "PACKING", "timestamp": "2026-07-08T01:15:00Z" },
       { "state": "PACKED",  "timestamp": "2026-07-08T01:20:00Z" }
     ]
   }
   ```

   The `state` field carries the `toState` of the transition (the state the order entered); the `timestamp` carries the `createdAt` of the transition (chronological). For the genesis transition (`fromState` is null), the `state` is `PLACED` (the genesis state, as set by `OrderTransitionValidator.isAllowed(null, PLACED) = true`).

5. **Given** the order service runs on port 8087 (per Story 4.1), **When** Story 4.3 lands, **Then** the endpoint is accessible at `GET http://localhost:8087/api/orders/{orderUuid}/timeline` returning the response shape above. The endpoint is unauthenticated in v1 (single-tenant; RBAC lands in Story 5.x). The CORS policy is permissive in dev (the BFF forwards from a different origin in production; in dev, the smoke calls the endpoint directly).

6. **Given** the runtime smoke rule (project memory `runtime-smoke-rule.md` — every dev-story must runtime-smoke), **When** Story 4.3 completes, **Then** the dev agent runs `bash dev/scripts/smoke-order-4-3.sh` which: (a) starts `services/order` with `SPRING_PROFILES_ACTIVE=dev` + `HMAC_SERVICE_SECRET_ORDER=<32-byte-hex>`; (b) waits for `/actuator/health` UP; (c) POSTs the genesis `PLACED` transition + 4 subsequent transitions (Story 4.2 contract); (d) `curl -i http://localhost:8087/api/orders/{orderUuid}/timeline` — asserts HTTP 200 + `Cache-Control: public, max-age=30` + the response body has 5 entries with the right `state` values; (e) `curl http://localhost:8087/api/orders/99999/timeline` — asserts HTTP 200 with `{"timeline": []}`; (f) kills the process, exits 0.

7. **Given** the timeline is a pure projection of the existing transition log, **When** Story 4.3 lands, **Then** no new DB migration is needed (Story 4.1's `order_state_transition` table already carries the data). The endpoint is read-only.

8. **Given** the existing ArchUnit deny-list from Story 3.3 covers `vn.vnpt..`, **When** Story 4.3 lands, **Then** the timeline endpoint inherits the R-15 deny-list (request-body loggers forbidden). The endpoint doesn't log the response body (per AC #2 — the response carries the timeline, which is non-sensitive).

### Out of scope (do NOT build — deferred, stated to prevent scope creep)

- **BFF layer (`/bff/storefront/order/{id}`)** → the actual `/bff/*` endpoint lives in `bff/storefront-bff/`. The order service endpoint is `/api/orders/{id}/timeline`; the BFF proxies it. Story 4.3 ships the backend surface only.
- **CDN configuration** (CloudFront, Fastly, etc.) → ops concern. The `Cache-Control` header is set; ops configures the CDN to honor it.
- **Real-time updates (SSE, WebSocket)** → Future story. The 30s cache is the v1 contract; live updates land in Story 8.x.
- **Filtering by state** (e.g. `?since=PAID`) → Future story. v1 returns the full timeline.
- **Localized timestamps** → Future story. v1 returns ISO-8601 UTC timestamps; the BFF layer formats per locale.
- **Pagination** → The timeline is bounded by the state machine (max 7 transitions); no pagination needed.
- **ArchUnit boundary test for the order module** → deferred per `_bmad-output/backlog/deferred-issues.md` (Story 4.1's "ArchUnit boundary test for order module" item).

## Tasks / Subtasks

- [ ] **Task 1 — `OrderTimelineEntry` DTO** (AC: #4)
  - [ ] `services/order/.../application/web/OrderTimelineEntry.java` — `record OrderTimelineEntry(String state, Instant timestamp)`. Mirrors the canonical AC #4 shape.

- [ ] **Task 2 — `OrderTimelineResponse` DTO** (AC: #4)
  - [ ] `services/order/.../application/web/OrderTimelineResponse.java` — `record OrderTimelineResponse(long orderUuid, List<OrderTimelineEntry> timeline)`.

- [ ] **Task 3 — `GetOrderTimelineUseCase`** (AC: #1, #3, #4)
  - [ ] `services/order/.../application/usecase/GetOrderTimelineUseCase.java` — `@Service @Transactional(readOnly = true)`; loads the transitions via `OrderStateTransitionRepository.findByOrderUuidOrderByIdAsc(orderUuid)`; maps each `OrderStateTransition` to an `OrderTimelineEntry(state = transition.toState, timestamp = transition.createdAt.toInstant(ZoneOffset.UTC))`. Returns `OrderTimelineResponse(orderUuid, entries)`. For empty results, returns `OrderTimelineResponse(orderUuid, List.of())` (per AC #3).

- [ ] **Task 4 — `OrderController` timeline endpoint** (AC: #1, #2, #3, #5)
  - [ ] `services/order/.../application/web/OrderController.java` — extend with `@GetMapping("/{orderUuid}/timeline") public ResponseEntity<OrderTimelineResponse> timeline(@PathVariable long orderUuid)`. The method calls the use case, wraps the response with `Cache-Control: public, max-age=30` + `Vary: Accept-Encoding` + `Content-Type: application/json; charset=utf-8`.

- [ ] **Task 5 — Tests** (AC: #1, #3, #4)
  - [ ] `services/order/src/test/java/vn/vnpt/order/application/usecase/GetOrderTimelineUseCaseTest.java` — 4 tests: `execute_returnsEmptyTimelineForUnknownOrder` (AC #3), `execute_returnsSingleEntryForGenesisTransition`, `execute_returnsEntriesInChronologicalOrder` (AC #4), `execute_mapsTimestampsToInstantUTC`.
  - [ ] `services/order/src/test/java/vn/vnpt/order/application/web/OrderControllerTimelineTest.java` — `@WebMvcTest` (or `@SpringBootTest(MOCK) + @AutoConfigureMockMvc` per the codebase's Spring Boot 4 convention) — 3 tests: `timeline_returns200WithCacheControl30s` (AC #2), `timeline_returnsEmptyArrayForUnknownOrder` (AC #3), `timeline_returnsEntriesInChronologicalOrder` (AC #4).

- [ ] **Task 6 — Runtime smoke script** (AC: #6)
  - [ ] `dev/scripts/smoke-order-4-3.sh` — bash. Pattern mirrors `smoke-order-4-2.sh`:
        - (a) Free port 8087.
        - (b) Start `services/order` with `SPRING_PROFILES_ACTIVE=dev` + `HMAC_SERVICE_SECRET_ORDER=<32-byte-hex>`.
        - (c) Wait for `/actuator/health` UP (up to 90s).
        - (d) POST genesis `PLACED` + 4 transitions (Story 4.2 contract).
        - (e) `curl -i http://localhost:8087/api/orders/{orderUuid}/timeline` — assert HTTP 200 + `Cache-Control: public, max-age=30` + 5 entries.
        - (f) `curl http://localhost:8087/api/orders/99999/timeline` — assert HTTP 200 with `{"timeline":[]}`.
        - (g) Kill the process, exit 0.

## Dev Notes

### Implementation Notes

- **The endpoint is a pure projection** of the existing `order_state_transition` log. No new DB write path; no migration needed. The repository's `findByOrderUuidOrderByIdAsc(orderUuid)` is reused from Story 4.1.
- **The `Cache-Control: public, max-age=30` header is set via `ResponseEntity.ok().cacheControl(CacheControl.maxAge(30, TimeUnit.SECONDS).cachePublic()).body(...)`** — Spring's `CacheControl` builder. `Vary: Accept-Encoding` is also set; `Content-Type` is set by Spring's content negotiation.
- **The endpoint returns 200 with an empty array for unknown orders** (NOT 404) per the BFF contract. The decision is documented in the controller's javadoc.
- **Timestamps are serialized as ISO-8601 UTC** via Jackson's default `Instant` serialization. The BFF layer formats per locale in a future story.
- **Test counts target** — `≥ 7 new tests` (GetOrderTimelineUseCase 4 + OrderControllerTimeline 3). Order service baseline after Story 4.2: 23 tests; target after Story 4.3: ≥ 30.
- **Module count** — `mvn validate` still reports 19 modules (no new modules; this story adds to `services/order` only).

### Debug Log References

(none — fresh story; debugs land in Dev Notes / Completion Notes after the dev agent runs)

### Completion Notes List

(empty — populated by dev-story)

### Deviations from literal story text (flagged for review)

(none — fresh story; deviations land here after dev-story)

## Dev Notes (post-dev-story section — populated after implementation)

### Project Structure Notes

- **Path placement** (per architecture §6):
  - `services/order/src/main/java/vn/vnpt/order/application/web/OrderTimelineEntry.java` ← new (Task 1)
  - `services/order/src/main/java/vn/vnpt/order/application/web/OrderTimelineResponse.java` ← new (Task 2)
  - `services/order/src/main/java/vn/vnpt/order/application/usecase/GetOrderTimelineUseCase.java` ← new (Task 3)
  - `services/order/src/main/java/vn/vnpt/order/application/web/OrderController.java` ← extend with `/timeline` (Task 4)
  - `services/order/src/test/java/vn/vnpt/order/application/usecase/GetOrderTimelineUseCaseTest.java` ← new (Task 5)
  - `services/order/src/test/java/vn/vnpt/order/application/web/OrderControllerTimelineTest.java` ← new (Task 5)
  - `dev/scripts/smoke-order-4-3.sh` ← new (Task 6)

- **Detected conflicts / variances (with rationale):**
  - **Endpoint lives on port 8087 (`/api/orders/...`)** — the BFF in `bff/storefront-bff/` will proxy to this; the actual `/bff/storefront/order/{id}` shape lands in a future story.
  - **Empty timeline returns 200 with `{"timeline":[]}`** (NOT 404) per the BFF contract; documented in the controller's javadoc.
  - **Timestamps are serialized as ISO-8601 UTC** — no locale formatting at the order service; BFF layer formats per locale.

### References

- [Source: `_bmad-output/planning-artifacts/epics.md:733-744` — Story 4.3 AC]
- [Source: `_bmad-output/planning-artifacts/architecture.md:299` — "Order state transition log: id, order_uuid, from_state, to_state, saga_step, event_id, created_at" (source of truth for the timeline shape)]
- [Source: `services/order/.../infrastructure/repository/OrderStateTransitionRepository.java` — Story 4.1 `findByOrderUuidOrderByIdAsc(...)` (reused here)]
- [Source: `services/order/.../application/usecase/AppendOrderTransitionUseCase.java` — Story 4.1 use case (sister use case)]
- [Source: `services/order/.../application/web/OrderController.java` — Story 4.1 + 4.2 controller (extend here)]
- [Source: `services/order/.../infrastructure/entity/OrderStateTransition.java` — Story 4.1 entity (`toState` + `createdAt` are the timeline inputs)]
- [Source: `services/payment/.../test/.../StripeWebhookControllerTest.java` — Spring Boot 4 `@SpringBootTest(MOCK) + @AutoConfigureMockMvc` pattern (mirror for `OrderControllerTimelineTest`)]
- [Source: project memory `runtime-smoke-rule.md` — every dev-story must runtime-smoke
- [Source: project memory `deep-review-rules.md` — F1: shared code in util/, 1-sentence javadoc, no single-impl abstractions

## Dev Agent Record

### Agent Model Used

{{agent_model_name_version}}

### Debug Log References

### Completion Notes List

### File List