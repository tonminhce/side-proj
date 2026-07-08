---
baseline_commit: 6b6d952
---

# Story 5.1: CustomerService — Customer aggregate + Address book (FR-45, FR-47)

Status: review

## Story

As a shopper,
I want my Customer profile separated from the auth User, with a Vietnamese address book,
so that I can manage multiple addresses and not lose them across sessions.

## Acceptance Criteria

1. **Given** `architecture.md:235` row "Customer + Auth | customer, auth | FR-45..50, FR-73..77" binds the new `services/customer/` module, **When** Story 5.1 lands, **Then** `services/customer/` is bootstrapped with packaging `jar`, port 8088, dependencies: `util` (for `ProvinceDto` / `DistrictDto` / `SnowflakeIdGenerator`), `spring-boot-starter-web` (REST), `spring-boot-starter-data-jpa` + `spring-boot-flyway` + Postgres driver (per-service `customer_db`, ADR-03), `spring-boot-starter-actuator` (for `/actuator/health`), `spring-boot-starter-test` + Testcontainers (test scope), Lombok. The `mvn validate` module count grows from 19 to **20**. `services/customer/src/main/java/vn/vnpt/customer/CustomerApplication.java` is `@SpringBootApplication @ApplicationModule(displayName = "customer")`.

2. **Given** FR-45 mandates "Customer aggregate is separate from auth User aggregate. Linked by `customer.userId`" (verbatim `prd.md:104` + `addendum.md` A1 row), **When** Story 5.1 lands, **Then** the Customer entity has a `userId` column (FK reference, not enforced at the DB level — the auth service isn't built yet; the column is just `BIGINT NOT NULL` for forward-compat). The Customer does NOT own the auth User's password / email / role — those are auth concerns. The auth → customer link is via `customer.user_id = auth.user.id`.

3. **Given** FR-47 requires the address book to use "util's existing `ProvinceDto` / `DistrictDto` / `CommuneDto` for Vietnamese address hierarchy" (`prd.md:106`), **When** Story 5.1 lands, **Then** the `Address` entity stores the address as 4 fields: `line1` (street address), `province_code` (FK to util's `ProvinceDto` — v1 stores the code as a string; future story wires the actual master-data table), `district_code`, `commune_code`. The 3 `*Dto` records are imported from util and used as the DTO surface (the request body uses the util records' structure).

4. **Given** the AddressBook is part of the Customer aggregate, **When** Story 5.1 lands, **Then** a Customer has a 1-to-many relationship with `Address` (one customer, many addresses). The Customer is the aggregate root; Address is an owned entity (FK to `customer`). The Customer is loaded + saved as a single unit (no DDD event sourcing — this is a simpler read/write aggregate, not append-only like Order).

5. **Given** the runtime smoke rule (project memory `runtime-smoke-rule.md`), **When** Story 5.1 completes, **Then** the dev agent runs `bash dev/scripts/smoke-customer-5-1.sh` which: (a) starts `services/customer`; (b) waits for `/actuator/health` UP; (c) `POST /api/customers` with a JSON body `{userId, displayName, email, phone}` — assert HTTP 201 + the new customer's `id`; (d) `POST /api/customers/{id}/addresses` with `{line1, provinceCode, districtCode, communeCode}` — assert HTTP 201; (e) `GET /api/customers/{id}/addresses` — assert the address is in the list; (f) `GET /api/customers/{id}` — assert the customer shape; (g) `docker exec postgres psql -d customer_db -c "SELECT count(*) FROM customer WHERE user_id = 99"` — assert 1 row; (h) `psql -c "SELECT count(*) FROM address WHERE customer_id = ..."` — assert 1 row; (i) kill the process, exit 0.

6. **Given** the per-service log redaction from Story 3.3 is wired via `util/.../logging/PanRedactingAppender`, **When** Story 5.1 lands, **Then** `services/customer/src/main/resources/logback-spring.xml` `<include>`s the shared `util/.../logback-include.xml` (same pattern as `services/payment/.../logback-spring.xml`). The `application.yml` carries `management.endpoints.web.exposure.include: health,info` (FR-29 deny-list baseline).

7. **Given** util's `ProvinceDto` / `DistrictDto` / `CommuneDto` are records (per `util/.../common/unit/ProvinceDto.java`), **When** Story 5.1 lands, **Then** the Customer's address endpoints use these records directly as the JSON DTO surface. The controller binds the request body to `AddAddressRequest(ProvinceDto province, DistrictDto district, CommuneDto commune, String line1)` — Jackson handles the JSON ↔ record deserialization.

### Out of scope (do NOT build — deferred, stated to prevent scope creep)

- **Auth service** (User aggregate, password, JWT) → Story 5.4. Story 5.1 ships the Customer side; the auth side lands with its own module.
- **Vietnamese address master-data (province / district / commune tables)** → Future ops story. v1 stores the code strings; the master-data table is a separate concern.
- **PDPD data export (FR-46, FR-49)** → Story 5.2.
- **Loyalty points (FR-50)** → Future story.
- **`@Version` JPA annotation on Customer** → Forward-compat; v1 doesn't have multi-tenant concurrency.
- **Multi-tenant isolation** (per-tenant Customer rows) → Story 5.x. v1 uses a global table.
- **Saga integration with order/checkout** (Customer ↔ Order) → Future story; the customer's `userId` is the seam.
- **`application-test.yml` Stripe config verification** → deferred per `_bmad-output/backlog/deferred-issues.md`.

## Tasks / Subtasks

- [ ] **Task 1 — Bootstrap `services/customer` Maven module** (AC: #1)
  - [ ] Modify `services/customer/pom.xml`: change packaging to `jar`; add deps per AC #1.
  - [ ] Create `services/customer/src/main/java/vn/vnpt/customer/CustomerApplication.java` with `@SpringBootApplication(scanBasePackages = "vn.vnpt.customer") @ApplicationModule(displayName = "customer")`.
  - [ ] Create `services/customer/src/main/resources/application.yml` (port 8088, customer_db datasource, FR-29 deny-list).
  - [ ] Create `services/customer/src/main/resources/logback-spring.xml` (include util's logback-include.xml).

- [ ] **Task 2 — V001 Flyway migration** (AC: #2, #4)
  - [ ] `services/customer/src/main/resources/db/migration/customer/V001__create_customer_tables.sql` — `customer` table + `address` table per AC #2 + AC #4.

- [ ] **Task 3 — JPA entities + repositories** (AC: #2, #4)
  - [ ] `services/customer/.../infrastructure/entity/Customer.java` — `@Entity @Table(name = "customer")` with `@Id Long id`, `Long userId`, `String displayName`, `String email`, `String phone`, `LocalDateTime createdAt`. Lombok `@Getter @Builder @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode(of = "id")`.
  - [ ] `services/customer/.../infrastructure/entity/Address.java` — `@Entity @Table(name = "address")` with `@Id Long id`, `@ManyToOne @JoinColumn(name = "customer_id") Customer customer`, `String line1`, `String provinceCode`, `String districtCode`, `String communeCode`, `boolean isDefault`, `LocalDateTime createdAt`.
  - [ ] `CustomerRepository extends JpaRepository<Customer, Long>`; `AddressRepository extends JpaRepository<Address, Long>` with `List<Address> findByCustomerId(Long customerId)`.

- [ ] **Task 4 — Domain + use cases** (AC: #2, #4)
  - [ ] `services/customer/.../domain/Customer.java` — record `(long id, long userId, String displayName, String email, String phone, List<Address> addresses)`.
  - [ ] `services/customer/.../application/port/CreateCustomerCommand.java` + `AddAddressCommand.java` (records with trust-boundary validation).
  - [ ] `services/customer/.../application/usecase/CreateCustomerUseCase.java` — `@Service @Transactional`; creates a Customer; emits no event (the saga integration is a future story).
  - [ ] `services/customer/.../application/usecase/AddAddressUseCase.java` — `@Service @Transactional`; loads the Customer; creates an Address; saves both.
  - [ ] `services/customer/.../application/usecase/GetCustomerWithAddressesUseCase.java` — `@Transactional(readOnly = true)`; loads Customer + addresses.

- [ ] **Task 5 — REST controller** (AC: #5)
  - [ ] `services/customer/.../application/web/CustomerController.java` — `@RestController @RequestMapping("/api/customers")`. Endpoints: `POST /api/customers` (create), `GET /api/customers/{id}` (get with addresses), `POST /api/customers/{id}/addresses` (add address), `GET /api/customers/{id}/addresses` (list addresses).
  - [ ] `services/customer/.../infrastructure/web/CustomerSecurityConfig.java` — permits all (mirrors `services/payment/.../PaymentSecurityConfig.java` from Story 3.2).

- [ ] **Task 6 — Tests** (AC: #2, #3, #5)
  - [ ] `services/customer/src/test/java/vn/vnpt/customer/application/usecase/CreateCustomerUseCaseTest.java` — 3 tests: `execute_persistsCustomerWithGeneratedId`, `execute_throwsOnNullUserId`, `execute_throwsOnNullDisplayName`.
  - [ ] `services/customer/src/test/java/vn/vnpt/customer/application/usecase/AddAddressUseCaseTest.java` — 2 tests: `execute_persistsAddressLinkedToCustomer`, `execute_throwsOnUnknownCustomer`.
  - [ ] `services/customer/src/test/java/vn/vnpt/customer/application/usecase/GetCustomerWithAddressesUseCaseTest.java` — 2 tests: `execute_returnsCustomerWithAddresses`, `execute_throwsOnUnknownCustomer`.

- [ ] **Task 7 — Runtime smoke script** (AC: #5)
  - [ ] `dev/scripts/smoke-customer-5-1.sh` — bash. Pattern mirrors `smoke-order-4-4.sh`:
        - (a) Free port 8088.
        - (b) Create `customer_db` (the smoke handles the create-DB pre-step).
        - (c) Start `services/customer` with `SPRING_PROFILES_ACTIVE=dev`.
        - (d) Wait for `/actuator/health` UP (up to 90s).
        - (e) `POST /api/customers` — assert 201.
        - (f) `POST /api/customers/{id}/addresses` — assert 201.
        - (g) `GET /api/customers/{id}/addresses` — assert 1 address.
        - (h) `psql -c "SELECT count(*) FROM customer"` — assert 1 row.
        - (i) Kill the process, exit 0.

## Dev Notes

### Implementation Notes

- **`services/customer/` is freshly bootstrapped** (current `pom.xml` is a packaging-pom stub). The dev agent must change packaging to `jar` and add all the dependencies per AC #1.
- **Per-service database** is `customer_db` per ADR-03. The dev docker-compose has Postgres already running; the smoke creates the database if missing.
- **Vietnamese address codes** are stored as strings (e.g., `"01"` for Hà Nội, `"001"` for Ba Đình). v1 doesn't enforce FK to a master-data table — future ops story wires the master-data table.
- **The Customer aggregate does NOT use append-only event sourcing** (unlike Order from Story 4.1). Customer is a simpler read/write aggregate; the address book is part of the same transactional boundary. Event sourcing is reserved for the Order aggregate (where the append-only log provides replay-ability for debugging — per FR-30).
- **The `userId` column is `BIGINT NOT NULL` but has no FK** — the auth service isn't built yet (Story 5.4). Future story adds the FK + the auth-side join.
- **Trust-boundary validation** in the `CreateCustomerCommand` + `AddAddressCommand` records (per the codebase's pattern from Story 3.1 / 3.3): `userId > 0`, `displayName != null && !blank`, `email` (if present) matches a basic email regex, `line1 != null && !blank`.
- **Test counts target** — `≥ 7 new tests` (CreateCustomer 3 + AddAddress 2 + GetCustomerWithAddresses 2). Customer service baseline: 0; target: ≥ 7.

### Debug Log References

(none — fresh story; debugs land in Dev Notes / Completion Notes after the dev agent runs)

### Completion Notes List

(empty — populated by dev-story)

### Deviations from literal story text (flagged for review)

(none — fresh story; deviations land here after dev-story)

## Dev Notes (post-dev-story section — populated after implementation)

### Project Structure Notes

- **Path placement** (per architecture §6):
  - `services/customer/pom.xml` ← change to jar (Task 1)
  - `services/customer/src/main/java/vn/vnpt/customer/CustomerApplication.java` ← new (Task 1)
  - `services/customer/src/main/resources/application.yml` + `logback-spring.xml` ← new (Task 1)
  - `services/customer/src/main/resources/db/migration/customer/V001__create_customer_tables.sql` ← new (Task 2)
  - `services/customer/src/main/java/vn/vnpt/customer/infrastructure/entity/Customer.java` + `Address.java` ← new (Task 3)
  - `services/customer/src/main/java/vn/vnpt/customer/infrastructure/repository/CustomerRepository.java` + `AddressRepository.java` ← new (Task 3)
  - `services/customer/src/main/java/vn/vnpt/customer/domain/Customer.java` + `Address.java` ← new (Task 4)
  - `services/customer/src/main/java/vn/vnpt/customer/application/port/CreateCustomerCommand.java` + `AddAddressCommand.java` ← new (Task 4)
  - `services/customer/src/main/java/vn/vnpt/customer/application/usecase/CreateCustomerUseCase.java` + `AddAddressUseCase.java` + `GetCustomerWithAddressesUseCase.java` ← new (Task 4)
  - `services/customer/src/main/java/vn/vnpt/customer/application/web/CustomerController.java` ← new (Task 5)
  - `services/customer/src/main/java/vn/vnpt/customer/infrastructure/web/CustomerSecurityConfig.java` ← new (Task 5)
  - `services/customer/src/test/java/vn/vnpt/customer/...` ← new tests (Task 6)
  - `dev/scripts/smoke-customer-5-1.sh` ← new (Task 7)

- **Detected conflicts / variances (with rationale):**
  - **`userId` has no FK** — the auth service isn't built yet (Story 5.4). The column is just `BIGINT NOT NULL`; future story adds the FK.
  - **Address codes stored as strings** — no master-data table for province/district/commune; v1 stores the code strings.

### References

- [Source: `_bmad-output/planning-artifacts/epics.md:790-801` — Story 5.1 AC]
- [Source: `_bmad-output/planning-artifacts/prd.md:104-106` — FR-45 (Customer separate from auth User) + FR-47 (Vietnamese address hierarchy)]
- [Source: `_bmad-output/planning-artifacts/architecture.md:212` — ADR-03: database-per-service]
- [Source: `_bmad-output/planning-artifacts/architecture.md:235` — Sprint 5: "Customer + Auth | customer, auth | FR-45..50, FR-73..77"]
- [Source: `services/order/src/main/java/vn/vnpt/order/OrderApplication.java` — Story 4.1 module pattern (mirror for customer)]
- [Source: `services/order/pom.xml` — Story 4.1 pom pattern (mirror for customer)]
- [Source: `util/src/main/java/vn/vnpt/util/common/unit/ProvinceDto.java` + `DistrictDto.java` — Vietnamese address hierarchy (per AC #3)]
- [Source: `services/order/.../application/web/OrderController.java` — Spring Boot 4 controller pattern (mirror for customer)]
- [Source: `services/payment/.../infrastructure/web/PaymentSecurityConfig.java` — Spring Security config pattern (mirror for customer)]
- [Source: project memory `runtime-smoke-rule.md` — every dev-story must runtime-smoke
- [Source: project memory `deep-review-rules.md` — F1: shared code in util/, 1-sentence javadoc, no single-impl abstractions

## Dev Agent Record

### Agent Model Used

{{agent_model_name_version}}

### Debug Log References

### Completion Notes List

### File List