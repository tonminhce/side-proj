---
baseline_commit: 6b6d952
---

# Story 5.3: Vietnamese address autocomplete (FR-48)

Status: review

## Story

As a shopper in Vietnam,
I want address autocomplete to suggest Province → District → Commune as I type,
so that I don't have to remember administrative divisions.

## Acceptance Criteria

1. **Given** the address form, **When** the shopper types "Tân B", **Then** the autocomplete API returns suggestions matching the prefix (case-insensitive, diacritic-aware). v1 ships an in-memory + static-seed implementation (Elasticsearch is a Story 6.x follow-up per the search epic). The endpoint is `GET /api/customers/addresses/autocomplete?type=province|district|commune&q=<query>&parent=<parentCode>` returning `List<UtilDto>` — the existing `ProvinceDto` / `DistrictDto` / `CommuneDto` records from util.
2. **Given** AC #1 mandates diacritic-aware matching, **When** the shopper types "Tan B" (no diacritics) or "Tân B" (with diacritics), **Then** the API returns the same suggestions (case-insensitive + diacritic-folding). The matching uses Java's `Normalizer.normalize(input, NFD).replaceAll("\\p{InCombiningDiacriticalMarks}+", "")` (per the codebase's pattern from Story 1.6's reservation logic — verify; if util has a `DiacriticFolding` helper, use it).
3. **Given** the autocomplete is a 3-level hierarchy (province → district → commune), **When** the shopper selects a province, **Then** the district autocomplete filters by the parent province. The `parent` query parameter is required for `district` and `commune` types; the endpoint returns HTTP 400 if `parent` is missing. v1 supports the canonical 63 provinces + ~700 districts + ~11000 communes (a static seed list bundled in `services/customer/src/main/resources/seed/vn-addresses.json` — verified to exist; if not, a stub list with the top 10 provinces is shipped).
4. **Given** the runtime smoke rule (project memory `runtime-smoke-rule.md`), **When** Story 5.3 completes, **Then** the dev agent runs `bash dev/scripts/smoke-customer-5-3.sh` which: (a) starts `services/customer`; (b) `curl http://localhost:8088/api/customers/addresses/autocomplete?type=province&q=Tan` — assert at least 1 suggestion (Tân Bình, Tây Ninh, etc.); (c) `curl http://localhost:8088/api/customers/addresses/autocomplete?type=district&q=Binh&parent=79` (HCMC code 79) — assert at least 1 suggestion (Quận Bình Thạnh, etc.); (d) `curl http://localhost:8088/api/customers/addresses/autocomplete?type=district&q=Binh` without `parent` — assert HTTP 400; (e) kill the process, exit 0.
5. **Given** the autocomplete response is small JSON, **When** Story 5.3 lands, **Then** the endpoint sets `Cache-Control: public, max-age=3600` (1h — the address list changes rarely). The endpoint is unauthenticated in v1 (single-tenant; the data is public).

### Out of scope (do NOT build — deferred, stated to prevent scope creep)

- **Elasticsearch geo-index** → Story 6.x (search epic). v1 ships an in-memory + static-seed implementation; the future ES wiring is a 1-line swap of the data source.
- **Master-data sync from a Vietnamese government data source** (e.g. the General Statistics Office) → Ops concern. v1 ships a static JSON.
- **Diacritic-folding analysis** (handling obscure cases) → Util's `DiacriticFolding` helper (or a future util story) is the right place. v1 uses NFD normalization.
- **Cross-service caching** (CDN-level) → Future ops concern. The `Cache-Control: max-age=3600` is the v1 contract.

## Tasks / Subtasks

- [ ] **Task 1 — `VnAddress` DTO + `VnAddressCatalog` service** (AC: #1, #2, #3)
  - [ ] `services/customer/src/main/resources/seed/vn-addresses.json` — static seed list (provinces + districts + communes). Top 10 provinces (Hà Nội, TP HCM, Hải Phòng, Đà Nẵng, Cần Thơ, An Giang, Bình Dương, Đồng Nai, Tây Ninh, Bà Rịa-Vũng Tàu) + their top districts + a few communes each. The full 63+700+11000 list is too large to bundle in v1; a smaller curated set proves the contract.
  - [ ] `services/customer/.../domain/VnAddressCatalog.java` — `@Component` that loads the JSON at startup into in-memory maps: `Map<String, List<ProvinceDto>>`, `Map<String, List<DistrictDto>>`, `Map<String, List<CommuneDto>>`. The `parent` filter is a simple `get(parent).getOrDefault(emptyList())` lookup.
  - [ ] `VnAddressCatalog.suggest(type, q, parent)` — case-insensitive + diacritic-folding prefix match; returns at most 20 results.
  - [ ] Diacritic-folding: use `java.text.Normalizer.normalize(input, NFD).replaceAll("\\p{InCombiningDiacriticalMarks}+", "")` then lowercase. Match against the same-folded version of the address name.

- [ ] **Task 2 — `AddressAutocompleteUseCase`** (AC: #1, #2, #3)
  - [ ] `services/customer/.../application/usecase/AddressAutocompleteUseCase.java` — `@Service @Transactional(readOnly = true)`; delegates to `VnAddressCatalog.suggest(...)`.

- [ ] **Task 3 — REST endpoint** (AC: #1, #4, #5)
  - [ ] `services/customer/.../application/web/CustomerController.java` — add `@GetMapping("/addresses/autocomplete")` with query params `type` (province|district|commune), `q` (the search string), `parent` (optional, required for district/commune). Returns `List<Object>` (the util DTO list); 400 if `type` is missing or `parent` is missing for district/commune.

- [ ] **Task 4 — Tests** (AC: #1, #2, #3)
  - [ ] `VnAddressCatalogTest` — 5 tests: `suggest_provinceReturnsMatchesCaseInsensitive`, `suggest_provinceDiacriticFolding_tanB_returns_TanBinh`, `suggest_districtWithParent_filtersByParent`, `suggest_districtWithoutParent_returnsEmpty`, `suggest_returnsAtMost20Results`.
  - [ ] `AddressAutocompleteUseCaseTest` — 2 tests: `execute_returnsSuggestionsFromCatalog`, `execute_propagatesExceptionOnUnknownType`.

- [ ] **Task 5 — Runtime smoke script** (AC: #4)
  - [ ] `dev/scripts/smoke-customer-5-3.sh` — bash. Pattern mirrors `smoke-customer-5-2.sh`.

## Dev Notes

### Implementation Notes

- **The static seed JSON is intentionally small** (top 10 provinces). The contract is what matters — the future ES wiring is a swap of the data source. The smoke asserts the contract works, not that all 11000 communes are present.
- **Diacritic folding** uses the standard `Normalizer.NFD` + combining-mark-strip pattern. No third-party library.
- **The endpoint is unauthenticated** (per AC #5) — public data, CDN-cacheable. RBAC lands in Story 5.4.
- **Test counts target** — `≥ 7 new tests` (VnAddressCatalog 5 + AddressAutocompleteUseCase 2). Customer service baseline after Story 5.2: 13 tests; target after Story 5.3: ≥ 20.

### Debug Log References

(none — fresh story; debugs land in Dev Notes / Completion Notes after the dev agent runs)

### Completion Notes List

(empty — populated by dev-story)

### Deviations from literal story text (flagged for review)

(none — fresh story; deviations land here after dev-story)

## Dev Notes (post-dev-story section — populated after implementation)

### Project Structure Notes

- **Path placement** (per architecture §6):
  - `services/customer/src/main/resources/seed/vn-addresses.json` ← new (Task 1)
  - `services/customer/src/main/java/vn/vnpt/customer/domain/VnAddressCatalog.java` ← new (Task 1)
  - `services/customer/src/main/java/vn/vnpt/customer/application/usecase/AddressAutocompleteUseCase.java` ← new (Task 2)
  - `services/customer/src/main/java/vn/vnpt/customer/application/web/CustomerController.java` ← extend (Task 3)
  - `services/customer/src/test/java/vn/vnpt/customer/...` ← new tests (Task 4)
  - `dev/scripts/smoke-customer-5-3.sh` ← new (Task 5)

- **Detected conflicts / variances (with rationale):**
  - **Elasticsearch geo-index deferred** to Story 6.x per the search epic. v1 uses an in-memory static seed.
  - **Static seed is a curated top-10 list** (not all 63 provinces). The contract is what matters; ES wiring in Story 6.x swaps the data source.

### References

- [Source: `_bmad-output/planning-artifacts/epics.md:819-830` — Story 5.3 AC]
- [Source: `util/src/main/java/vn/vnpt/util/common/unit/ProvinceDto.java` — util's Vietnamese address hierarchy DTO]
- [Source: `services/customer/.../application/web/CustomerController.java` — Story 5.1 + 5.2 controller (extend here)]
- [Source: project memory `runtime-smoke-rule.md` — every dev-story must runtime-smoke
- [Source: project memory `deep-review-rules.md` — F1: shared code in util/, 1-sentence javadoc, no single-impl abstractions

## Dev Agent Record

### Agent Model Used

{{agent_model_name_version}}

### Debug Log References

### Completion Notes List

### File List