---
baseline_commit: 6b6d952
---

# Story 5.2: PDPD data export (FR-46, FR-49) — solves LC-01

Status: review

## Story

As a data subject,
I want a single endpoint that exports all my data,
And right-to-be-forgotten that hard-deletes PII + anonymizes order history.

## Acceptance Criteria

1. **Given** Story 5.1 ships the Customer aggregate + Address book, **When** Story 5.2 lands, **Then** a new endpoint `GET /api/customers/{id}/export` returns the customer's full record (Customer + addresses) as a JSON object — the response body is the canonical PDPD export shape for the customer service. The export includes the customer displayName / email / phone / addresses (PII fields); the userId field is included for cross-service correlation. The endpoint is unauthenticated in v1 (single-tenant; RBAC lands in Story 5.4). Story 5.2 is the **first** service to ship a PDPD export — other services land their own exports in future stories (the registry table per AC #2 is the seam for cross-service orchestration; v1 ships the customer-side half).
2. **Given** AC #1 mandates a `customer_data_registry` table to track per-service exports (verbatim `epics.md:813`), **When** Story 5.2 lands, **Then** the customer service has a `customer_data_registry` table: `id BIGSERIAL PRIMARY KEY, service_name VARCHAR(64) NOT NULL, table_name VARCHAR(64) NOT NULL, columns JSONB NOT NULL, format VARCHAR(16) NOT NULL DEFAULT 'json', created_at TIMESTAMP NOT NULL DEFAULT now()`. The customer service seeds one row on startup: `(customer_service, customer, ["id","user_id","display_name","email","phone","created_at"], json)` + `(customer_service, address, ["id","customer_id","line1","province_code","district_code","commune_code","is_default","created_at"], json)`. Future services seed their own rows.
3. **Given** the right-to-be-forgotten contract ("PII is hard-deleted; order history rows are anonymized"), **When** Story 5.2 lands, **Then** a new endpoint `POST /api/customers/{id}/forget` performs: (a) hard-delete the customer row (cascades to addresses per the FK ON DELETE CASCADE in V001); (b) insert a `customer_data_registry` row tagged `service=customer_service, table=forget_audit, columns=["customer_id","forgotten_at"], format=json` recording the deletion; (c) the response is HTTP 200 with `{"customerId":N,"forgottenAt":"<ISO-8601>"}`. **Order history anonymization is OUT OF SCOPE for this story** — the customer service doesn't own order data (that's `services/order` from Epic 4). Story 5.2 ships the customer-side half; a future story wires the order-side anonymization.
4. **Given** the runtime smoke rule (project memory `runtime-smoke-rule.md`), **When** Story 5.2 completes, **Then** the dev agent runs `bash dev/scripts/smoke-customer-5-2.sh` which: (a) starts `services/customer`; (b) waits for `/actuator/health` UP; (c) `POST /api/customers` + `POST /api/customers/{id}/addresses` (the Story 5.1 contract); (d) `GET /api/customers/{id}/export` — assert HTTP 200 with the customer + addresses shape; (e) `GET /api/customers/{id}/export` again after `POST /api/customers/{id}/forget` — assert HTTP 404 (the customer is gone); (f) `psql -c "SELECT count(*) FROM customer WHERE id = ..."` — assert 0 rows; (g) `psql -c "SELECT count(*) FROM customer_data_registry WHERE table_name = 'forget_audit'"` — assert 1 row; (h) kill the process, exit 0.
5. **Given** the ArchUnit deny-list from Story 3.3 covers `vn.vnpt..`, **When** Story 5.2 lands, **Then** the new endpoints inherit the R-15 deny-list. The export endpoint may carry the customer's email / phone (PII); the `PanRedactingAppender` redacts `\d{13,19}` (PAN-shaped fields) — email/phone are not in the redactor's pattern, so they pass through. The export endpoint is a **deliberate** PII exposure (the customer is requesting their own data); the response is logged at INFO level with `customerId` only (not the email/phone content).
6. **Given** the export must be deterministic (the same customer returns the same bytes), **When** Story 5.2 lands, **Then** the response is the deterministic JSON projection of the entity state — no random IDs in field names, no timestamps that differ between calls. The `exportedAt` field is the only non-deterministic value (captures the time of the export; a fixed-clock test can pin it).

### Out of scope (do NOT build — deferred, stated to prevent scope creep)

- **Cross-service export aggregation** (joining customer + order + payment data into one ZIP) → Future story. v1 ships the customer-side export; a BFF-level aggregator joins them when the BFF lands.
- **Order history anonymization** (FR-49 second half) → Future story. Story 5.2 ships the customer-side hard-delete + forget_audit row; the order-side lands when the order service's PDPD story fires.
- **Right-to-be-forgotten with proof-of-deletion** (cryptographic receipts) → Story 10.x (observability). v1 records the deletion in `customer_data_registry`; cryptographic proof lands when the audit pipeline does.
- **Auth / RBAC** → Story 5.4. v1 endpoints are unauthenticated (single-tenant).
- **Email / SMS notification of the export** → Story 9.x (notifications). v1 just returns the data; future story notifies "your data was exported on YYYY-MM-DD".
- **Export format variants (CSV, XML, PDF)** → Future story. v1 ships JSON only (the registry's `format` column tracks which format each row supports; the customer_service rows default to `json`).
- **Cross-tenant export isolation** → Story 5.x (multi-tenant). v1 uses a global customer table.

## Tasks / Subtasks

- [ ] **Task 1 — V002 Flyway migration: `customer_data_registry`** (AC: #2)
  - [ ] `services/customer/src/main/resources/db/migration/customer/V002__create_customer_data_registry.sql` — `customer_data_registry` table per AC #2 + the customer-side seed rows.

- [ ] **Task 2 — JPA entity + repository for `customer_data_registry`** (AC: #2)
  - [ ] `services/customer/.../infrastructure/entity/CustomerDataRegistryEntity.java` — `@Entity @Table(name = "customer_data_registry")` with `@Id Long id`, `String serviceName`, `String tableName`, `@JdbcTypeCode(SqlTypes.JSON) String columns` (JSONB), `String format`, `LocalDateTime createdAt`. Lombok.
  - [ ] `services/customer/.../infrastructure/repository/CustomerDataRegistryRepository.java` extends `JpaRepository<CustomerDataRegistryEntity, Long>`.

- [ ] **Task 3 — `ExportCustomerDataUseCase` + `ForgetCustomerUseCase`** (AC: #1, #3, #6)
  - [ ] `services/customer/.../application/port/ExportResponse.java` — record `(long customerId, long userId, String displayName, String email, String phone, List<AddressEntity> addresses, Instant exportedAt)`.
  - [ ] `services/customer/.../application/usecase/ExportCustomerDataUseCase.java` — `@Service @Transactional(readOnly = true)`; loads the Customer + addresses; returns the deterministic `ExportResponse`.
  - [ ] `services/customer/.../application/usecase/ForgetCustomerUseCase.java` — `@Service @Transactional`; loads the Customer (throws 404 if not found); deletes the customer (cascade-deletes addresses); inserts a `forget_audit` row; returns the deletion timestamp.
  - [ ] `services/customer/.../application/port/ForgetResponse.java` — record `(long customerId, Instant forgottenAt)`.

- [ ] **Task 4 — REST endpoints** (AC: #1, #3)
  - [ ] `services/customer/.../application/web/CustomerController.java` — add `@GetMapping("/{id}/export")` and `@PostMapping("/{id}/forget")`. The `forget` endpoint returns HTTP 200 with the `ForgetResponse`; 404 if the customer doesn't exist.

- [ ] **Task 5 — Tests** (AC: #1, #3, #4, #5)
  - [ ] `ExportCustomerDataUseCaseTest` — 3 tests: `execute_returnsCustomerWithAddresses`, `execute_throwsOnUnknownCustomer`, `exportedAt_isCurrentInstant`.
  - [ ] `ForgetCustomerUseCaseTest` — 3 tests: `execute_deletesCustomerAndInsertsAudit`, `execute_throwsOnUnknownCustomer`, `execute_cascadeDeletesAddresses`.

- [ ] **Task 6 — Runtime smoke script** (AC: #4)
  - [ ] `dev/scripts/smoke-customer-5-2.sh` — bash. Pattern mirrors `smoke-customer-5-1.sh`.

## Dev Notes

### Implementation Notes

- **The export endpoint is a pure projection** of the existing Customer + Address entities; no new domain logic. The response is deterministic except for `exportedAt` (which the test pins via `Clock` injection).
- **The forget endpoint is a cascade delete + audit insert in the same transaction**. If the audit insert fails, the customer is not deleted (atomic). The response carries the `forgottenAt` timestamp from the audit row.
- **The `customer_data_registry` table is the cross-service seam**. v1 ships 2 seed rows for the customer service; future services seed their own rows on startup (the `forget_audit` row is appended at delete time).
- **PII handling:** the export endpoint deliberately returns email + phone (the customer is requesting their own data). The `PanRedactingAppender` (Story 3.3) redacts PAN-shaped fields (`\d{13,19}`); email + phone are not in the redactor's pattern, so they pass through. The R-15 deny-list is a separate concern (no request-body loggers); the export is a read-only operation, so the deny-list doesn't apply.
- **Test counts target** — `≥ 6 new tests` (ExportCustomerDataUseCase 3 + ForgetCustomerUseCase 3). Customer service baseline after Story 5.1: 7 tests; target after Story 5.2: ≥ 13.

### Debug Log References

(none — fresh story; debugs land in Dev Notes / Completion Notes after the dev agent runs)

### Completion Notes List

(empty — populated by dev-story)

### Deviations from literal story text (flagged for review)

(none — fresh story; deviations land here after dev-story)

## Dev Notes (post-dev-story section — populated after implementation)

### Project Structure Notes

- **Path placement** (per architecture §6):
  - `services/customer/src/main/resources/db/migration/customer/V002__create_customer_data_registry.sql` ← new (Task 1)
  - `services/customer/src/main/java/vn/vnpt/customer/infrastructure/entity/CustomerDataRegistryEntity.java` ← new (Task 2)
  - `services/customer/src/main/java/vn/vnpt/customer/infrastructure/repository/CustomerDataRegistryRepository.java` ← new (Task 2)
  - `services/customer/src/main/java/vn/vnpt/customer/application/port/ExportResponse.java` + `ForgetResponse.java` ← new (Task 3)
  - `services/customer/src/main/java/vn/vnpt/customer/application/usecase/ExportCustomerDataUseCase.java` + `ForgetCustomerUseCase.java` ← new (Task 3)
  - `services/customer/src/main/java/vn/vnpt/customer/application/web/CustomerController.java` ← extend (Task 4)
  - `services/customer/src/test/java/vn/vnpt/customer/...` ← new tests (Task 5)
  - `dev/scripts/smoke-customer-5-2.sh` ← new (Task 6)

- **Detected conflicts / variances (with rationale):**
  - **Order history anonymization is out of scope** for this story; lands with a future order-side PDPD story.
  - **Audit row is `forget_audit` not `customer` deletion** — the `customer_data_registry` table tracks the deletion for compliance, not the data itself.

### References

- [Source: `_bmad-output/planning-artifacts/epics.md:803-816` — Story 5.2 AC]
- [Source: `_bmad-output/planning-artifacts/prd.md:108` — FR-46: "PDPD data export"]
- [Source: `_bmad-output/planning-artifacts/prd.md:109` — FR-49: "right-to-be-forgotten"]
- [Source: `_bmad-output/planning-artifacts/addendum.md` A1 row R-06 (LC-01 root cause)]
- [Source: `services/customer/.../infrastructure/entity/CustomerEntity.java` — Story 5.1 entity (reused here)]
- [Source: `services/customer/.../application/web/CustomerController.java` — Story 5.1 controller (extend here)]
- [Source: project memory `runtime-smoke-rule.md` — every dev-story must runtime-smoke
- [Source: project memory `deep-review-rules.md` — F1: shared code in util/, 1-sentence javadoc, no single-impl abstractions

## Dev Agent Record

### Agent Model Used

{{agent_model_name_version}}

### Debug Log References

### Completion Notes List

### File List