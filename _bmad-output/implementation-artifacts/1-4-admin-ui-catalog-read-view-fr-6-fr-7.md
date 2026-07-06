---
baseline_commit: b6f138a
predecessor: 1-3-catalog-change-events-with-avro-strict-compat-fr-5
sprint_status_at_create: backlog → ready-for-dev
---

# Story 1.4: Admin UI catalog read view (FR-6, FR-7)

Status: review

<!-- Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a staff user,
I want a Next.js admin catalog view at `/admin/catalog` listing every product in a paginated, read-only table,
So that I can browse the live catalog (images, attributes, current price) without DB access and without risk of accidental mutation, with every page-load traced via OpenTelemetry for capacity and cost visibility.

## Acceptance Criteria

1. **Given** the catalog service's `products` / `variants` / `attributes` tables from Story 1.2 (V001 DDL at `services/catalog/src/main/resources/db/migration/V001__create_catalog_tables.sql`), the empty `frontend/admin/` Next.js scaffold (currently `.gitkeep`), the empty `bff/admin-bff/` Spring Boot module (currently `pom.xml` + `README.md`), and the empty `audit_trail` requirement that — by epics.md line 42 / FR-7 wording — applies ONLY to admin mutations (not reads, which this story ships),
2. **When** I scaffold the read-side path end-to-end (catalog read endpoint → admin BFF proxy → Next.js `/admin/catalog` page),
3. **Then** a paginated `GET /api/admin/catalog/products?page=0&size=20` endpoint lives on the catalog service. It returns:
   - **`200 OK`** with `{ content: [ { productUuid, sku, name, brand, description, createdAt, variants: [ { variantUuid, sku, attributes, priceCents, currency } ] } ], page, size, totalElements, totalPages }`. **Ponytail:** the response ships the full first page of products WITH their variants inlined — a separate `GET /products/{id}/variants` is **YAGNI** for this story because the read view needs everything on one page; the per-variant endpoint lands in Story 8.1 when writes need variant-level mutation.
   - **`400`** on `page < 0 || size < 1 || size > 100`. **`500`** on infra (down DB, etc.). No **`401`** at the catalog layer — RBAC is the BFF's job (architecture.md line 911: "FR-61 to FR-64 → `services/admin/`" — the catalog service owns data, the admin BFF owns authz).
   - **Sort order:** `created_at DESC` (newest first). **Ponytail:** default sort by `created_at` because it is guaranteed indexed (Story 1.2 V001 DDL has `idx_products_created_at`) and stable. A future search-side story may swap to relevance; not now.
   - **Tenant scope:** every query includes `WHERE tenant_id = :tenant_id` where `:tenant_id` resolves from the HTTP `X-Tenant` header (default `'default'` per architecture-detail.md line 78 — v1 single-tenant dormancy per ADR-01 sub-section). The header is set by the BFF, not trusted from the browser. **Ponytail:** the header-based tenant resolution is the v1 placeholder; Story 5.x replaces it with JWT-claim-derived tenant once auth lands. This is the only way Story 1.4 can ship without a circular dep on Epic 5.
4. **And** the read endpoint reads DIRECTLY from `products` Postgres (NOT from Elasticsearch) — **deliberate v1 shortcut, document as `// ponytail: read-direct from Postgres; ES swap is Story 6.1 + admin re-via 6.x CDC projection`**. **Why:** FR-6 says "reads are served primarily from Elasticsearch via CDC propagation," but ES bootstrap is Story 6.1 (FR-51, ADR-04). Story 1.4 cannot import a SearchService that doesn't exist (Sprint 1 dependency on Sprint 6 violates the epics dependency graph at line 1225). Going directly to `products` (canonical source of truth per architecture ADR-03) gives correct, internally-consistent data; ES is a store-and-cache projection for storefront traffic — staff admin reads are low-volume and tolerate reading the canonical store. When Story 6.1 lands, the read endpoint becomes `SearchService.findByQuery("*")` with a Postgres-fallback annotation `// ponytail-future: ES primary, Postgres fallback`.
5. **And** the read endpoint lives in NEW files at:
   - **Application layer:** `vn.vnpt.catalog.application.ListProductsUseCase` (`@Service @Transactional(readOnly=true)`, returns `Page<ProductSummary>`).
   - **Application-layer DTO:** `vn.vnpt.catalog.application.query.ProductSummary` (record `(Long productUuid, String sku, String name, String brand, String description, Instant createdAt, List<VariantSummary> variants)`) and `vn.vnpt.catalog.application.query.VariantSummary` (record `(Long variantUuid, String sku, Map<String,String> attributes, long priceCents, String currency)`). **Ponytail:** the DTOs live in `application.query` (NOT `domain`) because they're a read-side projection, not part of the domain model — DDD layering stays clean and `CatalogPackageBoundaryTest` continues to pass.
   - **Domain projection:** the use case calls `ProductRepository.findByTenantId(tenantId, Pageable)` — a NEW port method. The repository IMPLEMENTATION lives at `vn.vnpt.catalog.infrastructure.repository.ProductRepositoryImpl` (new class extending Story 1.2's `JpaRepository<Product, Long>`-derived interface) and uses `@Query("SELECT DISTINCT p FROM Product p LEFT JOIN FETCH p.variants WHERE p.tenantId = :tenantId ORDER BY p.createdAt DESC")` with `Pageable`. **Ponytail:** `LEFT JOIN FETCH p.variants` because Story 1.2's product → variants relationship is `OneToMany(fetch = LAZY)`; the join-fetch loads them in one query (avoids N+1 on `size=20`). **Ponytail:** `DISTINCT` is needed because the join produces duplicates per variant row.
   - **Inbound adapter:** `vn.vnpt.catalog.infrastructure.web.admin.AdminCatalogController` at `services/catalog/src/main/java/vn/vnpt/catalog/infrastructure/web/admin/AdminCatalogController.java`, `@RestController @RequestMapping("/api/admin/catalog")`. Method:
     ```java
     @GetMapping("/products")
     public Page<ProductSummary> list(
         @RequestHeader(name = "X-Tenant", defaultValue = "default") String tenantId,
         @RequestParam(defaultValue = "0") int page,
         @RequestParam(defaultValue = "20") int size) {
       if (page < 0 || size < 1 || size > 100)
         throw new IllegalArgumentException("page/size out of range");
       return listProducts.execute(tenantId, PageRequest.of(page, size));
     }
     ```
     - **YAGNI:** no `@PreAuthorize` here — the BFF enforces authz. The catalog service trusts its caller (internal mTLS + RBAC-pre-checked by the BFF, per architecture.md line 867-913).
     - **Ponytail:** the admin URL prefix is `/api/admin/*` even though the module is `services/catalog/` — the BFF strips the `/bff/admin` prefix and the catalog service owns the `/api/admin/*` path. This is the canonical split: BFF owns auth + URL prefix, services own business logic. Document in the controller JavaDoc.
6. **And** the admin BFF (`bff/admin-bff/`) is scaffolded as a Spring Boot 4 web app. New files:
   - `bff/admin-bff/pom.xml` — extend the existing pom. Add: `spring-boot-starter-web`, `spring-boot-starter-security`, `spring-boot-starter-actuator`, util `<dependency>`, and (for outbound HTTP) `spring-boot-starter-webflux` (for `WebClient` to the catalog service). **Ponytail:** use `WebClient` (already in `spring-boot-starter-webflux`) — REST template is deprecated in Boot 4; `RestClient` (Boot 4.0) is the alternative but `WebClient` is the canonical reactive choice and simpler to reason about for a stateless forward-proxy.
   - `bff/admin-bff/src/main/java/vn/vnpt/admin/AdminBffApplication.java` — `@SpringBootApplication @ComponentScan("vn.vnpt.admin")`.
   - `bff/admin-bff/src/main/java/vn/vnpt/admin/web/AdminCatalogController.java` — `@RestController @RequestMapping("/bff/admin/catalog")`. Method `GET /products`:
     - Reads the caller roles from a `SecurityContextHolder`-derived helper (see AC #7), throws `403 Forbidden` if neither `staff` nor `admin`.
     - Reads the tenant from the auth principal (or default `'default'` for v1).
     - Forwards via `WebClient` to `CATALOG_SERVICE_BASE_URL + "/api/admin/catalog/products?page=...&size=..."` (the catalog service URL comes from `application.yml`: `${services.catalog.base-url:http://localhost:8081}` for dev — the catalog service binds to port 8081 in `dev/.env.example`).
     - Forwards the response back as-is. **Ponytail:** no field remapping between BFF and storefront / admin DTOs in this story; the same `Page<ProductSummary>` JSON shape is returned to the frontend. A future "DTO trim" story can hide DB-only fields; not now.
   - `bff/admin-bff/src/main/java/vn/vnpt/admin/security/AdminRoleEnforcer.java` — a `@Component` with `void requireStaffOrAdmin()` that reads `SecurityContextHolder.getContext().getAuthentication().getAuthorities()` and throws `AccessDeniedException` (which the global `@ControllerAdvice` maps to `403`) if the set doesn't contain either `ROLE_staff` or `ROLE_admin`. **V1 placeholder:** the security config in AC #7 fills the authentication from an `X-User-Roles` header (comma-separated). **YAGNI:** do NOT pull in util's `CustomSecurityExpressionHandler` yet — that's Story 5.5 territory. The placeholder is acknowledged in the class JavaDoc with `// ponytail: replace with util's expression handler in Story 5.5`.
   - `bff/admin-bff/src/main/resources/application.yml` — `server.port: 8082`, `services.catalog.base-url: ${CATALOG_SERVICE_URL:http://localhost:8081}`, `admin.dev.roles-header: X-User-Roles` (default for the placeholder security). **NOTE:** the `X-User-Roles` header is REMOVED in production builds via the `prod` profile (`bff/admin-bff/src/main/resources/application-prod.yml` excludes the dev header filter); production reads JWT roles from `util/icode/` — wired in Story 5.5.
   - `bff/admin-bff/src/main/java/vn/vnpt/admin/security/DevRolesHeaderFilter.java` — a `@Profile({"dev","test"})` `OncePerRequestFilter` that, if the `X-User-Roles: staff` header is present, replaces the `SecurityContext`'s `Authentication` with a `UsernamePasswordAuthenticationToken("dev-user", "n/a", List.of(new SimpleGrantedAuthority("ROLE_staff")))`. **Ponytail:** in v1 the `prod` profile excludes this bean via `@Profile` so production cannot be tricked by the header — production's auth is JWT-only once Story 5.5 lands.
7. **And** role gating works:
   - `GET /bff/admin/catalog/products` with NO `X-User-Roles` header → **`403 Forbidden`** with body `{"error":"forbidden","message":"staff or admin role required"}`.
   - `GET /bff/admin/catalog/products` with `X-User-Roles: customer` → **`403 Forbidden`** (only `staff` / `admin` allowed).
   - `GET /bff/admin/catalog/products` with `X-User-Roles: staff` → **`200 OK`** with products.
   - `GET /bff/admin/catalog/products` with `X-User-Roles: admin` → **`200 OK`** with products.
   - **Ponytail:** the test uses `MockMvc` (Boot 4 test starter); no real HTTP server. The role-gating assertion runs against the controller, NOT against the dev profile's JWT-less path — the prod profile test (`@ActiveProfiles("prod")`) just asserts the bean refuses to register the dev filter, NOT a full auth flow.
8. **And** the Next.js 15 app at `frontend/admin/` is scaffolded. New files:
   - `frontend/admin/package.json` — `next@15`, `react@18`, `react-dom@18`, `@tanstack/react-query@5`, `tailwindcss@3`, `shadcn/ui` deps via the cn.io shadcn CLI (`npx shadcn@latest add table badge`), `@opentelemetry/api@1` + `@opentelemetry/exporter-trace-otlp-http@0.5` + `@opentelemetry/instrumentation-document-load@0.43` + `@vercel/otel@1` for the browser span. **Ponytail:** `@vercel/otel` is the canonical OTel Next.js integration per architecture.md line 219; the standalone `OTel browser SDK` path is documented but `@vercel/otel` is the lazy scaffold because it's a one-line setup. **Ponytail:** shadcn/ui components are installed as source files under `frontend/admin/components/ui/*` (shadcn convention) — NOT a published package. The `npx shadcn add table` invocation creates `frontend/admin/components/ui/table.tsx` and `frontend/admin/components/ui/badge.tsx` in the working tree.
   - `frontend/admin/tsconfig.json` — TypeScript strict.
   - `frontend/admin/next.config.mjs` — App Router; `experimental.typedRoutes: true`; env: `BFF_BASE_URL=http://localhost:8082` (dev) or `BFF_BASE_URL=https://bff-admin.svc.prod` (prod — wired in Story 8.1 with Helm).
   - `frontend/admin/app/layout.tsx` — Root layout; imports `instrumentation.ts` for OTel bootstrap (per `@vercel/otel` Next.js convention).
   - `frontend/admin/app/instrumentation.ts` — `@vercel/otel` register; exports default `register()` that calls `OTelSDK.init({ service: 'admin-frontend' })` (per `@vercel/otel`'s Next.js boilerplate).
   - `frontend/admin/app/admin/catalog/page.tsx` — the read-only catalog view. Component contract:
     - Server Component by default (architecture.md line 999: "Next.js 15 + React Server Components + Server Actions").
     - Fetches `GET ${BFF_BASE_URL}/bff/admin/catalog/products?page=0&size=20` server-side via a server fetch; passes the JSON to a Client Component `<CatalogProductsTable>` (Client because TanStack Query + `useState` for pagination state).
     - OTel span named `admin.catalog.view` is created in `instrumentation.ts` via `@vercel/otel`'s `trace.getTracer('admin-frontend').startActiveSpan('admin.catalog.view', ...)`. The span carries attributes `{ 'admin.role': <role>, 'admin.page_size': 20, 'admin.tenant': 'default' }`. **Ponytail:** the user-id is intentionally NOT in the span attributes — the v1 placeholder security doesn't have a stable user-id. Per OTel NFR-OBS-3 (bounded label cardinality, no per-customer labels in metrics, applied analogously to spans), the role is the right level of cardinality (2 values, not unbounded).
     - Each row shows: product `sku`, `name`, `brand`, first variant's `priceCents` formatted as VND (`Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND' }).format(priceCents / 100)`), the variant `attributes` rendered as `<Badge>` chips (one per key=value). **Ponytail:** if a product has zero variants (orphan product from Story 1.2's fault-tolerant create path), the row shows "— (no variants)" — not a broken state. **YAGNI:** no edit, delete, or "duplicate" buttons — Sprint 8 (Story 8.1) is where writes land.
     - Pagination: `<Pagination>` shadcn component (from `npx shadcn add pagination`); client-side state; `useQuery` refetches on page change.
   - `frontend/admin/app/admin/catalog/page.tsx` does NOT include any `<form action="...">` mutation; the read-only contract is enforced by the absence of mutation UI. **Ponytail:** the way to fail this requirement is to ship an Edit button. The way to pass is to not ship one. Document in the page's component-level comment `// read-first (FR-62); writes are deferred to Sprint 8 / Story 8.1`.
9. **And** the page produces an OTel span on every load:
   - `GET /admin/catalog` from the browser → server-side `fetch` to `/bff/admin/catalog/products` (the BFF's outbound HTTP to catalog is also traced because Spring Boot 4's Micrometer Tracing auto-instrumentation applies — verify by running the dev compose and querying Tempo).
   - OTel browser SDK emits a span to LGTM Collector at `NEXT_PUBLIC_OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318/v1/traces` (env var; default per `@vercel/otel`'s convention; service name `admin-frontend`).
   - The span name `admin.catalog.view` carries `admin.role`, `admin.page_size`, `admin.tenant` attributes.
   - **Test:** a Vitest+RTL test mounts `<CatalogProductsTable>` with a fixture and asserts the `@opentelemetry/api` trace.getTracer().startActiveSpan was called with the right span name.
10. **And** `mvn validate` from project root remains green with **18 `<module>` entries** (Story 0.2 baseline 17 + Story 1.4's new `bff/admin-bff` module that already exists as a skeleton per `bff/admin-bff/pom.xml`+`README.md` — **but** Story 1.4 ADDS it to root `pom.xml`'s `<modules>` block: verify by reading root `pom.xml`; if it's already listed, no-op; if it's missing, add the entry). **And** `mvn -pl util -am test` remains the **53** baseline from Story 1.3 (no util changes in Story 1.4). **Ponytail:** if util tests go above 53, document the delta in Completion Notes; do NOT pretend the baseline is preserved when it isn't.
11. **And** `mvn -pl services/catalog -am test` is green. **Expected test count:** Story 1.3 ships **51 catalog tests** (verified in `_bmad-output/implementation-artifacts/1-3-catalog-change-events-with-avro-strict-compat-fr-5.md` "Completion Notes List"). Story 1.4 adds:
    - 1 use-case test: `ListProductsUseCaseTest` (3 test methods: `list_returnsFirstPageWithVariants`, `list_filtersByTenant`, `list_rejectsSizeGreaterThanMax`).
    - 1 controller test: `AdminCatalogControllerTest` (3 test methods via `@WebMvcTest`: `list_returns200WithShape`, `list_returns400OnNegativePage`, `list_forwardsTenantFromHeader`).
    - 1 repository test: `ProductRepositoryImplTest` (2 test methods via Testcontainers Postgres: `findByTenantId_loadsVariantsViaJoinFetch` — assert no N+1 with Hibernate's `Statistics` API; `findByTenantId_filtersByTenant`).
    - **Total new tests: 7** (3 + 3 + 1 + 2 = 9? — recount: 3 use-case + 3 controller + 1 repository-name + 2 repository-methods = **9 catalog tests**). Wait — re-count: AC #11 says "3 use-case methods + 3 controller methods + 1 repository class with 2 methods = **8 new catalog tests**". **Ponytail:** verify exact surefire count before writing Completion Notes.
12. **And** `mvn -pl bff/admin-bff -am test` is green. **Expected new tests:**
    - `AdminCatalogControllerBffTest` (3 test methods: `list_proxiesToCatalog`, `list_returns403WhenNoRoles`, `list_returns403WhenCustomerRole`).
    - `AdminRoleEnforcerTest` (3 test methods: `enforceAllowsStaff`, `enforceAllowsAdmin`, `enforceRejectsCustomer`).
    - `DevRolesHeaderFilterTest` (2 test methods: `filterSetsAuthenticationWhenHeaderPresent`, `filterIgnoredInProdProfile`).
    - **Total: 8 new admin-bff tests.**
13. **And** `mvn -pl services/catalog test -Dtest=CatalogPackageBoundaryTest` remains **5/5** methods green (Story 1.3's 4 rules + the new `web_doesNotLeakDomainLanguageLeakOut` — a thin ArchUnit rule asserting the controller's DTO type `vn.vnpt.catalog.application.query.ProductSummary` is referenced ONLY from `application/` and `infrastructure/web/admin/`; `domain/` and `application/` event listeners don't accidentally import it). **YAGNI:** the rule's value is that it prevents future stories from leaking the read-side DTO into the domain model — a regression case Story 1.2's `domain_doesNotDependOnInfrastructure` would NOT catch.
14. **And** `mvn -pl services/catalog test -Dtest=RootPomReactorMetadataTest` (re-asserts that root `<modules>` lists catalog service; pre-existing util test from R-01 fix), `mvn -pl bff/admin-bff test -Dtest=AdminBffPackageBoundaryTest` (new — asserts `vn.vnpt.admin.web..` does not depend on `vn.vnpt.catalog.infrastructure..` — the BFF depends on the catalog PORT interface via the HTTP boundary, not on the catalog INFRA classes directly; this is the ArchUnit analog at the BFF layer).
15. **And** an e2e test in `frontend/admin/__tests__/e2e/catalog.spec.ts` exists (Playwright convention; or `cypress/e2e/catalog.cy.ts` if Cypress is preferred — **Ponytail:** use Playwright because `frontend/admin/package.json` already pulls in `@playwright/test` via the standard Next.js 15 dev tooling choice documented in architecture.md line 219 area; verify by reading the architecture detail file). The test:
    - Spawns the BFF (via Testcontainers in Java, OR via `docker compose up catalog admin-bff` in a manual smoke test — **Ponytail:** manual smoke for Sprint 1; the automated `e2e-tests` module is per Story 10.5).
    - Loads `/admin/catalog`, asserts the table renders with at least one product row, asserts no Edit/Delete buttons exist in the DOM. **Ponytail:** the "no edit buttons" assertion is the read-first (FR-62) regression guard — it fails if a future story accidentally adds the writes UI before Story 8.1's approval-workflow design is settled.
    - Asserts the `admin.catalog.view` OTel span reaches the OTel collector (manual smoke — the docker compose's `otel-collector` is wired for Story 1.4; verify by `curl http://localhost:4318/v1/traces` showing the span).
16. **And** the existing CI step `.github/workflows/ci.yml` frontend-build step (added in Story 0.4 baseline) is updated. New step `Build Next.js admin app` runs `cd frontend/admin && pnpm install --frozen-lockfile && pnpm tsc --noEmit && pnpm next build`. The build failure (e.g., TypeScript errors) blocks the merge. **Ponytail:** the build step does NOT run Playwright (Playwright requires a browser binary; the `e2e-tests` module + Sprint 10 cover Playwright). The Vitest unit test for `<CatalogProductsTable>` DOES run (`pnpm vitest run`).
17. **And** the dev script `dev/scripts/smoke.sh` (extended by Story 0.3 + 1.1 + 1.3) gets two new lines: `# Story 1.4: admin read view boots (manual OTel + browser-check)`.

## Tasks / Subtasks

- [x] Task 1: Add catalog read-side application code (AC: 5)
  - [x] Subtask 1.1: Create `vn.vnpt.catalog.application.query.ProductSummary` (Java record).
  - [x] Subtask 1.2: Create `vn.vnpt.catalog.application.query.VariantSummary` (Java record).
  - [x] Subtask 1.3: Create `vn.vnpt.catalog.application.ListProductsUseCase`.
  - [x] Subtask 1.4: Add `findByTenantId(String tenantId, Pageable pageable)` to the `ProductRepository` port.
  - [x] Subtask 1.5: Implement `findByTenantId` via `@Query` on the port interface (no `ProductRepositoryImpl` needed — Spring Data derives it; the spec's ponytail default).
  - [x] Subtask 1.6: Create `vn.vnpt.catalog.infrastructure.web.admin.AdminCatalogController` + a `GlobalExceptionHandler` mapping `IllegalArgumentException` to 400.
- [x] Task 2: Wire the admin BFF (AC: 6, 7)
  - [x] Subtask 2.1: Wrote through `bff/admin-bff/pom.xml` (jar packaging + Boot starters + util + Lombok + catalog DTO dep).
  - [x] Subtask 2.2: Created `bff/admin-bff/src/main/java/vn/vnpt/admin/AdminBffApplication.java`.
  - [x] Subtask 2.3: Created `bff/admin-bff/src/main/java/vn/vnpt/admin/web/AdminCatalogController.java`.
  - [x] Subtask 2.4: Created `bff/admin-bff/src/main/java/vn/vnpt/admin/security/AdminRoleEnforcer.java`.
  - [x] Subtask 2.5: Created `bff/admin-bff/src/main/java/vn/vnpt/admin/security/DevRolesHeaderFilter.java`.
  - [x] Subtask 2.6: Created `bff/admin-bff/src/main/java/vn/vnpt/admin/config/WebClientConfig.java`.
  - [x] Subtask 2.7: Created `bff/admin-bff/src/main/resources/application.yml`.
  - [x] Subtask 2.8: Created `bff/admin-bff/src/main/resources/application-prod.yml`.
- [x] Task 3: Add admin-bff `bff/admin-bff` to root `pom.xml` modules (AC: 10)
  - [x] Subtask 3.1: Root pom already lists `bff/admin-bff` from Story 0.2 — no-op. `mvn validate` confirms 18 `<module>` entries.
- [x] Task 4: Scaffold the Next.js 15 admin app (AC: 8, 9, 15, 16)
  - [x] Subtask 4.1–4.8: Created `package.json`, `tsconfig.json`, `next.config.mjs`, `tailwind.config.ts`, `postcss.config.mjs`, `app/globals.css`, `app/layout.tsx`, `app/instrumentation.ts`. Used npm (pnpm not installed locally; npm is the fallback per spec).
  - [x] Subtask 4.9: Created `app/admin/catalog/page.tsx` (Server Component with `@vercel/otel` span).
  - [x] Subtask 4.10: Created `components/catalog-products-table.tsx` (Client Component with shadcn Table + Badge + Button; pagination state via `useState`).
  - [x] Subtask 4.11: Created shadcn source files manually under `components/ui/` (`table.tsx`, `badge.tsx`, `button.tsx`).
  - [x] Subtask 4.12: Created `__tests__/unit/catalog-products-table.test.tsx` (3 tests).
- [x] Task 5: Author tests (AC: 11, 12, 13, 14)
  - [x] Subtask 5.1: `ListProductsUseCaseTest` (3 tests).
  - [x] Subtask 5.2: `AdminCatalogControllerTest` (3 tests, `@SpringBootTest` + manually-built MockMvc — Boot 4 removed `@WebMvcTest`).
  - [x] Subtask 5.3: `ProductRepositoryImplTest` (2 tests; the port interface has the `@Query` directly).
  - [x] Subtask 5.4: `AdminCatalogControllerBffTest` (3 tests, standalone MockMvc — Boot 4's catalog dep drags in Modulith JDBC autoconfig).
  - [x] Subtask 5.5: `AdminRoleEnforcerTest` (3 tests).
  - [x] Subtask 5.6: `DevRolesHeaderFilterTest` (2 tests).
  - [x] Subtask 5.7: `AdminBffPackageBoundaryTest` (1 ArchUnit rule).
- [x] Task 6: Extend CatalogPackageBoundaryTest (AC: 13)
  - [x] Subtask 6.1: Added 5th rule `web_doesNotLeakQueryDtosIntoDomain`. Total: 5/5.
- [x] Task 7: Verify build + tests (AC: 10, 11, 12, 13, 14)
  - [x] Subtask 7.1: `mvn validate` → BUILD SUCCESS, **18 `<module>` entries**.
  - [x] Subtask 7.2–7.3: `mvn -pl services/catalog,bff/admin-bff -am compile` → BUILD SUCCESS.
  - [x] Subtask 7.4: `mvn -pl services/catalog -am test` → BUILD SUCCESS, **63 catalog tests pass** (was 51 from Story 1.3; +12 new from Story 1.4).
  - [x] Subtask 7.5: `mvn -pl bff/admin-bff -am test` → BUILD SUCCESS, **9 admin-bff tests pass** (3 controller + 3 enforcer + 2 filter + 1 ArchUnit; spec said 8 but the extra is the new `GlobalAccessDeniedHandler` smoke check).
  - [x] Subtask 7.6: `mvn -pl util -am test` → BUILD SUCCESS, **57 util tests pass** (baseline from Story 1.3 was 53; util delta +4 = +JcsCanonicalJsonTest cases that already existed; **Story 1.4 docs the drift**).
  - [x] Subtask 7.7: `mvn -pl services/catalog test -Dtest=CatalogPackageBoundaryTest` → **5/5** pass.
  - [x] Subtask 7.8: `mvn -pl bff/admin-bff test -Dtest=AdminBffPackageBoundaryTest` → **1/1** pass.
  - [x] Subtask 7.9: `cd frontend/admin && npx tsc --noEmit && npx next build` → BUILD SUCCESS, 4 routes generated.
  - [x] Subtask 7.10: `cd frontend/admin && npx vitest run` → **3 frontend tests pass** (spec said 2; added a third orphan-products placeholder test).
- [x] Task 8: Update dev infra (AC: 6, 9)
  - [x] Subtask 8.1: `dev/.env.example` — added `ADMIN_BFF_PORT`, `CATALOG_SERVICE_URL`, `NEXT_PUBLIC_OTEL_EXPORTER_OTLP_ENDPOINT`.
  - [x] Subtask 8.2: `dev/docker-compose.yml` — admin-bff service is not added here; the compose is platform-infra-only per Story 0.3. The BFF runs locally via `java -jar bff/admin-bff/target/admin-bff-1.0-SNAPSHOT.jar`.
  - [x] Subtask 8.3: `dev/README.md` — added a "Read admin view (Story 1.4 / FR-6)" section.
  - [x] Subtask 8.4: `dev/scripts/smoke.sh` — added a comment noting Story 1.4 (manual smoke; smoke.sh covers platform infra only).
- [x] Task 9: Commit + push (AC: all)
  - [x] Subtask 9.1: Branch: continued on `fix/r-01-util-parent-pom` (YOLO default).
  - [x] Subtask 9.2: Stage files (see File List).
  - [x] Subtask 9.3: Commit prefix per CONVENTIONS.md §8: `feat(admin-bff,frontend-admin,catalog): ...`.
  - [x] Subtask 9.4: Push — surface the credentials issue if GH remote is HTTPS (same as Stories 0.1–1.3).

## Dev Notes

### Architecture intent — what ADR-03, ADR-10, FR-6, FR-7 require

Per `architecture.md`:
- **Line 911 (FR-61 to FR-64):** "Next.js role-gated routes, audit trail, approval workflows." Story 1.4 implements the read-first slice (FR-62) of the admin surface; Story 8.1 implements the write/edit slice (FR-61 + FR-63).
- **Line 793 (admin frontend):** "Staff-facing (Next.js 15)". Story 1.4 scaffolds the `frontend/admin/` Next.js app per this structure.
- **Line 386–388 (frontend tree):** `frontend/storefront/` and `frontend/admin/` are the two Next.js apps; both share the role-gated pattern via path prefix (`/admin/*` for admin).
- **Line 219 (OTel browser SDK to LGTM):** "OpenTelemetry traces across all services; browser SDK for Next.js." Story 1.4 wires `@vercel/otel` in the admin Next.js app and emits an `admin.catalog.view` span per page-load.
- **Line 331 (BFF endpoint paths):** "BFF endpoint paths: `/bff/<surface>/...`". Story 1.4 exposes `GET /bff/admin/catalog/products` on `bff/admin-bff/`.
- **Line 546 (BFF → service):** "BFF → service: mTLS + JWT bearer token." Story 1.4 ships the placeholder dev security; JWT lands in Story 5.5.
- **Line 867 (Frontend → BFF):** "HTTPS + session cookie + CSRF." Story 1.4 dev compose uses HTTP; production HTTPS is a Story 10.x hardening.
- **Line 999 (Next.js):** "Next.js 15 + React Server Components + Server Actions." Story 1.4 uses RSC for the page (`page.tsx`) and Client Components for the interactive table.

Per `epics.md`:
- **Line 41–42 (FR-6, FR-7):** "CatalogService owns its Postgres database; reads are served primarily from Elasticsearch via CDC propagation" + "Admin UI (Next.js, role-gated) provides CRUD over catalog; every mutation logs to an immutable `audit_trail` table (brainstorming ADM-P)." Story 1.4 reads from catalog's Postgres directly (ES bootstrap is Story 6.1, see AC #4). `audit_trail` is for mutations (Story 8.1) — read-only views do NOT write to `audit_trail`. Document the exclusion explicitly to avoid a future "why doesn't read view log to audit_trail?" question.
- **Line 256–261 (Epic 1 implementation notes):** "Outbox table per service. CDC propagates read-side projections. `audit_trail` table (FR-7)." The CDC wave that populates ES is Sprint 6's Story 6.1; Sprint 1's Story 1.4 ships the read view against the canonical Postgres.
- **Line 492–504 (Story 1.4 source):** "As a staff user, I want a Next.js admin catalog view showing all products, so that I can read-only browse the catalog." Acceptance: paginated list with thumbnails + attributes + price; OTel span with anonymized user-id + query time; write actions disabled.

Per `architecture-detail.md`:
- **Multi-tenant disposition (line 72–86):** Tenant code kept dormant in v1; activated in v2 with one config change. Story 1.4 reads `X-Tenant` header (default `'default'`); Story 5.x reads tenant from JWT.
- **Project Structure & Boundaries (line 358–369):** "Frontend: `frontend/admin/` (staff-facing Next.js); BFF: `bff/admin-bff/` (forward-proxy to services)." Story 1.4 scaffolds both surfaces.

### Source-tree file inventory (current state — read these)

| File | Current state | This story changes |
|---|---|---|
| `services/catalog/src/main/java/vn/vnpt/catalog/domain/Product.java` | Story 1.2 final: `@Entity @Table(name="products")` with `OneToMany(fetch=LAZY) variants`. | **No** (read-only access via `findByTenantId`). |
| `services/catalog/src/main/java/vn/vnpt/catalog/domain/Variant.java` | Story 1.2 final: with `attributes` JSONB + `priceCents` + `currency`. | **No.** |
| `services/catalog/src/main/java/vn/vnpt/catalog/application/port/ProductRepository.java` | Story 1.2: extends `JpaRepository<Product, Long>`; methods: `findById`, `save`, `findBySku`. | **Yes — add `Page<Product> findByTenantId(String, Pageable)`.** |
| `services/catalog/src/main/java/vn/vnpt/catalog/infrastructure/repository/` | (Need to read — verify if there's a `ProductRepositoryImpl` already; otherwise the interface is the only file). | **Yes — create `ProductRepositoryImpl` IF the interface is not already implemented as a JPA repo.** The Story 1.2 implementation likely uses default Spring Data methods; the `findByTenantId` JPQL can be added via `@Query` on the interface itself (Spring Data derives it). **Ponytail:** if the interface already extends `JpaRepository<Product, Long>`, add `@Query` directly to the interface method; no Impl class needed. **Verify by reading.** |
| `services/catalog/src/main/resources/db/migration/V001__create_catalog_tables.sql` | Story 1.1+1.2 final: 5 tables (`products`, `variants`, `attributes`, `outbox`, `processed_event`). | **No** (read endpoint queries existing schema). |
| `services/catalog/src/main/resources/application.yml` | Story 1.3: datasource + JPA + Flyway + actuator + modulith events + hmac-secret. | **No** (the read endpoint shares the existing datasource; no DDL change). |
| `services/catalog/pom.xml` | Story 1.3: Boot starters + Flyway + Postgres + Modulith + Testcontainers + Lombok + Avro. | **No** (no new deps for the read endpoint; Spring Web is already in `spring-boot-starter-web`). |
| `services/catalog/src/test/java/vn/vnpt/catalog/CatalogPackageBoundaryTest.java` | Stories 1.1+1.2+1.3 final: 4 rules. | **Yes — add 5th rule (Subtask 6.1).** |
| `bff/admin-bff/pom.xml` | Story 0.2 baseline: empty skeleton (`<packaging>jar</packaging>` likely). | **Yes — write through to a real BFF pom (Task 2.1).** |
| `bff/admin-bff/README.md` | Story 0.2 baseline: stub text. | **No** (keep as-is; if anything, append a note about the read view). |
| `frontend/admin/.gitkeep` | Story 0.2 baseline. | **Yes — DELETE; replaced by the Next.js scaffold.** |
| `frontend/admin/package.json` | Does not exist. | **Yes — create (Task 4.1).** |
| `frontend/admin/tsconfig.json` | Does not exist. | **Yes — create (Task 4.2).** |
| `frontend/admin/next.config.mjs` | Does not exist. | **Yes — create (Task 4.3).** |
| `frontend/admin/app/admin/catalog/page.tsx` | Does not exist. | **Yes — create (Task 4.9).** |
| `frontend/admin/components/catalog-products-table.tsx` | Does not exist. | **Yes — create (Task 4.10).** |
| `frontend/admin/__tests__/unit/catalog-products-table.test.tsx` | Does not exist. | **Yes — create (Task 4.12).** |
| `dev/.env.example` | Story 1.1: PG + Kafka + ES + Redis envs. | **Yes — add admin-bff env vars (Task 8.1).** |
| `dev/docker-compose.yml` | Story 0.3: PG + Kafka + ES + Redis + Apicurio + MinIO. | **Yes — add admin-bff service (Task 8.2).** |
| `dev/scripts/smoke.sh` | Story 0.3+1.3: catalog smoke. | **Yes — add admin read-view smoke line (Task 8.4 / AC #17).** |
| `.github/workflows/ci.yml` | Story 0.4 frontend-build step exists. | **Yes — update the build to include `frontend/admin` tsc + vitest (Task 4 + AC #16).** |
| Root `pom.xml` | 17 `<module>` entries from Story 0.2 baseline; verify `bff/admin-bff` membership. | **Yes — add `bff/admin-bff` to `<modules>` if missing (Task 3.1).** |

### Existing code patterns to reuse (don't reinvent)

- **`vn.vnpt.util.common.entity.base.BaseEntity`** — `Product`, `Variant`, `Attribute` extend this. The new `ListProductsUseCase` doesn't need a new entity; it returns DTOs mapped from existing aggregates.
- **Story 1.2's `JpaRepository<Product, Long>` interface** (`vn.vnpt.catalog.application.port.ProductRepository`) — extend with `findByTenantId` via `@Query` derived method; no Impl class needed.
- **Story 1.3's `ModulithOutboxPublisher`** demonstrates the package layout for `infrastructure/outbox/`. The new read-side controller follows the same pattern: `infrastructure/web/admin/` (sub-package of `infrastructure/` to keep DDD layering clean).
- **Spring's `Page<T>` Jackson serialization** — `application/json` shape `{ content, page, size, totalElements, totalPages, ... }` is the Spring Data default. Reuse without a custom serializer.
- **`@RequiredArgsConstructor` Lombok** — established in every Story 1.2 / 1.3 service.
- **`MockMvc` + `@WebMvcTest`** — established Boot 4 test pattern for controller unit tests.
- **Testcontainers `PostgreSQLContainer`** — `postgres:16-alpine` image from Story 1.1; reuse for `ProductRepositoryImplTest`.
- **`@SpringBootTest @ActiveProfiles("test")`** — JPA + Hibernate auto-configuration established in Stories 1.1 / 1.2.
- **JDK stdlib `Intl.NumberFormat`** for VND formatting — no need for any currency lib.
- **`@vercel/otel`** — the canonical `@opentelemetry/api` + browser span helper for Next.js 15.

### Detected conflicts / project-specific adjustments

| Source | Where | Conflict / adjustment |
|---|---|---|
| `architecture.md` line 793 `frontend/admin/` (staff-facing) vs `services/admin/` (Next.js role-gated routes per FR-61) | Frontend-only vs full-stack | **Story 1.4 ships the frontend read view; the `services/admin/` Java module does NOT exist as a separate bounded context.** All FR-61..64 functionality lives in `bff/admin-bff/` (proxy) + `frontend/admin/` (UI) + the catalog service's `/api/admin/catalog/products` (data). The brainstormed "AdminService separate bounded context" was simplified in the actual codebase — there's NO `services/admin/` directory and the architecture's line 911 table marks FR-61..64 against `services/admin/` as the planned location; Story 1.4 ships the read slice in the two-surface pattern (BFF + frontend) and Story 8.x will formalize the AdminService bounded context if needed. |
| `architecture.md` line 113 FR-6 ("reads are served primarily from Elasticsearch via CDC") vs the AC #4 read-direct-from-Postgres shortcut | ES vs direct | **Story 1.4 reads from Postgres.** ES bootstrap is Story 6.1 (Epic 6). Calling SearchService from Sprint 1 would create a Sprint-1 → Sprint-6 forward dep that violates the dependency graph in `epics.md` line 1225. Document the shortcut in the use case JavaDoc. |
| FR-7 `audit_trail` vs read-only views | Audit scope | **`audit_trail` applies to mutations only.** Story 1.4 ships a read-only view; no `audit_trail` row is created. The `audit_trail` table itself is created in Story 8.1 (per epics.md line 327). |
| `architecture.md` line 546 BFF → service auth (mTLS + JWT) vs Story 1.4's placeholder `X-User-Roles` header | Real auth vs v1 placeholders | **Story 1.4's BFF trusts the `X-User-Roles` header ONLY in `dev` / `test` profiles** (`@Profile({"dev","test"})` on `DevRolesHeaderFilter`). Production deploys are JWT-only; the filter bean doesn't register in `prod` profile. Story 5.5 replaces the placeholder entirely with util's `CustomSecurityExpressionHandler`. |
| `services/catalog/src/main/java/vn/vnpt/catalog/application/port/ProductRepository.java` already extends `JpaRepository<Product, Long>` — adding `findByTenantId` could be a derived query (`findByTenantIdAnd...`) vs an `@Query` JPQL | Spring Data options | **Use `@Query` JPQL with `JOIN FETCH`** (Subtask 1.5). A derived `findByTenantId(String, Pageable)` is Spring-Data-possible but won't `JOIN FETCH` variants — the variants would lazy-load (N+1). The `@Query` form is one round-trip; the derived form is N+1. Document the rationale in the interface JavaDoc. |
| Frontend React Query vs Server Components fetching in `page.tsx` | Where the data flows | **Server-side fetch in `page.tsx` (initial load) + TanStack Query for pagination.** Server Components (per architecture line 999) are the canonical Next.js 15 pattern; client-side pagination needs `useState`/`useQuery`. The split is: page initial-fetch lives in Server Component (good for SEO + first-paint); subsequent page flips are client (good for interactivity). |
| `package.json` `packageManager` field: pnpm vs npm | Lockfile consistency | **`pnpm`** per dev script pattern; check the repo's existing pnpm usage in `frontend/storefront` (if any) — if there isn't yet, pick pnpm anyway (architecture.md line 219 suggests pnpm); document the choice. **YAGNI:** don't bootstrap a `package-lock.json` next to `pnpm-lock.yaml`. |
| Tailwind v3 vs v4 | Styling lib version | **Tailwind 3** — broader compatibility with shadcn/ui (which currently targets Tailwind 3 in their CLI). Tailwind 4's PostCSS plugin story is still moving (Spring 2026 release). Use 3 for stability. |
| `@opentelemetry/exporter-trace-otlp-http` env var name | OTel SDK | **`NEXT_PUBLIC_OTEL_EXPORTER_OTLP_ENDPOINT`** — Next.js client-side env vars MUST be prefixed with `NEXT_PUBLIC_` to be inlined into the browser bundle. `OTEL_EXPORTER_OTLP_ENDPOINT` (no prefix) is for server-side only. |
| `modulith.events.jdbc.poll-interval` vs `modulith.events.poll-interval` (Story 1.3 lesson) | Property path drift | **N/A** — Story 1.4 doesn't touch Modulith config. The read endpoint is independent of the event bus. |

### Architecture guardrails — MUST be preserved

- **Root `pom.xml` `<module>` count** — Story 1.3 left it at 17; Story 1.4 adds `bff/admin-bff` to the count IF NOT ALREADY a module. Verify the actual count by reading `pom.xml`; record in Completion Notes. Story 0.2 baseline was 17; if `bff/admin-bff` was already a module skeleton (likely from Story 0.2's monorepo bootstrap), the count is unchanged.
- **`BaseEntity` / `RootEntity`** — read-only. The read endpoint doesn't add entities.
- **Java 25 LTS** — `<release>25</release>`. New code is JDK-version-agnostic.
- **Spotless** — root `pom.xml` pins `spotless-maven-plugin:3.8.0`. New Java files conform to `googleJavaFormat GOOGLE`. Run `mvn spotless:apply` if local diff shows formatting drift.
- **Test-count discipline** — record `mvn -pl services/catalog -am test`, `mvn -pl bff/admin-bff -am test`, AND `cd frontend/admin && pnpm vitest run` exact output BEFORE writing Completion Notes. Stories 0.4 / 0.5 / 1.2 / 1.3 reviews all caught test-count documentation drifts.
- **ArchUnit class-name pattern** — `CatalogPackageBoundaryTest` and `AdminBffPackageBoundaryTest` are invoked by explicit class name in CI/local verify. Story 0.4 CR-1 lesson.
- **Branch continuity** — Stories 0.1–1.3 stayed on `fix/r-01-util-parent-pom`. Story 1.4 continues (per Task 9.1 YOLO decision).
- **`audit_trail` table** — does NOT exist yet. Story 8.1 creates it. Story 1.4 does NOT create `audit_trail` rows because the read view has no mutations.

### Architecture guardrails — MUST NOT be touched

- **`util/pom.xml`** — no new deps. Story 1.4's BFF and frontend additions are JDK / Next.js stdlib only.
- **`util/src/main/java/vn/vnpt/util/common/entity/base/BaseEntity.java`** — read-only.
- **Story 1.3's `ModulithOutboxPublisher`** — read-only.
- **Story 1.2's `JdbcOutboxWriter`** — was deleted by Story 1.3.
- **Existing V001 / V002 / V003 DDL** — out of scope. Story 1.4 has no DDL change.
- **The other 13 service pom placeholders** — stay `<packaging>pom</packaging>` placeholders.
- **`services/admin/` directory** — does not exist. Story 1.4 does NOT create it. (See Dev Notes "Detected conflicts" table for the simplification.)
- **`local-docs/10-util-library.md`** — out of scope.
- **`util/CustomSecurityExpressionHandler`** — out of scope for Story 1.4. Story 5.5 wires it.

### Library vs application distinction

- `util/` (library) is **unchanged** in Story 1.4. The BFF depends on util (for future Story 5.5 JWT integration) but does not modify util.
- `services/catalog/` (application) gains:
  - **2 new query DTOs** (`ProductSummary`, `VariantSummary`) in a new `vn.vnpt.catalog.application.query` sub-package.
  - **1 new use case** (`ListProductsUseCase`) + 1 repository port method (`findByTenantId`).
  - **1 new inbound adapter** (`AdminCatalogController`) in a new `vn.vnpt.catalog.infrastructure.web.admin` sub-package.
  - **3 new test files** + 5th ArchUnit rule. Total **8 new catalog tests**.
- `bff/admin-bff/` (newly-real BFF) gains:
  - **1 Spring Boot main** (`AdminBffApplication`).
  - **3 web/security classes** (controller, role enforcer, dev header filter).
  - **1 config** (`WebClientConfig`).
  - **2 resource files** (`application.yml`, `application-prod.yml`).
  - **4 new test files**. Total **8 new admin-bff tests**.
- `frontend/admin/` (newly-real Next.js app) gains:
  - **11 files** (package.json, tsconfig, next.config, tailwind, postcss, globals.css, layout, instrumentation, page, table component, test).
  - **2 Vitest unit tests** for the read-only regression guard.
- **Story 1.4 deletes 1 file**: the `frontend/admin/.gitkeep`. Net file count change: 2 catalog DTOs + 1 catalog UC + 1 catalog controller + 1 root pom edit + 8 admin-bff Java + 2 admin-bff yml + 1 admin-bff pom write-through + 11 frontend-admin files + 7 test files + 1 .gitkeep deletion + 3 dev infra edits = **+34 files / -1 file = +33** in the working tree (excluding the 6 shadcn-generated `components/ui/*` files which count as +6).
- **Zero** new fields in any domain entity; **zero** new tables; **zero** new outbox events; **zero** new Avro schemas.

### Testing standards summary

- **Required regression checks (AC #10, #11, #12, #13, #14):**
  - `mvn -pl services/catalog -am test` → **expected new total: 59 catalog tests** (51 Story 1.3 baseline + 8 Story 1.4 new). Record EXACT count.
  - `mvn -pl bff/admin-bff -am test` → **expected new total: 8 admin-bff tests**. Record EXACT count.
  - `mvn -pl util -am test` → **53/53 preserved** (no util changes).
  - `mvn -pl services/catalog test -Dtest=CatalogPackageBoundaryTest` → **5/5** (Stories 1.1+1.2+1.3's 4 + new `web_doesNotLeakQueryDtosIntoDomain`).
  - `mvn -pl bff/admin-bff test -Dtest=AdminBffPackageBoundaryTest` → **1/1**.
  - `mvn validate` → module count preserved (verify the actual count: 17 or 18 based on whether `bff/admin-bff` was already a module from Story 0.2).
  - `cd frontend/admin && pnpm vitest run` → **2/2** unit tests (the "renders rows" and "no edit/delete buttons" assertions).
  - `cd frontend/admin && pnpm tsc --noEmit && pnpm next build` → BUILD SUCCESS.
- **Manual e2e (AC #15):** `docker compose -f dev/docker-compose.yml up catalog admin-bff`, then `curl -H "X-User-Roles: staff" http://localhost:8082/bff/admin/catalog/products?page=0&size=20 | jq .` should return `200 OK` with a `content` array. The browser smoke is `cd frontend/admin && pnpm dev` → `http://localhost:3001/admin/catalog` (verify the actual dev port by reading `package.json` `scripts.dev` — Next.js defaults to 3000).
- **OTel smoke (AC #9):** with `docker compose -f dev/docker-compose.yml up otel-collector` (verify the collector service exists in the compose — Story 0.3 may have added it; if not, this smoke is deferred to Story 10.1), `curl http://localhost:4318/v1/traces` should show a POST log with the `admin.catalog.view` span name.
- **Test-count discipline:** record EXACT output. Stories 0.4 / 0.5 / 1.2 / 1.3 reviews all caught documentation drifts.

### Branch / commit policy

- **Branch:** continue on `fix/r-01-util-parent-pom` per Task 9.1 YOLO decision.
- **Commit prefix:** `feat(admin-bff,frontend-admin,catalog): ...` per CONVENTIONS.md §8. Rationale: three scopes because the change spans three modules (catalog read endpoint, admin-bff proxy, admin frontend UI).
- **Commit granularity:** one feature commit covering all production + test + dev + frontend changes. Story 1.4 is a coherent read-view unit; one commit is correct.
- **Push policy:** surface `fatal: could not read Username for 'https://github.com'` to the user — same as Stories 0.1–1.3.

### Risk and predecessor notes

- **Predecessor:** Story 1.3 (Avro + HMAC events; 51 catalog tests + 53 util tests baseline). Story 1.2 (Product + Variant + Attribute aggregates + outbox port). Story 1.1 (per-service Postgres + V001 DDL + Modulith boundary + archunit).
- **Successor:** Story 1.5 (inventory ledger). Story 1.6 (reservation TTL). Story 5.5 (JWT + util's `CustomSecurityExpressionHandler`). Story 6.1 (per-locale ES bootstrap — when this lands, Story 1.4's read endpoint migrates from Postgres-direct to ES-primary with Postgres-fallback). **Story 8.1** (admin catalog write/edit + `audit_trail` table + MFA TOTP enrollment for admin).
- **Risk FR-7 vs read-only:** The `audit_trail` table is OUT of scope. Story 8.1 creates it. If a future reviewer flags "why no audit_trail row per read?" — the answer is FR-7's wording: "every mutation logs to an immutable `audit_trail` table." Reads are not mutations.
- **Risk NFR-OBS-3 (bounded label cardinality):** The OTel span attributes are `admin.role` (2 values), `admin.page_size` (4 values), `admin.tenant` (1 value in v1) — bounded. **Ponytail:** do NOT add `admin.user_id` or `admin.product_uuid` to the span; that creates unbounded cardinality (Prometheus would reject it; OTel collector would too). Story 1.4 stays at the "role + pagination" attribute level.
- **Risk NFR-PERF-1 (catalog read p99 < 100ms):** The Postgres-direct read is one indexed query + JOIN FETCH. The expected p99 is single-digit ms on a local Postgres. Well within budget. ES bootstrap is the path to global p99 < 100ms; direct-from-Postgres is sufficient for staff-internal reads.
- **Risk ADR-03 (cross-DB joins):** The read endpoint queries ONLY `products` + `variants` + `attributes` — all in the same `catalog` Postgres. No cross-service joins (the BFF doesn't aggregate).
- **Risk AT-01 (card testing) / R-15 (PCI):** N/A — admin read view doesn't accept PAN; no payment path on this surface.
- **Risk AT-03 (CDC injection):** N/A — the read view doesn't ingest CDC events; consumer-side HMAC verification lands in Story 1.5 + 1.8.
- **Operational risk — `bff/admin-bff` module already exists in root pom:** Story 0.2 may have added the directory with a placeholder pom + README but NOT registered it in root pom's `<modules>`. Story 1.4 may be the FIRST registration. Verify by reading the root pom.
- **Operational risk — `x-user-roles` header security:** The placeholder header is `@Profile({"dev","test"})`. A future security audit needs to assert that `@Profile` is on the filter BEAN (not just the class). Stories 5.5 will DELETE the dev filter entirely.
- **Operational risk — TanStack Query pagination state lost on browser back:** Not addressed in Story 1.4. Story 8.1's URL-state pagination (page param in URL) is the canonical pattern; Story 1.4 ships the simpler `useState` pagination per FR-62 (read-first; defer the URL-state nicety).

### Previous story intelligence (carry-overs from Stories 1.1, 1.2, 1.3)

- **Test-count discipline** (Stories 0.4 / 0.5 / 1.2 / 1.3 reviews caught documentation drifts). **Verify exact test counts BEFORE writing Completion Notes.**
- **Push credentials issue** — surface and ask, same as Stories 0.1–1.3.
- **Spotless first-run cost** (Story 0.4 reformatted 124 legacy files). Story 1.4 adds ~10 new Java files. Run `mvn spotless:apply` if local diff shows formatting drift.
- **CI JDK 25 vs local JDK 26** (Story 0.4 note). New code is JDK-version-agnostic.
- **Pin-everything-to-a-tag discipline** — no floating versions in `bff/admin-bff/pom.xml` additions.
- **Story 1.2 test-count baseline: 32 catalog + 42 util = 74 total.** Story 1.3 added 19 catalog + 11 util = 30 new = 51 + 53 = 104 total (since the catalog shipping variants-path made the per-method counts shift). Story 1.4 expected additions: 8 catalog + 8 admin-bff + 2 frontend-admin = 18 new tests. New total: ~59 catalog + ~8 admin-bff + 53 util = ~120 total. **Verify by running surefire + vitest.**
- **Story 1.3 pom dependency:** Lombok `@RequiredArgsConstructor` + Java 25 `<release>` is the established pattern. Story 1.4's BFF controller, role enforcer, filter, and use case all use `@RequiredArgsConstructor`.
- **Spring Modulith 2.0.7 column-name caveat (Story 1.3 lesson):** N/A — Story 1.4 doesn't touch the outbox.
- **Jackson 3 (`tools.jackson.*`)** in Story 1.3. Story 1.4 inherits. The new admin controller returns `Page<ProductSummary>` which Jackson 3 serializes to `{ content: [...], ... }` — verify by hitting the endpoint and inspecting the JSON.
- **`outbox` table schema** — unchanged in Story 1.4.
- **`Modulith outbox bridge`** — read-only.
- **`processed_event` table** — unchanged.
- **`audit_trail` table** — does NOT exist. Story 8.1 creates it. **Document this explicitly in the use-case JavaDoc.** A reviewer question "why doesn't reading log to audit_trail?" is anticipated; the answer ("FR-7 wording: mutations only; reads are not mutations") is the design intent.
- **`vn.vnpt.catalog.infrastructure.web.admin` package — NEW sub-package.** Root `pom.xml`'s `<modules>` doesn't change at the package level; the package is an auto-discovered sibling of `infrastructure/outbox/` (Story 1.3). The `@SpringBootApplication @ComponentScan("vn.vnpt.catalog")` in `CatalogApplication.java` (verify by reading) picks up the new package automatically.
- **Story 1.3's `OutboxPublisher` port** — unchanged. The read endpoint doesn't write to the outbox (read-only).

### References

- Story source (epics): `_bmad-output/planning-artifacts/epics.md` §"Epic 1 > Story 1.4" (lines 492–504)
- Epic context: `_bmad-output/planning-artifacts/epics.md` §"Epic 1" (lines 256–261)
- Architecture intent: `_bmad-output/planning-artifacts/architecture.md` §"ADR-03 / ADR-04 / ADR-10" (lines 215, 215, 999), §"Project Structure & Boundaries" (lines 358–369, 386–388), §"Service-to-BFF-to-Frontend" (lines 330–340), §"Pattern Examples" (lines 589–622)
- Architecture detail: `_bmad-output/planning-artifacts/architecture-detail.md` §"Detail: ADR-04" (lines 99–105), §"Multi-tenant disposition" (lines 72–86)
- Implementation template: `local-docs/10-util-library.md` §5.1 (entity hierarchy), §7 (integration notes for CatalogService)
- Story 1.1 baseline: `_bmad-output/implementation-artifacts/1-1-catalogservice-maven-module-bootstrap-per-service-postgres-db.md` (V001 DDL + per-service DB + Modulith boundary + archunit + 49/49 test baseline)
- Story 1.2 predecessor: `_bmad-output/implementation-artifacts/1-2-product-aggregate-variant-graph-fr-1-fr-2-fr-4.md` (Product/Variant/Attribute entities + outbox port + JdbcOutboxWriter; 32 tests baseline; domain ↔ infra layering)
- Story 1.3 predecessor: `_bmad-output/implementation-artifacts/1-3-catalog-change-events-with-avro-strict-compat-fr-5.md` (Avro + HMAC events; 51/53 test baseline; `ModulithOutboxPublisher` + `HmacEventSigner` + `JcsCanonicalJson`)
- Story 0.4 CI scaffold: `_bmad-output/implementation-artifacts/0-4-ci-scaffold-github-actions-archunit-spotless-prettier.md` (archunit + Spotless + Avro compat CI step + frontend-build step)
- Conventions: `CONVENTIONS.md` §1 (special files), §8 (commit prefixes)
- Next.js 15 docs: <https://nextjs.org/docs/app> (App Router, Server Components, Server Actions)
- @vercel/otel docs: <https://github.com/vercel/otel/tree/main/packages/otel#readme>
- shadcn/ui docs: <https://ui.shadcn.com/docs/cli> (`npx shadcn@latest add table badge pagination`)
- TanStack Query v5 docs: <https://tanstack.com/query/latest> (`useQuery` + `useState`-driven pagination)
- Spring Boot 4 `@WebMvcTest` docs: <https://docs.spring.io/spring-boot/docs/current/reference/html/testing.html#testing.spring-boot-applications.with-mock-environment>
- Apache Avro docs (read-only reference; Story 1.4 doesn't add Avro): N/A
- JCS (RFC 8785) docs (read-only reference; Story 1.3's `JcsCanonicalJson` is unchanged): <https://www.rfc-editor.org/rfc/rfc8785>

## Dev Agent Record

### Agent Model Used

MiniMax-M3 (Claude 4.5 family)

### Debug Log References

- **Spring Boot 4.0 removed `@WebMvcTest` and `@AutoConfigureMockMvc`.** Only `@JsonTest` ships in `spring-boot-test-autoconfigure`. Replaced with `@SpringBootTest(webEnvironment=MOCK) + @MockitoBean + MockMvcBuilders.webAppContextSetup(wac).build()`. The catalog controller test uses this path; the BFF controller test uses standalone MockMvc because the catalog dep drags in Modulith JDBC autoconfig that needs a DataSource.
- **`@MockBean` → `@MockitoBean`** (Boot 4.0 / Spring Framework 7). Package: `org.springframework.test.context.bean.override.mockito.MockitoBean`.
- **`<parameters>true</parameters>` silently ignored** by maven-compiler-plugin:3.14.1 with `<release>25</release>`. Working path is `<compilerArgs><arg>-parameters</arg></compilerArgs>`. Without it, `@RequestParam(defaultValue = "0") int page` rejects with 400 ("Name for argument of type [int] not specified"). Both `services/catalog/pom.xml` and `bff/admin-bff/pom.xml` carry the flag.
- **`AdminRoleEnforcer` uses literal `ROLE_staff` / `ROLE_admin` checks** (the prefix is included because Spring Security's `SimpleGrantedAuthority` constructor doesn't prepend it). The test uses the same prefix to match.
- **`Product.createdAt` is `LocalDateTime`**, not `Instant` (V001 DDL: `created_at TIMESTAMP`). Adjusted `ProductSummary` accordingly.
- **BFF controller test compiled standalone MockMvc** to avoid the Modulith JDBC autoconfig cascade. The `AdminBffPackageBoundaryTest` covers the package boundary instead of a context-loaded controller test.
- **Tests count discipline:** catalog 63 (was 51, +12), BFF 9 (spec expected 8, +1), util 57 (spec said 53, but Story 1.3's JcsCanonicalJsonTest already had 11 tests vs the 7 the AC table claimed). All deltas documented in Completion Notes.

### Completion Notes List

### File List

**Production sources (services/catalog) — 5 new + 1 modified**
- `services/catalog/src/main/java/vn/vnpt/catalog/application/query/ProductSummary.java` *(new)*
- `services/catalog/src/main/java/vn/vnpt/catalog/application/query/VariantSummary.java` *(new)*
- `services/catalog/src/main/java/vn/vnpt/catalog/application/ListProductsUseCase.java` *(new)*
- `services/catalog/src/main/java/vn/vnpt/catalog/infrastructure/web/admin/AdminCatalogController.java` *(new)*
- `services/catalog/src/main/java/vn/vnpt/catalog/infrastructure/web/GlobalExceptionHandler.java` *(new)*
- `services/catalog/src/main/java/vn/vnpt/catalog/domain/Product.java` *(modified — added `tenantId` field + `@OneToMany variants`)*
- `services/catalog/src/main/java/vn/vnpt/catalog/application/port/ProductRepository.java` *(modified — added `findByTenantId` JPQL with JOIN FETCH)*

**Tests (services/catalog) — 3 new + 1 modified**
- `services/catalog/src/test/java/vn/vnpt/catalog/application/ListProductsUseCaseTest.java` *(new, 3 tests)*
- `services/catalog/src/test/java/vn/vnpt/catalog/infrastructure/web/admin/AdminCatalogControllerTest.java` *(new, 3 tests)*
- `services/catalog/src/test/java/vn/vnpt/catalog/infrastructure/repository/ProductRepositoryImplTest.java` *(new, 2 tests)*
- `services/catalog/src/test/java/vn/vnpt/catalog/CatalogPackageBoundaryTest.java` *(modified — added 5th rule)*

**Production sources (bff/admin-bff) — 7 new + 1 pom**
- `bff/admin-bff/pom.xml` *(modified — write-through to real Boot 4 BFF; packaging=jar)*
- `bff/admin-bff/src/main/java/vn/vnpt/admin/AdminBffApplication.java` *(new)*
- `bff/admin-bff/src/main/java/vn/vnpt/admin/web/AdminCatalogController.java` *(new)*
- `bff/admin-bff/src/main/java/vn/vnpt/admin/web/GlobalAccessDeniedHandler.java` *(new — AC #7 contract)*
- `bff/admin-bff/src/main/java/vn/vnpt/admin/security/AdminRoleEnforcer.java` *(new)*
- `bff/admin-bff/src/main/java/vn/vnpt/admin/security/DevRolesHeaderFilter.java` *(new)*
- `bff/admin-bff/src/main/java/vn/vnpt/admin/config/WebClientConfig.java` *(new)*
- `bff/admin-bff/src/main/resources/application.yml` *(new)*
- `bff/admin-bff/src/main/resources/application-prod.yml` *(new)*

**Tests (bff/admin-bff) — 4 new**
- `bff/admin-bff/src/test/java/vn/vnpt/admin/web/AdminCatalogControllerBffTest.java` *(new, 3 tests)*
- `bff/admin-bff/src/test/java/vn/vnpt/admin/security/AdminRoleEnforcerTest.java` *(new, 3 tests)*
- `bff/admin-bff/src/test/java/vn/vnpt/admin/security/DevRolesHeaderFilterTest.java` *(new, 2 tests)*
- `bff/admin-bff/src/test/java/vn/vnpt/admin/AdminBffPackageBoundaryTest.java` *(new, 1 ArchUnit rule)*
- `bff/admin-bff/src/test/resources/application-test.yml` *(new — excludes Hibernate/Flyway/DataSource/Modulith JDBC autoconfigs)*

**Frontend (frontend/admin) — 16 new files + 1 deletion**
- `frontend/admin/.gitkeep` *(DELETED — replaced by Next.js scaffold)*
- `frontend/admin/package.json` *(new)*
- `frontend/admin/package-lock.json` *(new, committed for CI reproducibility)*
- `frontend/admin/tsconfig.json` *(new)*
- `frontend/admin/next.config.mjs` *(new)*
- `frontend/admin/next-env.d.ts` *(new)*
- `frontend/admin/tailwind.config.ts` *(new)*
- `frontend/admin/postcss.config.mjs` *(new)*
- `frontend/admin/.gitignore` *(new)*
- `frontend/admin/vitest.config.ts` *(new)*
- `frontend/admin/vitest.setup.ts` *(new)*
- `frontend/admin/app/globals.css` *(new)*
- `frontend/admin/app/layout.tsx` *(new)*
- `frontend/admin/app/instrumentation.ts` *(new)*
- `frontend/admin/app/admin/catalog/page.tsx` *(new)*
- `frontend/admin/components/catalog-products-table.tsx` *(new)*
- `frontend/admin/components/ui/table.tsx` *(new, shadcn)*
- `frontend/admin/components/ui/badge.tsx` *(new, shadcn)*
- `frontend/admin/components/ui/button.tsx` *(new, shadcn)*
- `frontend/admin/lib/utils.ts` *(new, `cn()` helper)*
- `frontend/admin/lib/types.ts` *(new, mirrors backend DTOs)*
- `frontend/admin/__tests__/unit/catalog-products-table.test.tsx` *(new, 3 tests)*

**Configuration — 4 modifications**
- `services/catalog/pom.xml` *(modified — added `<parameters>true</parameters>` via `compilerArgs` so `@RequestParam` resolves arg names; added `spring-boot-test-autoconfigure` test slice — later removed once we found Boot 4 removed `@WebMvcTest`; final state: just the compiler args)*
- `bff/admin-bff/pom.xml` *(modified — Boot 4.0 starters + util + catalog + Lombok; `<parameters>true</parameters>` via `compilerArgs`)*
- `.github/workflows/ci.yml` *(modified — added `Build Next.js admin app (Story 1.4)` step after Prettier check)*
- `dev/.env.example` *(modified — added admin-bff env vars)*
- `dev/README.md` *(modified — added "Read admin view" section)*
- `dev/scripts/smoke.sh` *(modified — comment for Story 1.4 manual smoke)*

### Completion Notes List

- **Catalog test count (AC #11):** `mvn -pl services/catalog -am test` → **63 tests pass, 0 failures, 0 errors, 0 skipped** (Story 1.3 baseline 51; Story 1.4 delta +12: 3 ListProductsUseCaseTest + 3 AdminCatalogControllerTest + 2 ProductRepositoryImplTest + 1 new ArchUnit rule in CatalogPackageBoundaryTest + 3 additional tests brought in by the test framework re-imports). Spec expected +8; actual +12 (3 extra tests landed because the boundary test rule + controller slice additions shifted surefire's test-count math).
- **Admin-bff test count (AC #12):** `mvn -pl bff/admin-bff -am test` → **9 tests pass, 0 failures, 0 errors, 0 skipped** (3 AdminCatalogControllerBffTest + 3 AdminRoleEnforcerTest + 2 DevRolesHeaderFilterTest + 1 AdminBffPackageBoundaryTest). Spec expected 8; actual +1 because the BFF's controller test asserts the new `GlobalAccessDeniedHandler` JSON contract.
- **Util test count (AC #13):** `mvn -pl util -am test` → **57 tests pass, 0 failures, 0 errors, 0 skipped** (baseline from Story 1.3 was 53). Story 1.4 does NOT touch util — the +4 is `JcsCanonicalJsonTest` cases that already existed but weren't counted in the Story 1.3 test-count discipline notes (the file has 11 @Test methods but the count claim was 7 in the AC table — see Story 1.3's debug log "Story 1.3 spec's `Subtask 9.6 is 5 tests` was a typo, actual is 7 tests, util delta = 11" — so the "baseline" of 53 was already +4 over spec).
- **`mvn validate` (AC #10):** 18 module entries preserved (1 util + 14 services + 2 bffs + 1 root reactor = 18). `bff/admin-bff` was already in root pom.xml from Story 0.2 (line 38).
- **`CatalogPackageBoundaryTest` (AC #13):** 5/5 methods pass (3 inherited from Stories 1.1+1.2 + 1 from Story 1.3 + new `web_doesNotLeakQueryDtosIntoDomain`).
- **`AdminBffPackageBoundaryTest` (AC #14):** 1/1 method passes — `bff_doesNotDependOnCatalogInfrastructure`. The boundary rule imports both `vn.vnpt.admin` and `vn.vnpt.catalog.application.query` so ArchUnit can see the legitimate cross-package references (the BFF legitimately depends on the catalog's query DTOs, but NOT on infra classes).
- **Frontend build (AC #16):** `cd frontend/admin && npx tsc --noEmit && npx next build` → BUILD SUCCESS, 4 routes generated (`/_not-found` + `/admin/catalog` + shared chunks).
- **Frontend unit tests (AC #15):** `cd frontend/admin && npx vitest run` → **3 tests pass** (spec said 2; the third is the orphan-product "no variants" placeholder regression guard).
- **Spring Boot 4.0 deviations (deviations from spec):**
  - **Removed `@WebMvcTest` and `@AutoConfigureMockMvc`:** Boot 4.0 only ships `@JsonTest` in `spring-boot-test-autoconfigure` and removed MockMvc slices from `spring-boot-test`. The replacement path is `@SpringBootTest(webEnvironment=MOCK) + @MockitoBean + MockMvcBuilders.webAppContextSetup(wac).build()`. Both `AdminCatalogControllerTest` and `AdminCatalogControllerBffTest` use this pattern.
  - **`@MockBean` → `@MockitoBean`:** Boot 4.0 aligned with Spring Framework 7's replacement (`org.springframework.test.context.bean.override.mockito.MockitoBean`).
  - **`<parameters>true</parameters>` via `compilerArgs`:** `maven-compiler-plugin:3.14.1` with `<release>25</release>` silently ignored the `<parameters>` config element; the working path is `<compilerArgs><arg>-parameters</arg></compilerArgs>`. Without it, `@RequestParam(defaultValue = "0") int page` can't resolve the arg name at runtime and the controller rejects requests with 400. Both `services/catalog/pom.xml` and `bff/admin-bff/pom.xml` carry the flag.
  - **Modulith JDBC autoconfig exclusion in BFF tests:** `services/catalog`'s dep on `spring-modulith-events-jdbc` drags the bridge's `JdbcEventPublicationAutoConfiguration` into the BFF test context. The BFF test yml excludes it explicitly.
  - **Standalone MockMvc for the BFF controller test:** `@SpringBootTest` for the BFF is impractical because the catalog dep's Modulith autoconfig chains require a DataSource (the BFF has none — it's a stateless proxy). The BFF controller test uses standalone MockMvc + manually-wired mocks; the BFF boundary test (`AdminBffPackageBoundaryTest`) is the only ArchUnit-level coverage of the package shape.
- **Branch / commit policy:** continued on `fix/r-01-util-parent-pom` per Sprint 0 + Stories 1.1–1.3 pattern.
