---
baseline_commit: 454c85205539af42207a1194b3cb2b7cf3aa230b
---

# Story 0.2: Bootstrap multi-module Maven monorepo

Status: review

<!-- Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a backend engineer,
I want the project tree per architecture §"Project Structure & Boundaries",
so that each Sprint can scaffold a service module into the established layout.

## Acceptance Criteria

1. **Given** the canonical tree in `_bmad-output/planning-artifacts/architecture.md` §"Project Structure & Boundaries" (lines 693–859),
2. **When** I scaffold the directories and root `pom.xml`,
3. **Then** `services/<name>/` exists for each of the 14 service modules listed in §"Service Boundaries (intra-Modulith)" (catalog, inventory, cart, checkout, payment, order, fulfillment, returns, customer, search, notification, admin, pricing, invoice), each with a placeholder `pom.xml`.
4. **And** `bff/storefront-bff/` and `bff/admin-bff/` exist as Maven modules (placeholder `pom.xml` each).
5. **And** `frontend/storefront/`, `frontend/admin/`, `frontend/packages/` exist as empty directories (Next.js 15 — not Maven; no `pom.xml`).
6. **And** `platform/{observability,policies/opa,chaos/chaos-mesh,runbooks,ci-cd}`, `dev/`, `helm/`, `docs/{adr,diagrams,runbooks,slos,tutorials}` exist as directories (no `pom.xml`).
7. **And** root `pom.xml` is a parent pom (`<packaging>pom</packaging>`) with `<modules>` listing every Java module: `util` plus the 14 services plus the 2 BFF surfaces.
8. **And** root pom uses `<groupId>vn.vnpt</groupId>` per `CONVENTIONS.md` §2 "Java modules (Maven)" (current `org.example` placeholder is replaced — minimal one-line change in addition to `<modules>`).
9. **And** `util/pom.xml` keeps its inline `<dependencyManagement>` for Spring Boot `4.0.0` + Spring Cloud `2025.1.0` BOMs untouched (Story 0.1 fix preserved). Root pom does **not** re-import these BOMs — services will inherit them transitively from `util` per architecture-detail §"Detail: ADR-01" line 97.
10. **And** `mvn -pl util -am clean install -DskipTests` from project root succeeds (exit 0, BUILD SUCCESS), proving the reactor wiring is correct. The `-am` flag is now meaningful (was a no-op in Story 0.1).
11. **And** `mvn test` from project root (or `-pl util -am test`) runs `util`'s 17/17 tests with no regression.
12. **And** `util/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (Spring Boot 4 autoconfig contract) is unchanged.

## Tasks / Subtasks

- [x] Task 1: Convert root `pom.xml` into a parent pom (AC: 7, 8, 9)
  - [x] Subtask 1.1: Change `<groupId>` from `org.example` to `vn.vnpt`. Keep `<artifactId>side-project</artifactId>` and `<version>1.0-SNAPSHOT</version>` (existing commit history references these names; minimal diff wins per Story 0.1's lesson).
  - [x] Subtask 1.2: Set `<packaging>pom</packaging>`.
  - [x] Subtask 1.3: Add `<modules>` listing: `util`, the 14 service names (`catalog`, `inventory`, `cart`, `checkout`, `payment`, `order`, `fulfillment`, `returns`, `customer`, `search`, `notification`, `admin`, `pricing`, `invoice`), plus the 2 BFF surfaces (`bff/storefront-bff`, `bff/admin-bff`). Order them roughly by Epic order so the reactor makes pedagogical sense (catalog first, invoice last).
  - [x] Subtask 1.4: Add `<properties>` for `java.version=25`, `project.build.sourceEncoding=UTF-8`, `maven.compiler.source=25`, `maven.compiler.target=25`. Do **not** re-import the Boot/Cloud BOMs here — `util`'s `<dependencyManagement>` is the single source per architecture-detail.
  - [x] Subtask 1.5: Add `<pluginManagement>` with `maven-compiler-plugin:3.14.1` and `spring-boot-maven-plugin:4.0.0` versions (the values the Boot 4 BOM pins; matches Story 0.1's follow-up commit F9).
- [x] Task 2: Create `services/<name>/` Maven modules (AC: 3, 7)
  - [x] Subtask 2.1: For each of the 14 service names, `mkdir -p services/<name>/` and create a 12-line `pom.xml`: parent = root, groupId = `vn.vnpt`, artifactId = `<name>`, packaging = `pom` (placeholder until Epic 1+ adds code, at which point it flips to `jar`). No dependencies, no plugins — purely a reactor participant.
  - [x] Subtask 2.2: Add a `README.md` stub (3 lines) to each service dir stating the bounded context and the FRs it owns (copy from architecture §"Service Boundaries" table, lines 900–915).
- [x] Task 3: Create BFF Maven modules (AC: 4, 7)
  - [x] Subtask 3.1: `mkdir -p bff/storefront-bff/{src/main/java/vn/vnpt/bff/storefront/{api,client,compositIon,config},src/main/resources}` — directory tree per architecture line 758–763 (note: `compositIon` is the original spelling in the architecture — preserve it; do not "fix" to `composition`).
  - [x] Subtask 3.2: `mkdir -p bff/admin-bff/{src/main/java/vn/vnpt/bff/admin/{api,client,compositIon,config},src/main/resources}`.
  - [x] Subtask 3.3: Placeholder `pom.xml` for each BFF (same 12-line template as services; artifactId `storefront-bff` and `admin-bff` respectively).
  - [x] Subtask 3.4: Placeholder `README.md` per BFF.
- [x] Task 4: Create non-Java directories (AC: 5, 6)
  - [x] Subtask 4.1: Frontend (Next.js 15 / TypeScript — no Maven): `mkdir -p frontend/storefront frontend/admin frontend/packages/{ui,types,eslint-config}`. Do **not** create `package.json`, `tsconfig.json`, etc. — those arrive with their own stories (storefront app lands with Epic 2's checkout flow; admin with Epic 5/8). Empty dirs are fine for this story.
  - [x] Subtask 4.2: Platform: `mkdir -p platform/observability/{otel-config,grafana-dashboards,prometheus-rules,loki-schemas,tempo-config} platform/policies/opa platform/chaos/chaos-mesh platform/runbooks platform/ci-cd/.github/workflows platform/ci-cd/argocd`.
  - [x] Subtask 4.3: Dev (local-only): `mkdir -p dev/seed-data platform/ci-cd/argocd`. The docker-compose file itself lands in Story 0.3.
  - [x] Subtask 4.4: Helm: `mkdir -p helm/<name>` for the 14 service names (parallel to `services/`).
  - [x] Subtask 4.5: Docs: `mkdir -p docs/adr docs/diagrams docs/runbooks docs/slos docs/tutorials`. Add `docs/adr/0001-record-architecture-decisions.md` with the standard "Status: Accepted" stub (10 lines) so the ADR folder is not literally empty.
- [x] Task 5: Verify the reactor builds (AC: 10, 11, 12)
  - [x] Subtask 5.1: From project root: `mvn -pl util -am clean install -DskipTests`. Confirm `BUILD SUCCESS` and exit 0. The `-am` flag now correctly resolves `util`'s parent (root) before building `util`.
  - [x] Subtask 5.2: From project root: `mvn test` (or `mvn -pl util -am test`). Confirm `ExcelImportExportHelperTest` (15/15) and `UtilsAutoConfigurationMetadataTest` (2/2) pass — total 17/17, matching Story 0.1's verified count. Zero regressions.
  - [x] Subtask 5.3: Confirm `util/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` byte-for-byte unchanged vs Story 0.1.
  - [x] Subtask 5.4: Run `mvn validate` from root to confirm every `<module>` path resolves (all 17 directories — util, 14 services, 2 BFFs — must exist). `validate` will fail if any module path is missing.
- [x] Task 6: Commit on the current branch and open PR (AC: all)
  - [x] Subtask 6.1: Stay on `fix/r-01-util-parent-pom` if it's still the working branch (Story 0.2 is a continuation of the Sprint 0 bootstrap). Per `CONVENTIONS.md`, prefix commits with `chore(monorepo): ...` or `feat(monorepo): ...`.
  - [x] Subtask 6.2: Stage `pom.xml`, all `services/<name>/pom.xml`, both BFF poms, all new directories.
  - [x] Subtask 6.3: Commit message: `feat(monorepo): bootstrap multi-module Maven reactor (Story 0.2)`. Reference architecture §"Project Structure & Boundaries", the risk register R-01 closure, and Story 0.1 (R-01 fix unblocked this).
  - [x] Subtask 6.4: Push branch and open PR. **Push requires GitHub credentials** — if `git push` returns `fatal: could not read Username`, surface that and ask the user to push themselves.

## Dev Notes

### Architecture intent — the tree you are materializing

The AC says "scaffold the monorepo per `local-docs/09`" — but `local-docs/09-project-structure.md` is a **generic e-commerce reference template** (uses `payment-service`, `tax-service`, `delivery-service` etc.). The **authoritative tree for this project is `_bmad-output/planning-artifacts/architecture.md` §"Project Structure & Boundaries"** (lines 693–859), which uses the 14-service list bound to FR coverage. If `local-docs/09` and `architecture.md` disagree, **follow architecture.md** — it's the binding document. The local-docs/09 template is preserved for context but is not the target.

### Current state of the working tree

- Root `pom.xml` is a **placeholder**: `<groupId>org.example</groupId>`, `<artifactId>side-project</artifactId>`, `<version>1.0-SNAPSHOT</version>`, no `<packaging>`, no `<modules>`, no `<dependencyManagement>`. It came from Maven's default archetype and needs conversion.
- `util/pom.xml` is **already fixed** (Story 0.1 / commit `cac5441`): no `<parent>`, inline `<dependencyManagement>` for both BOMs, Java 25, explicit dep versions. **Do not modify it.** The Story 0.1 review's follow-up commits (F9/F10/F6) pinned `maven-compiler-plugin:3.14.1` and `spring-boot-maven-plugin:4.0.0` to eliminate Maven warnings — preserve those pins.
- `util/src/main/java/vn/vnpt/util/` exists with ~95 Java files (per architecture line 712) — all auto-built into the jar when the reactor runs.
- No other Maven module directories exist yet. Everything else is to be scaffolded.

### Architecture guardrails — what MUST be preserved

- **GroupId:** root pom must be `vn.vnpt` per `CONVENTIONS.md` §2 "Java modules (Maven)" (line 76). The current `org.example` is a placeholder; replacing it is in-scope for this story. Service/BFF modules inherit `vn.vnpt` from the root via `<parent>` — no need to repeat `<groupId>` in each child pom (Maven inheritance handles it).
- **Java 25 LTS** — root pom `<java.version>25</java.version>` plus `<maven.compiler.source>25</maven.compiler.source>` / `<target>25</target>` for IDEs that ignore `<release>`. Story 0.1 dropped `<maven.compiler.source>`/`<target>` from `util/pom.xml` once `<release>25</release>` was authoritative; root pom can keep both for older IDE support since it's not yet under a `<release>` constraint.
- **BOM single source of truth:** Spring Boot `4.0.0` + Spring Cloud `2025.1.0` BOMs live in `util/pom.xml` `<dependencyManagement>`. Root pom does **not** re-import them — per `architecture-detail.md` §"Detail: ADR-01" line 97: "every services/<name>/ module that imports util will transitively inherit both BOMs through util's dependencyManagement. Service-specific poms should NOT re-import these BOMs — duplication risks version skew." This avoids the R-09 Boot 4 ecosystem library-lag risk by keeping one version pin.
- **Plugin pin:** root `<pluginManagement>` must pin `maven-compiler-plugin:3.14.1` and `spring-boot-maven-plugin:4.0.0`. Without these, Maven emits the `'build.plugins.plugin.version' is missing` warning Story 0.1 already fixed in `util/`. Pin once at root → all child modules inherit cleanly.
- **`<packaging>pom</packaging>` for empty modules:** services and BFFs have no Java code yet (Epics 1+ add it). Use `pom` packaging now; flip to `jar` when code lands. This is standard Maven multi-module bootstrap practice.
- **`util` autoconfig contract:** `util/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` registers `vn.vnpt.util.UtilsAutoConfiguration`. This is the Spring Boot 4 autoconfig mechanism — leave it alone. Story 0.1 added `UtilsAutoConfigurationMetadataTest.java` as a regression guard; this story must not break that test (AC #11).

### Architecture guardrails — what MUST NOT be touched

- **`util/pom.xml`** — Story 0.1's R-01 fix is final. If a Maven warning appears after this story lands, fix it in `<pluginManagement>` at the root, not in `util/`.
- **Any `src/main/java/org/example/Main.java`** — placeholder from the Maven archetype; out of scope. Don't delete it (it would inflate the diff); don't upgrade it. It compiles and tests don't run it.
- **`<dependencyManagement>` contents** — do not pre-populate service poms with deps they don't yet need (YAGNI per ponytail). Each Epic's first story adds the deps it actually uses.
- **CI / GitHub Actions / docker-compose / Snowflake strict mode / ArchUnit / Spotless** — Stories 0.3, 0.4, 0.5. Out of scope. Do not add `.github/workflows/ci.yml` files; do not pre-create `dev/docker-compose.yml`.
- **`docs/adr/` content** — only `0001-record-architecture-decisions.md` (stub) is in scope. Other ADRs (0002–0006 in `local-docs/09`) arrive with the architecture phase's documentation sweep, not this story.

### Source tree components to touch

| File / Dir | Action | Why |
|---|---|---|
| `pom.xml` | Edit (root parent pom conversion) | AC #7, #8 |
| `services/<name>/pom.xml` ×14 | Create (placeholder, 12 lines each) | AC #3, #7 |
| `services/<name>/README.md` ×14 | Create (3-line stub) | Project context |
| `bff/storefront-bff/pom.xml` | Create | AC #4, #7 |
| `bff/admin-bff/pom.xml` | Create | AC #4, #7 |
| `bff/<surface>-bff/src/main/java/vn/vnpt/bff/<surface>/{api,client,compositIon,config}/` | Create (empty dirs) | Project context |
| `bff/<surface>-bff/README.md` ×2 | Create | Project context |
| `frontend/{storefront,admin,packages/{ui,types,eslint-config}}/` | Create (empty dirs) | AC #5 |
| `platform/{observability,policies/opa,chaos/chaos-mesh,runbooks,ci-cd}/` | Create (empty dirs, sub-dirs as listed in Task 4.2) | AC #6 |
| `dev/seed-data/` | Create (empty dir) | AC #6 |
| `helm/<name>/` ×14 | Create (empty dirs) | AC #6 |
| `docs/{adr,diagrams,runbooks,slos,tutorials}/` | Create | AC #6 |
| `docs/adr/0001-record-architecture-decisions.md` | Create (10-line stub) | Project context |

**Do not touch** anything outside this table. Do not modify `util/pom.xml`, `util/src/**`, `local-docs/**`, `.gitnexus/**`, `src/main/java/org/example/Main.java`, or any existing config.

### Project Structure Notes

- **Alignment with unified project structure:** root `pom.xml` becomes the Maven reactor parent per `local-docs/09` (generic template) and `architecture.md` lines 700–704 (this project's authoritative structure). The two agree on the parent-pom concept; they differ on service names. **Follow `architecture.md`.**
- **Detected conflict with `local-docs/09`:** that template uses `payment-service`, `tax-service`, `delivery-service`, `notification-service` (kebab-case hyphens). Architecture.md uses `payment`, `invoice` (single tokens). This is a deliberate rename to align with `CONVENTIONS.md` §2 "kebab-case directory names" (line 74) — the architecture's names are the kebab-cased single tokens. **No action needed** — just be aware.
- **Detected conflict with `CONVENTIONS.md` §2 "Java modules (Maven)":** current root pom uses `<groupId>org.example</groupId>`. The convention says service modules use `vn.vnpt`. **Fix this in scope** — convert the root pom to `vn.vnpt:side-project:1.0-SNAPSHOT`. Service/BFF poms inherit `vn.vnpt` from the root via `<parent>` — explicit `<groupId>` in children is **not** required (Maven inherits). Writing it explicitly is acceptable but redundant.
- **R-01 closure:** Story 0.1 inlined the BOMs in `util/pom.xml` as a one-file fix. This story makes `util` a module of a real parent reactor — but does **not** move the BOMs up. The "may move these imports up" phrasing in architecture-detail line 96 is **not** a directive; the explicit instruction in line 97 ("Service-specific poms should NOT re-import these BOMs") takes precedence. Keep BOMs in `util/`.

### Library vs application distinction

`util/` is a **library** (`<packaging>jar</packaging>`, `spring-boot-maven-plugin` configured with `<skip>true</skip>`). The 14 services and 2 BFFs will be **runnable Spring Boot apps** when their Epic stories land. For now, all of them use `<packaging>pom</packaging>` so the reactor resolves cleanly without producing jars.

The root pom is the **reactor parent** — `<packaging>pom</packaging>`, never `jar`/`war`. This is the Maven default for multi-module parents.

### Build command reference

The canonical verification (from `_bmad-output/DEVOPS-RUNBOOK.md` line 45, `_bmad-output/LOCAL-DEV-SETUP-CHECKLIST.md` line 104, `_bmad-output/SPRINT-0-ONBOARDING.md` line 141):

```bash
# from project root
mvn -pl util -am clean install -DskipTests
# expected: BUILD SUCCESS, exit 0
# Note: `-am` is now meaningful (it pulls util's parent = root pom, which itself
#       has no upstream modules to build, so it's effectively a no-op for now,
#       but the command matches every downstream service's pattern).

# run existing tests
mvn -pl util -am test
# expected: 17/17 tests pass (15/15 ExcelImportExportHelperTest + 2/2 UtilsAutoConfigurationMetadataTest)

# full reactor sanity check
mvn validate
# expected: BUILD SUCCESS — proves all 17 <module> paths resolve
```

The repo still has no `mvnw` wrapper (Story 0.1 documented this). Continue using `mvn` until a later story adds `./mvnw`. Do not introduce `./mvnw` in this story — YAGNI.

### Testing standards summary

- **No new tests required.** The story adds zero Java code; only pom.xml and directory scaffolding. Behavior is unchanged.
- **Required regression check (AC #11):** `mvn -pl util -am test` must still pass 17/17. If the count drops, the root-pom conversion broke something in `util/` (e.g., a plugin override that's now resolved differently). Investigate and fix.
- **No ArchUnit / no Testcontainers / no Spotless** in this story — those arrive in Story 0.4 (CI scaffold).
- **Per `CONVENTIONS.md`:** PR checklist item "Unit tests pass (`mvn -pl <module> test`)" applies — run it before pushing.

### Branch / commit policy

- **Current branch:** `fix/r-01-util-parent-pom` (carried from Story 0.1). Stay on it. Don't create a new branch.
- **Commit prefix:** `feat(monorepo): ...` per `CONVENTIONS.md` §6 (multi-module bootstrap is a feature, not a fix).
- **Commit granularity:** prefer a single commit covering all 17 module poms + the root pom conversion. Story 0.1's lesson: one commit per story keeps the history scannable; splitting 17 commits for mechanical directory creation adds noise without value. The follow-up commit (if needed for review fixes) can be a separate `chore(monorepo): ...` commit.
- **Push policy:** same as Story 0.1 — if `git push` returns `fatal: could not read Username for 'https://github.com': Device not configured`, surface it and ask the user to push themselves.

### Risk and predecessor notes

- **R-01 closure:** Story 0.1 fixed the broken `util/pom.xml` parent reference. This story extends the fix by giving `util/` a real reactor parent (the root `pom.xml`). Both together constitute the R-01 mitigation per `addendum.md` A1 row 1: "Vendor parent pom OR inline dependencyManagement (see INT-01 root cause). Owner: Build Eng."
- **R-09 mitigation (Boot 4 ecosystem immaturity):** by keeping the BOMs in `util/pom.xml` and preventing root pom from re-importing, this story reinforces the version-pinning strategy from Story 0.1's follow-up commits. Any future service module that inherits from root automatically gets the same `spring-boot-dependencies:4.0.0` and `spring-cloud-dependencies:2025.1.0` versions via transitive `dependencyManagement`.
- **Predecessor:** Story 0.1 (`0-1-fix-util-parent-pom-blocker-r-01`) — done. Reuse the patterns established there: minimal diff, root pom stays the canonical source of `<java.version>`, dependency versions pinned exactly (no ranges, no `4.x` floating, no `-SNAPSHOT`).
- **Successor:** Story 0.3 will create `dev/docker-compose.yml`; Story 0.4 will create `.github/workflows/ci.yml`; Story 0.5 will modify `util/SnowflakeIdGenerator.java`. None of those depend on this story's exact directory layout — they only need the directories to exist. This story's scope is **scaffolding, not behavior**.

### Previous story intelligence (Story 0.1 — relevant carry-overs)

- **Plugin versions pin (F9):** `maven-compiler-plugin:3.14.1`, `spring-boot-maven-plugin:4.0.0`. Apply in root pom `<pluginManagement>` so future modules don't reintroduce the Maven warning.
- **BOM source-of-truth rule:** `util/pom.xml` imports both BOMs; downstream services inherit via `util`. Root pom does **not** re-import. Story 0.1's Debug Log explicitly noted this design intent.
- **Build command deviation:** no `mvnw` wrapper exists yet; use `mvn` from root. After this story, `mvn -pl util -am` will resolve correctly because the root pom now declares `util` as a module.
- **JDK deviation tolerated:** only OpenJDK 26.0.1 is locally installed; pom targets `--release 25`. JDK 26 compiles to `--release 25` bytecode successfully. No flag overrides needed.
- **Test count discipline:** Story 0.1's review found a `15/15` vs `17/17` documentation drift — be precise in this story's Completion Notes. If `mvn -pl util -am test` returns `Tests run: 17, Failures: 0, Errors: 0, Skipped: 0`, write `17/17` exactly.

### References

- Story source (epics): `_bmad-output/planning-artifacts/epics.md` §"Epic 0 > Story 0.2" (lines 391–402)
- Project tree (authoritative): `_bmad-output/planning-artifacts/architecture.md` §"Project Structure & Boundaries" (lines 693–859), §"Service Boundaries (intra-Modulith)" (lines 878–915)
- BOM pin policy (binding): `_bmad-output/planning-artifacts/architecture-detail.md` §"Detail: ADR-01" lines 88–97
- Project tree (template, lower authority): `local-docs/09-project-structure.md`
- Risk register: `_bmad-output/planning-artifacts/addendum.md` §A1 row R-01 (closure), §A4 R-09 (Boot 4 ecosystem)
- Conventions: `_bmad-output/CONVENTIONS.md` §2 "Java modules (Maven)", §6 commit prefixes
- Predecessor story: `_bmad-output/implementation-artifacts/0-1-fix-util-parent-pom-blocker-r-01.md` (carry-over patterns: plugin pin, BOM source-of-truth, build command)
- Build command refs: `_bmad-output/DEVOPS-RUNBOOK.md` line 45; `_bmad-output/LOCAL-DEV-SETUP-CHECKLIST.md` line 104; `_bmad-output/SPRINT-0-ONBOARDING.md` line 141; `_bmad-output/RISK-REGISTER.md` line 35
- Reviewer checklist: `_bmad-output/REVIEWER-GUIDE.md` line 215
- PR checklist: `_bmad-output/CONTRIBUTING.md` lines 120–121, 155

## Dev Agent Record

### Agent Model Used

claude-opus-4-8

### Debug Log References

- `mvn validate` initially failed with `Non-resolvable parent POM` for every service and BFF. Cause: child `<parent>` element lacked `<relativePath>`, so Maven's default `../pom.xml` lookup walked one level up (e.g. `services/pom.xml`) and missed the root. Fix: added `<relativePath>../../pom.xml</relativePath>` to each child pom (services sit 2 levels deep, BFFs sit 3 — but `../../pom.xml` works for both because BFF paths resolve to the same `pom.xml` at the root). Re-ran `mvn validate` → BUILD SUCCESS.

### Completion Notes List

- Root `pom.xml` converted: groupId `org.example` → `vn.vnpt`; `<packaging>pom</packaging>`; `<modules>` lists 17 entries (util + 14 services + 2 BFFs); `<pluginManagement>` pins `maven-compiler-plugin:3.14.1` and `spring-boot-maven-plugin:4.0.0`. BOMs intentionally NOT re-imported — `util/pom.xml` remains the single source per architecture-detail.md ADR-01 line 97.
- 14 service pom.xml files + README.md stubs created (catalog..invoice).
- 2 BFF pom.xml files + README.md stubs created; BFF Java tree (`api/client/compositIon/config`) preserved with the architecture's original `compositIon` capitalization.
- Frontend, platform, dev, helm, and docs directories scaffolded. Empty subdirs got `.gitkeep` so a clean clone preserves the tree. docs/adr/0001-record-architecture-decisions.md stub added.
- Verification:
  - `mvn validate` → BUILD SUCCESS (all 17 module paths resolve)
  - `mvn -pl util -am clean install -DskipTests` → BUILD SUCCESS, exit 0
  - `mvn -pl util -am test` → 17/17 pass (15/15 ExcelImportExportHelperTest + 2/2 UtilsAutoConfigurationMetadataTest)
  - `util` autoconfig imports file: byte-for-byte unchanged (`git hash 6b79912c0ded051c8a88a35b593615dc5940fe76`)
- Single commit on `fix/r-01-util-parent-pom`: `8a8295a feat(monorepo): bootstrap multi-module Maven reactor (Story 0.2)`. Push + PR open pending GitHub credentials (see Task 6.4).
- R-01 closure: util is now a member of a real reactor, with parent = root pom. Story 0.1's inline-BOM fix in `util/pom.xml` is preserved unchanged; root pom does not re-import.
- R-09 mitigation: any future `services/<name>/pom.xml` will inherit Spring Boot 4.0.0 + Spring Cloud 2025.1.0 BOMs transitively through `util`, eliminating the Boot 4 ecosystem version-skew risk.

### File List

**Modified (2):**
- `pom.xml`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

**Created (52):**
- 14 × `services/<name>/pom.xml` (catalog, inventory, cart, checkout, payment, order, fulfillment, returns, customer, search, notification, admin, pricing, invoice)
- 14 × `services/<name>/README.md` (same names)
- 2 × `bff/storefront-bff/{pom.xml,README.md}`
- 2 × `bff/admin-bff/{pom.xml,README.md}`
- BFF Java tree: `bff/storefront-bff/src/main/{java/vn/vnpt/bff/storefront/{api,client,compositIon,config},resources}` and the equivalent under `bff/admin-bff/`
- `docs/adr/0001-record-architecture-decisions.md`
- `.gitkeep` markers under `frontend/{storefront,admin,packages/{ui,types,eslint-config}}`, `platform/{observability/{otel-config,grafana-dashboards,prometheus-rules,loki-schemas,tempo-config},policies/opa,chaos/chaos-mesh,runbooks,ci-cd/{.github/workflows,argocd}}`, `dev/seed-data/`, `helm/<14 service names>/`, `docs/{diagrams,runbooks,slos,tutorials}/`
- `_bmad-output/implementation-artifacts/0-2-bootstrap-multi-module-maven-monorepo.md` (story status / checkboxes updated)