---
baseline_commit: 6b6d952
---

# Story 5.7: PricingService stub (FR-65, FR-67) — placeholder

Status: review

## Story

As a system,
I want a minimal `services/pricing/` module stub with the FR-65 + FR-67 contract,
So that other services can depend on the interface without coupling to Stripe Coupons or future multi-currency logic.

## Acceptance Criteria

1. **Given** FR-65 mandates "Single PricingService owns list price, sale price, promotion discount" (`prd.md:136`), **When** Story 5.7 lands, **Then** a new `services/pricing/` Maven module is bootstrapped with: `GET /api/pricing/{variantId}` returning `{variantId, listPriceCents, salePriceCents?, currency, effectiveAt}` for a variant. v1 ships a static in-memory catalog (no DB) — the variant IDs + prices are seeded at startup from a `pricebook.json` resource. The endpoint returns HTTP 200 for known variants, HTTP 404 for unknown. The static catalog is the v1 contract; a real pricebook table lands with a future story.
2. **Given** FR-67 mandates "Multi-currency display via static FX rate; settlement in base currency (VND)" (`prd.md:138`), **When** Story 5.7 lands, **Then** the response always carries `currency: "VND"` (v1 is VND-only — multi-currency is an explicit non-goal per the epic's P2 marker). The FX rate config is in `application.yml` (a `pricing.fx-rate: 1.0` default); the `pricing.events` and `pricing.promotion_applied` events land with Epic 3 (Stripe Coupons) + Epic 4 (Order aggregate). v1 ships no events.
3. **Given** the FR-79 PCI-scope constraint (Stripe Elements iframe-only, OTel log redaction for any `\d{13,19}` field, deny-list default request-body logger) is enforced at the payment module, **When** Story 5.7 lands, **Then** the pricing service inherits the `PanRedactingAppender` from util + the R-15 deny-list (no request-body loggers) — same as every other service. The ArchUnit deny-list from Story 3.3 covers `vn.vpt..`; pricing is no exception.
4. **Given** the runtime smoke rule (project memory `runtime-smoke-rule.md`), **When** Story 5.7 completes, **Then** the dev agent runs `bash dev/scripts/smoke-pricing-5-7.sh` which: (a) starts `services/pricing`; (b) `GET /api/pricing/variant-1` — assert HTTP 200 + the JSON has `listPriceCents`, `currency:"VND"`; (c) `GET /api/pricing/variant-unknown` — assert HTTP 404; (d) kill the process, exit 0.

### Out of scope (do NOT build — deferred, stated to prevent scope creep)

- **Real pricebook table** (Postgres) → Future story. v1 ships the static JSON.
- **Promotion discount logic** (FR-66) → Epic 3 (Stripe Coupons). v1 just returns list price.
- **Pricing events** (FR-68) → Epic 4 (Order aggregate). v1 emits no events.
- **Multi-currency** (FR-67 second half) → Out of scope. v1 is VND-only.
- **Catalog integration** (catalog → pricing sync) → Future story. v1 has a static seed.

## Tasks / Subtasks

- [ ] **Task 1 — Bootstrap `services/pricing` Maven module** (AC: #1)
  - [ ] Modify `services/pricing/pom.xml`: change packaging to `jar`; add deps (util, spring-boot-starter-web, spring-boot-starter-actuator, Lombok).
  - [ ] Create `services/pricing/src/main/java/vn/vnpt/pricing/PricingApplication.java` with `@SpringBootApplication(scanBasePackages = "vn.vnpt.pricing") @ApplicationModule(displayName = "pricing")`.
  - [ ] Create `services/pricing/src/main/resources/application.yml` (port 8090, FR-29 deny-list).
  - [ ] Create `services/pricing/src/main/resources/logback-spring.xml` (include util's logback-include.xml).

- [ ] **Task 2 — Static pricebook + domain** (AC: #1)
  - [ ] `services/pricing/src/main/resources/pricebook.json` — sample pricebook (3 variants: `variant-1`, `variant-2`, `variant-3` with list + sale prices).
  - [ ] `services/pricing/.../domain/Pricebook.java` — `@Component` that loads the JSON at startup into a `Map<String, PriceEntry>`. v1 has no DB; the static JSON is the v1 contract.
  - [ ] `services/pricing/.../domain/PriceEntry.java` — record `(long listPriceCents, Long salePriceCents, String currency, Instant effectiveAt)`.

- [ ] **Task 3 — REST endpoint** (AC: #1)
  - [ ] `services/pricing/.../application/web/PricingController.java` — `GET /api/pricing/{variantId}`. Returns 200 with the PriceEntry; 404 if not found.

- [ ] **Task 4 — Tests** (AC: #1, #2)
  - [ ] `services/pricing/src/test/java/vn/vnpt/pricing/domain/PricebookTest.java` — 3 tests: `load_parsesValidJson`, `get_returnsPriceEntryForKnownVariant`, `get_returnsEmptyForUnknownVariant`.

- [ ] **Task 5 — Runtime smoke script** (AC: #4)
  - [ ] `dev/scripts/smoke-pricing-5-7.sh` — bash. Pattern mirrors `smoke-customer-5-3.sh`.

## Dev Notes

### Implementation Notes

- **`services/pricing/` is freshly bootstrapped** (no `services/pricing/` dir yet — needs `mkdir`). The dev agent creates the directory + the pom + sources.
- **No DB** — pricing has no per-service `pricing_db`; the pricebook is a static JSON. v1 is the v1 contract; a real DB lands with a future story.
- **Test counts target** — `≥ 3 new tests`. Pricing service baseline: 0; target: ≥ 3.

### Debug Log References

(none — fresh story; debugs land in Dev Notes / Completion Notes after the dev agent runs)

### Completion Notes List

(empty — populated by dev-story)

### Deviations from literal story text (flagged for review)

(none — fresh story; deviations land here after dev-story)

## Dev Notes (post-dev-story section — populated after implementation)

### Project Structure Notes

- **Path placement** (per architecture §6):
  - `services/pricing/pom.xml` ← change to jar (Task 1)
  - `services/pricing/src/main/java/vn/vnpt/pricing/PricingApplication.java` ← new (Task 1)
  - `services/pricing/src/main/resources/application.yml` + `logback-spring.xml` ← new (Task 1)
  - `services/pricing/src/main/resources/pricebook.json` ← new (Task 2)
  - `services/pricing/src/main/java/vn/vnpt/pricing/domain/Pricebook.java` + `PriceEntry.java` ← new (Task 2)
  - `services/pricing/src/main/java/vn/vnpt/pricing/application/web/PricingController.java` ← new (Task 3)
  - `services/pricing/src/test/java/vn/vnpt/pricing/...` ← new tests (Task 4)
  - `dev/scripts/smoke-pricing-5-7.sh` ← new (Task 5)

### References

- [Source: `_bmad-output/planning-artifacts/epics.md:875-888` — Story 5.7 AC]
- [Source: `_bmad-output/planning-artifacts/prd.md:136-138` — FR-65, FR-67]
- [Source: `_bmad-output/planning-artifacts/architecture.md:236` — Sprint 5: "Pricing: FR-65..68"]
- [Source: `services/customer/.../application/web/CustomerController.java` — Story 5.1 controller pattern (mirror for pricing)]
- [Source: project memory `runtime-smoke-rule.md` — every dev-story must runtime-smoke
- [Source: project memory `deep-review-rules.md` — F1: shared code in util/, 1-sentence javadoc, no single-impl abstractions

## Dev Agent Record

### Agent Model Used

{{agent_model_name_version}}

### Debug Log References

### Completion Notes List

### File List