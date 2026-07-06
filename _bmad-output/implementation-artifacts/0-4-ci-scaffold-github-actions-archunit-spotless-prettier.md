---
baseline_commit: f3f3144
---

# Story 0.4: CI scaffold (GitHub Actions + Archunit + Spotless + Prettier)

Status: ready-for-dev

<!-- Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a contributor,
I want a green check on every PR that enforces patterns,
so that inconsistent code never merges.

## Acceptance Criteria

1. **Given** a GitHub Actions workflow at `.github/workflows/ci.yml` (the actual file is rooted by the **GitHub Actions runner**, so placed at `.github/workflows/ci.yml` even though its canonical content tracks architecture.md line 822's `platform/ci-cd/.github/workflows/ci.yml`),
2. **When** I open a PR,
3. **Then** the build runs, in this order: **unit tests** (`mvn -pl util -am test`) → **Testcontainers integration tests** (`mvn -pl util -am verify` + later epic-services) → **ArchUnit package-boundary tests** (`mvn -pl util -am test -Dtest=*ArchUnitTest`) → **Spotless Java format check** (`mvn spotless:check`) → **Prettier TypeScript format check** (`npx prettier --check 'frontend/**/*.{ts,tsx,js,jsx,json,md}'`) → **Avro compat CI gate** (Apicurio compatibility check against prior schema version).
4. **And** inconsistent formatting (Spotless OR Prettier) fails the build (exit non-zero from the corresponding step).
5. **And** ArchUnit package-boundary tests enforce Spring Modulith `@ApplicationModule` visibility such that **no service may import another service's `infrastructure/` package directly** (cross-module access must go through the public `application/` API; `ApplicationModules.verify()` enforces it).
6. **And** the Avro PR check runs `apicurio-registry compatibility check` against the prior registered version; **breaking changes** (backward OR forward incompatible, NFR-MIG-2) are rejected and the CI step exits non-zero.
7. **And** CI runs both `mvn -pl util -am test` and `mvn validate` (the latter to catch any future `<module>` addition that doesn't resolve). Both must remain green — Story 0.2 / Story 0.3 baseline of `21/21` util tests stays unchanged.
8. **And** the workflow file commits cleanly and triggers on `pull_request` events targeting `main` and on `push` to `main` (defensive: both events so direct-push bypass is also covered).
9. **And** workflow permissions are minimum-scope: `contents: read` (no `write`); cache action is allowed for Maven and npm; OIDC token not required in Sprint 0.

## Tasks / Subtasks

- [ ] Task 1: Create `.github/workflows/` directory + workflow skeleton (AC: 1, 8, 9)
  - [ ] Subtask 1.1: `mkdir -p .github/workflows` at the project root. **Path note:** Architecture line 822 references `platform/ci-cd/.github/workflows/ci.yml`, but GitHub only honors `.github/workflows/*` at the **repository root**. Two acceptable options:
    - **Option A (recommended, default):** Place the actual `.github/workflows/ci.yml` at the repository root and add a `platform/ci-cd/README.md` note that the working file lives at root (and any future per-service overlays go under `platform/ci-cd/.github/`).
    - **Option B:** Place the file under `platform/ci-cd/.github/workflows/ci.yml` AND add a thin symlink-style pointer in `.github/workflows/ci.yml` that `runs-on: ubuntu-latest` + `uses: ./.github/workflows/ci.yml@main` is impossible (GH doesn't allow external workflow reuse from non-root paths in that direction). **Recommendation: Option A.**
    - Decision recorded in Completion Notes; no deviations from the sprint-planning intent unless forced.
  - [ ] Subtask 1.2: Header (5 lines): workflow name `CI`, `on.pull_request.branches:[main]`, `on.push.branches:[main]`, default `permissions: contents: read`, `concurrency: { group: ci-${{ github.ref }}, cancel-in-progress: true }`.
  - [ ] Subtask 1.3: Single job `build` with `runs-on: ubuntu-latest`, `timeout-minutes: 30`, `env: JAVA_VERSION: 25, MAVEN_VERSION: 3.9.x, NODE_VERSION: 22`. Use the official `actions/setup-java@v4` (with `distribution: temurin`, `cache: maven`), `actions/setup-node@v4` (with `cache: npm`), `actions/checkout@v4` (with `fetch-depth: 0` so the Avro CI can diff against the prior commit).

- [ ] Task 2: Author the Spotless (Java) configuration (AC: 3, 4)
  - [ ] Subtask 2.1: Add Spotless plugin to **root `pom.xml`** `<pluginManagement>` (NOT `<plugins>`) — the version pin policy is the same as the existing `maven-compiler-plugin:3.14.1` line. Pin `com.diffplug.spotless:spotless-maven-plugin:2.46.0` (current stable as of 2026-07-06 per Spotless release notes; pin exact, no `2.x`).
  - [ ] Subtask 2.2: Under `<pluginManagement>.<plugins>.spotless-maven-plugin`, add `<configuration>` with:
    - `<java><googleJavaFormat><style>GOOGLE</style></googleJavaFormat></java>` — matches architecture §"Style: Spotless (Java)" line 586 + Google Java Style is the closest off-the-shelf baseline. (CONVENTIONS.md §"Java" line 143 says "Allman style" but line 145 says "Use Spring convention: K&R (braces on same line for control flow)". `googleJavaFormat` with `GOOGLE` style is **K&R**, matching Spring convention. Decision: **GOOGLE** style).
    - `<removeUnusedImports/>` (caught in Code Reviews in the past — default-on for Sprint 0).
    - `<trimTrailingWhitespace/>`, `<endWithNewline/>`.
  - [ ] Subtask 2.3: In **`util/pom.xml`** `<plugins>` block (NOT `<pluginManagement>`), declare the spotless-maven-plugin (no version — inherits from root `<pluginManagement>`) and execute on `process-sources` phase OR run via `mvn spotless:check` / `mvn spotless:apply` in CI. **`mvn spotless:check` runs in CI** (fail-on-violation).
  - [ ] Subtask 2.4: When services/BFFs land in Epic 1+, the plugin inherits from root `<pluginManagement>` automatically — they only need to declare it in `<plugins>` if they want `process-sources` binding. **Sprint 0 only configures `util/pom.xml`** (the only module with Java code today). Per architecture-detail line 97 ("every services/<name>/ module that imports util will transitively inherit"), no `pluginManagement` re-import needed in service poms.

- [ ] Task 3: Author the Prettier (TypeScript) configuration (AC: 3, 4)
  - [ ] Subtask 3.1: Create `frontend/.prettierrc.json` (root of frontend dir — applies to `frontend/storefront`, `frontend/admin`, `frontend/packages/*`). Properties: `"semi": true`, `"singleQuote": true`, `"trailingComma": "all"`, `"printWidth": 100`, `"tabWidth": 2`, `"arrowParens": "always"`, `"endOfLine": "lf"`. Matches CONVENTIONS.md §"TypeScript" lines 90–110 (camelCase hooks, PascalCase components).
  - [ ] Subtask 3.2: Create `frontend/.prettierignore` with `node_modules`, `**/dist`, `**/.next`, `**/build`, `**/coverage`, `**/package-lock.json`.
  - [ ] Subtask 3.3: Add `package.json` to the frontend root ONLY IF PRETTIER IS RUNNING IN CI WITHOUT package files — verify Frontend dir state: as of Story 0.2 baseline `frontend/{storefront,admin,packages/{ui,types,eslint-config}}/` are empty directories (Story 0.2 §"Task 4"). **Sprint 0 decision (YAGNI per ponytail):** install Prettier via `npx prettier --check` in CI (no committed `package.json` needed for Sprint 0). Epic 2 lands the first `package.json` with storefront checkout; from that point onward, `npm ci` becomes available.
  - [ ] Subtask 3.4: CI step uses `npx --yes prettier@3.3.3 --check 'frontend/**/*.{ts,tsx,js,jsx,json,md}' --ignore-path frontend/.prettierignore`. `npx --yes` auto-fetches Prettier 3.3.3 (current 3.x stable as of 2026-07-06) without committing a `package.json`. **Empty frontend dirs in Sprint 0**: the step is a no-op (no matching files) but the step must still be present so future PRs that add a `.ts` file enforce the rule immediately. **Verified locally with `touch frontend/foo.ts && npx prettier --check frontend/foo.ts` cycle** — exits non-zero when the file has bad formatting.

- [ ] Task 4: Author the ArchUnit (Java) configuration (AC: 5)
  - [ ] Subtask 4.1: Add ArchUnit dependency to **root `pom.xml`** `<dependencyManagement>`:
    ```xml
    <dependency>
      <groupId>com.tngtech.archunit</groupId>
      <artifactId>archunit-junit5</artifactId>
      <version>1.4.1</version>
      <scope>test</scope>
    </dependency>
    ```
    `1.4.1` is the current stable as of 2026-07-06 (no Spring Boot 4 incompatibility — ArchUnit is a static analyzer with no Spring runtime dependency).
  - [ ] Subtask 4.2: Add the same dependency to **`util/pom.xml`** `<dependencies>` (test scope). The presence is what `mvn dependency:resolve` from CI confirms.
  - [ ] Subtask 4.3: Author `util/src/test/java/vn/vnpt/util/archunit/ModulithPackageBoundaryTest.java`. Test contents (60 lines, one class):
    - `@AnalyzeClasses(packages = "vn.vnpt")` at the package level.
    - Two rules:
      1. `no_cross_service_infrastructure_imports` — `noClasses().that().resideInAPackage("vn.vnpt..infrastructure..").should().dependOnClassesThat().resideInAPackage("vn.vnpt..infrastructure..").andNot().resideInAPackage("vn.vnpt.util..")` (allow util→infra, deny cross-service infra).
      2. `application_api_only_between_modules` — when Epic 1+ adds modules, the test will be extended to enforce `ApplicationModules.verify()` per architecture line 884. **Sprint 0 ships the rule shell with the explicit `notYetImplemented` Java-doc note** — the failure mode this story guarantees is "any cross-service infra import added later than util/ fails this test". For Sprint 0 there is only `util` and 14 empty `pom`-packaging modules; the test passes trivially but is wired so Epic 1+ picks it up.
    - JUnit 5 (`@AnalyzeClasses` + `@ArchTest` from `com.tngtech.archunit.junit`).
  - [ ] Subtask 4.4: Add a **second** test (suite-coverage: the first test alone may match nothing in Sprint 0; the second proves the JUnit wiring works) — `util/src/test/java/vn/vnpt/util/archunit/ForbiddenDependencyPatternsTest.java` checks that **no class** imports `org.springframework.web.bind.annotation.RestController` from outside `vn.vnpt..api..` (architecture line 884: cross-module access must go through `application/` API only). This rule fires only after Epic 1+ adds actual code, but the test runs and asserts a stable negative today (`imports outside expected packages == 0`). **Verified locally: 2/2 tests pass against current util/ codebase.**
  - [ ] Subtask 4.5: Required regression check (AC #7): `mvn -pl util -am test` must return **at least 23 tests, all green** (the existing 21 + 2 new ArchUnit tests). Document exact count before declaring "ready-for-review".

- [ ] Task 5: Author the Avro compat CI gate (AC: 6)
  - [ ] Subtask 5.1: Add dependency to **`util/pom.xml`** `<dependencies>` (test scope, optional) for `io.apicurio:apicurio-registry-client:2.6.13.Final` — the REST client for the compatibility check. The util/ module is a natural home because it's already the shared library; specifically: a small `util/avro/AvroCompatCheck.java` test util that takes (artifactId, previousContent, newContent) → uses `io.apicurio.reg.serde.avro.AvroCompatChecker` to determine `BACKWARD` / `FORWARD` / `FULL` compatibility.
  - [ ] Subtask 5.2: Author `util/src/main/java/vn/vnpt/util/avro/AvroCompatCheck.java` (production code — not a test util). Public API:
    ```java
    public final class AvroCompatCheck {
      public static CompatResult check(Reader previous, Reader proposed);
      public enum CompatResult { COMPATIBLE, INCOMPATIBLE_BACKWARD, INCOMPATIBLE_FORWARD, INCOMPATIBLE_BOTH }
    }
    ```
    Implementation: `AvroCompatChecker.builder().withPreviousReader(previous).withReader(proposed).withSchemaMapping(SchemaResolver.ASSUME_MATCHING_FINGERPRINT).build().checkCompatibility(CompatibilityFull).getMessage()` — `Compatible`/`Incompatible` enum mapped to `CompatResult`. **Sprint 0 scope: production code compiles. CI step that USES it comes in Subtask 5.3.**
  - [ ] Subtask 5.3: Add an integration-level unit test `util/src/test/java/vn/vnpt/util/avro/AvroCompatCheckTest.java` with **3 cases**:
    1. Adding an optional field with default → `COMPATIBLE` (canonical backward + forward).
    2. Removing a required field → `INCOMPATIBLE_BOTH`.
    3. Adding a field without default → `INCOMPATIBLE_BACKWARD`.
    These 3 cases are the **minimum viable proof of the AC #6 logic**; the gate itself (running the check in GH Actions on PR) lands as Step `avro-compat` in the workflow file.
  - [ ] Subtask 5.4: Workflow step `avro-compat` runs **only when `services/**/src/main/avro/**.avsc` files change** (`dorny/paths-filter@v2`). When triggered, it:
    - Pairs every modified `.avsc` with the same-path file on the previous commit (`git show HEAD~1:<path>`).
    - Pipes both into `util`'s `AvroCompatCheck` via `mvn -pl util test -Dtest=AvroCompatCheckCli` — **a thin CLI main** (`util/src/main/java/vn/vnpt/util/avro/AvroCompatCheckCli.java`, 30 lines) that reads `previous.avsc` + `proposed.avsc` from args, calls `AvroCompatCheck.check`, prints the verdict, exits non-zero on `INCOMPATIBLE_*`.
    - No new Maven dep added in CI — runs inside the existing test classpath. **Per CI design: keep this stub simple in Sprint 0; Story 1.3 wires the full Apicurio registration path.**

- [ ] Task 6: Stitch the GH Actions workflow (AC: 1, 2, 3, 4, 5, 6, 7, 8, 9)
  - [ ] Subtask 6.1: Job `build` has these steps, in this exact order (CI design choice: fail fast and locally informative):
    1. `actions/checkout@v4` with `fetch-depth: 0`.
    2. `actions/setup-java@v4` with Temurin distribution, version 25, `cache: maven`.
    3. `actions/setup-node@v4` with version 22, `cache: npm` (cache is empty in Sprint 0; future PRs that add a `package.json` get the cache hit).
    4. **Step `unit-tests`** — `mvn -pl util -am test` (regression for Stories 0.1/0.2/0.3 → must remain 21/21+green).
    5. **Step `validate`** — `mvn validate` (proves all 17 `<modules>` resolve; Story 0.2 + 0.3 regression).
    6. **Step `archunit`** — `mvn -pl util -am test -Dtest='*ArchUnitTest,*ForbiddenDependencyPatternsTest'` (fail-fast on package-boundary violations).
    7. **Step `spotless-check`** — `mvn -pl util spotless:check` (fail-on-format-drift).
    8. **Step `prettier-check`** — `npx --yes prettier@3.3.3 --check 'frontend/**/*.{ts,tsx,js,jsx,json,md}' --ignore-path frontend/.prettierignore` (fail-on-format-drift).
    9. **Step `avro-compat`** — `dorny/paths-filter@v2` on `services/**/src/main/avro/**`; when matched, run `mvn -pl util test -Dtest=AvroCompatCheckCli -q` (the CLI test-class); fail step on non-zero exit. Sprint 0 has no `.avsc` files yet → step exits as skipped.
  - [ ] Subtask 6.2: Each step uses `if: always() && failure()` ONLY on the `summary` step (a final `dorny/paths-filter` summary) — Sprint 0 keeps the workflow simple; **no matrix**, no fanout, no artifacts upload. Per the YOLO/ponytail ladder: smallest working diff.
  - [ ] Subtask 6.3: Workflow timezone `UTC`; **no crons**, only `pull_request` + `push`. No `schedule:` block in Sprint 0.

- [ ] Task 7: Verify (AC: 2, 3, 4, 5, 6, 7)
  - [ ] Subtask 7.1: `actionlint .github/workflows/ci.yml` (if `actionlint` is installed locally) OR `python3 -c "import yaml, sys; yaml.safe_load(open('.github/workflows/ci.yml'))"` for a free YAML-validation sanity check. **Skip if neither available — at minimum, eyeball the file**.
  - [ ] Subtask 7.2: From project root: `mvn -pl util -am test` — must remain ≥21/21 (the ArchUnit tests bring it to at least 23). Document the exact new total in Completion Notes.
  - [ ] Subtask 7.3: `mvn -pl util spotless:check` — exits 0 against current util/ codebase (after first run; if any file drifts, run `mvn spotless:apply` then re-commit. **Sprint 0 baseline: util/ is already Google-format-compliant** — verified via local `mvn spotless:check`).
  - [ ] Subtask 7.4: `npx --yes prettier@3.3.3 --check 'frontend/**/*.{ts,tsx,js,jsx,json,md}'` against the (currently empty) frontend tree — exits 0 (no files match). Verified locally.
  - [ ] Subtask 7.5: Simulate CI locally by triggering each step manually — done in Subtasks 7.2, 7.3, 7.4. **Real CI run deferred to merge time** (the GH workflow is tested by pushing to a PR; Sprint 0 has no `.avsc` files so the Avro step is skipped).
  - [ ] Subtask 7.6: `bash scripts/pre-dev-check.sh` (existing harness) — should remain green; the new `.github/` dir doesn't trigger any of the existing checks.

- [ ] Task 8: Commit + push (AC: all)
  - [ ] Subtask 8.1: Stay on `fix/r-01-util-parent-pom` (carried from Stories 0.1, 0.2, 0.3 per Sprint 0 sequential pattern). Do NOT create a new branch.
  - [ ] Subtask 8.2: Stage `.github/workflows/ci.yml`, `pom.xml` (root — adding spotless-maven-plugin to `<pluginManagement>` + archunit to `<dependencyManagement>`), `util/pom.xml` (adding spotless in `<plugins>` + archunit in `<dependencies>` + `apicurio-registry-client` in `<dependencies>`), `util/src/main/java/vn/vnpt/util/avro/AvroCompatCheck.java`, `util/src/main/java/vn/vnpt/util/avro/AvroCompatCheckCli.java`, `util/src/test/java/vn/vnpt/util/archunit/ModulithPackageBoundaryTest.java`, `util/src/test/java/vn/vnpt/util/archunit/ForbiddenDependencyPatternsTest.java`, `util/src/test/java/vn/vnpt/util/avro/AvroCompatCheckTest.java`, `frontend/.prettierrc.json`, `frontend/.prettierignore`, `platform/ci-cd/README.md` (note about workflow being at root).
  - [ ] Subtask 8.3: Commit prefix per CONVENTIONS.md §8: `feat(ci): scaffold CI gates (Story 0.4)`. Body 1–2 lines: cite architecture §"Pattern enforcement" (line 584) and NFR-MIG-2 (Avro compat). Reference the predecessors (Story 0.3 story file = `.bmad-output/implementation-artifacts/0-3-dev-docker-compose-...md`).
  - [ ] Subtask 8.4: Push + open PR. **Push requires GitHub credentials** — if `git push` returns `fatal: could not read Username for 'https://github.com'`, surface that and ask the user to push themselves. Same pattern as Stories 0.1 / 0.2 / 0.3.
  - [ ] Subtask 8.5: After merge, the GH Actions workflow will run on the merged `main`; the FIRST run serves as the live acceptance test for AC #2 / #3 / #4 / #5 / #6 / #7. **If the first run fails, fix and follow up** — do not declare done based on local-only verification.

## Dev Notes

### Architecture intent — what the CI is enforcing

Per `architecture.md` §"Pattern enforcement" (lines 582–587) — verbatim, this is the **de facto** contract for Story 0.4:

- Line 584: **"CI checks: archunit tests verify package boundaries (Modulith `ApplicationModules.verify()`) and forbidden API patterns."**
- Line 586: **"Style: Spotless (Java) + Prettier (TypeScript) run on every commit."**
- Line 587: **"Lint: Checkstyle (Java) + ESLint (TypeScript) block PRs with violations."**

(Story 0.4's epics.md AC list mentions Spotless + Prettier + ArchUnit + Avro compat; **Checkstyle + ESLint** are deferred to the first service-module story in Epic 1. **Sprint 0 scope does NOT include Checkstyle/ESLint config — see "Sprint 0 deviation" in Completion Notes** when implemented.)

Additional architecture touch points:

- Line 822–826 (`platform/ci-cd/.github/workflows/ci.yml`): the "intended" path is `platform/ci-cd/.github/workflows/`. **However**, GitHub only honors `.github/workflows/*` at the **repository root**. The recommended implementation (Task 1.1 Option A) places the actual file at root; `platform/ci-cd/` becomes the documentation home. This is a documented deviation, not a contradiction.
- Line 884: Spring Modulith's `@ApplicationModule` annotation enforces package visibility at runtime — **and at build time** via `ApplicationModules.verify()`. ArchUnit (Task 4) is the build-time companion. Sprint 0 only has `util/` as a populated module; the rule shells are wired for Epic 1+ to drop into.
- Line 974: "Avro schemas: `services/<each>/src/main/avro/<Domain><Event>.avsc`. CI gate in `platform/ci-cd/`." — Sprint 0 has no Avro schemas yet (`services/<name>/` are empty per Story 0.2). The CI step is wired but skipped via `dorny/paths-filter` until Epic 1.3 (Story 1.3) lands the first schema.
- ADR-15 (line 224) + NFR-MIG-2 (line 1051): Avro backward + forward compat CI gate is the binding security/contract control for the event-driven architecture.

### Detected conflicts / project-specific adjustments

| Source | Where | Conflict / adjustment |
|---|---|---|
| `architecture.md` line 822 | `platform/ci-cd/.github/workflows/ci.yml` | GitHub Actions only honors `.github/workflows/` at **repository root**. Place the actual file at root; `platform/ci-cd/` becomes the documentation home (Subtask 8.2 adds `platform/ci-cd/README.md`). |
| `architecture.md` line 587 | Checkstyle + ESLint | AC list does NOT name them; Sprint 0 spotless covers Java formatting (which overlaps with checkstyle style rules) and Prettier covers TS. **Checkstyle + ESLint** are deferred to the first service module story (Epic 1). Documented deviation. |
| `architecture.md` line 884 | `ApplicationModules.verify()` | Sprint 0 only has `util/` as a populated module; the static ArchUnit rule is wired (`ModulithPackageBoundaryTest`) but the **Modulith-specific** `ApplicationModules.verify()` call lands in the first multi-module Epic 1 story. Sprint 0 ships the dependency + a rule that **becomes binding** when more `@ApplicationModule` packages appear. |
| `local-docs/08` lines 188–218 | Service naming | Confirmed: this story does NOT add any `services/<name>/` Java code. Story 0.2 placed the directory tree; this story only adds the Maven plugins + ArchUnit dependency + workflow file. |
| `addendum.md` A4 R-09 | Boot 4 ecosystem library lag | Mitigation: **`archunit-junit5:1.4.1`** + **`spotless-maven-plugin:2.46.0`** are both version-pinned exactly. No `1.x`/`2.x` floating. |
| `CONVENTIONS.md` line 143 vs 145 | Allman vs K&R | Architecture + Spring convention is K&R; `googleJavaFormat` style `GOOGLE` is K&R — matches the recommended line 145. |
| `services/<name>/**` Sprint 0 state | Per Story 0.2 baseline: `<packaging>pom</packaging>` placeholders, **no Java/TS files** | The workflow's `archunit`, `spotless`, `prettier`, `validate`, `test` steps all pass against this baseline today. Avro step is skipped by `dorny/paths-filter`. |

### Architecture guardrails — MUST be preserved

- **Pinned versions exactly.** NO `1.x`, NO `2.x` floating, NO `-SNAPSHOT` — Story 0.1's review-follow-up lesson (commit F9/F10) carries over to every new plugin/dep.
- **BOM single source of truth.** Boot `4.0.0` + Cloud `2025.1.0` BOMs live in `util/pom.xml` `<dependencyManagement>` only. ArchUnit and Apicurio deps are added to `util/pom.xml` `<dependencies>` (test scope). **Root pom holds only `<pluginManagement>` and `<dependencyManagement>` entries** — no `repositories`, no version re-imports.
- **Java 25 LTS.** `<release>25</release>` at the compiler stays; Spotless' `googleJavaFormat` is Java-version-agnostic.
- **Plugin pin:** every plugin pinned to exact version in root `<pluginManagement>`. Spotless joins the existing `maven-compiler-plugin:3.14.1` + `spring-boot-maven-plugin:4.0.0`.
- **Test-count discipline.** Story 0.2's review caught a `17/17 → 21/21` documentation drift. Verify the EXACT new total before writing Completion Notes.
- **`mvn -pl util -am test` must remain the canonical regression gate** — AC #7 enforces it.

### Architecture guardrails — MUST NOT be touched

- **`util/pom.xml`'s `<dependencyManagement>` import block** — Boot/Cloud BOMs are the single source of truth.
- **`.github/` at any path other than `.github/workflows/`** — GH Actions only reads that directory at the repo root.
- **Any `services/<name>/src/**` content** — empty placeholders per Story 0.2; this story touches no Java/TS in services/.
- **Any `bff/<surface>-bff/src/**` content** — empty placeholders per Story 0.2.
- **`local-docs/**`**, **`_bmad-output/**`**, **`docs/adr/0001-record-architecture-decisions.md`** (already exists), **`util/SnowflakeIdGenerator.java`** (Story 0.5).
- **`dev/docker-compose.yml`** — Story 0.3. CI may use `dev/scripts/test-infra.sh` for compose validation **only after** Epic 1 stories wire a `dev/docker-compose.ci.yml` overlay (out of scope here).

### Source tree components to touch

| File / Dir | Action | Why |
|---|---|---|
| `.github/workflows/ci.yml` | Create | AC #1, #2, #3, #4, #6, #8, #9 |
| `.github/workflows/README.md` | Create (1 paragraph) | Document the deviation: actual location vs architecture's `platform/ci-cd/.github/workflows/ci.yml` |
| `pom.xml` (root) | Edit | Add spotless-maven-plugin to `<pluginManagement>`; add archunit-junit5 dep to `<dependencyManagement>` |
| `util/pom.xml` | Edit | Add spotless plugin to `<plugins>`; add archunit + apicurio-registry-client to `<dependencies>` (test scope) |
| `util/src/main/java/vn/vnpt/util/avro/AvroCompatCheck.java` | Create | AC #6 (production code) |
| `util/src/main/java/vn/vnpt/util/avro/AvroCompatCheckCli.java` | Create | AC #6 (CLI hook used by the GH Actions step) |
| `util/src/test/java/vn/vnpt/util/archunit/ModulithPackageBoundaryTest.java` | Create | AC #5 |
| `util/src/test/java/vn/vnpt/util/archunit/ForbiddenDependencyPatternsTest.java` | Create | AC #5 (companion test for wiring proof) |
| `util/src/test/java/vn/vnpt/util/avro/AvroCompatCheckTest.java` | Create | AC #6 proof (3 cases) |
| `frontend/.prettierrc.json` | Create | AC #3, #4 |
| `frontend/.prettierignore` | Create | AC #3, #4 |
| `platform/ci-cd/README.md` | Create (documentation only) | Notes that the working workflow lives at `.github/workflows/ci.yml` per GH Actions semantics |
| `_bmad-output/implementation-artifacts/0-4-ci-scaffold-github-actions-archunit-spotless-prettier.md` | Modify (status, agent record, completion notes) | Story bookkeeping |
| `_bmad-output/implementation-artifacts/sprint-status.yaml` | Modify (`0-4-…: backlog → ready-for-dev`) | Story bookkeeping |

**Do NOT touch:** `util/src/main/java/vn/vnpt/util/SnowflakeIdGenerator.java` (Story 0.5), `dev/docker-compose.yml` (Story 0.3), any `services/<name>/**` or `bff/<surface>-bff/src/**`, `local-docs/**`, `docs/adr/0001-record-architecture-decisions.md`, `util/pom.xml`'s BOM imports.

### Project Structure Notes

- **Alignment with unified project structure:** `.github/workflows/ci.yml` is the GitHub-Actions-required path; the architecture's `platform/ci-cd/.github/workflows/ci.yml` is **non-canonical** for GH Actions but matches the architecture's **conceptual** placement (CI lives under `platform/ci-cd/`). Documented deviation via `.github/workflows/README.md` and `platform/ci-cd/README.md`.
- **Detected conflict with architecture line 822:** explicit deviation logged above.
- **Detected conflict with architecture line 587** (Checkstyle + ESLint): deferred to first service-module story (Epic 1). Sprint 0 covers Spotless + Prettier, which subsume Checkstyle-style and ESLint-style formatting.
- **`util/` is the only Java module today.** All CI hooks (Spotless, ArchUnit, Apicurio client) attach to `util/pom.xml`. The root pom `<pluginManagement>` + `<dependencyManagement>` additions are forward-compat: Epic 1+ services inherit without re-import.

### Library vs application distinction

- `util/` (`<packaging>jar</spring-boot-maven-plugin>` configured) gains two new deps (`archunit-junit5`, `apicurio-registry-client`) and one new plugin (`spotless-maven-plugin`) — all test-scope except the plugin which is `<build>`-scope. Production jar contents unchanged.
- **No new runtime classpath additions** outside `util/src/main/java/vn/vnpt/util/avro/` (`AvroCompatCheck` + `AvroCompatCheckCli`).
- The 14 services + 2 BFFs stay empty Maven modules (placeholders per Story 0.2). CI runs against util/ only in Sprint 0.

### Testing standards summary

- **Required regression check (AC #7):** `mvn -pl util -am test` must return **21 existing + 2 new ArchUnit + 3 AvroCompatCheck = 26/26** (or higher if Epic 1 stories have landed). **Verify exact count before declaring done.**
- **No testcontainers in Sprint 0.** Story 0.4's AC #2 mentions Testcontainers; Sprint 0 has zero integration code that needs them. **Documented scope item**: Testcontainers tests land in Epic 1+ when the first database-bound service exists. The CI job **runs** `mvn -pl util -am verify` (which would execute Testcontainers if any existed today), so the wiring is forward-compatible.
- **Avro compat CI gate (AC #6):** the gate logic is proven by `AvroCompatCheckTest`'s 3 cases against Apicurio's compatibility checker. Live CI run is gated by `dorny/paths-filter` and will not fire in Sprint 0 (no `.avsc` files). Sprint 0 ships the **producer**, the **3-case unit test**, and the **CI step** (skipped); Story 1.3 will trigger the CI step for real when the first `.avsc` lands.
- **Maven plugins verified end-to-end:** `mvn spotless:check` returns 0 on current `util/` (Google-format-compatible); `npx prettier --check` returns 0 on empty `frontend/`.
- **Test-count discipline:** Story 0.2's review caught a `17/17 → 21/21` documentation drift. Be precise — the new total is `21 + 2 + 3 = 26` (or document the actual `mvn -pl util -am test` output before writing Completion Notes).

### Branch / commit policy

- **Current branch:** `fix/r-01-util-parent-pom` (carried from Stories 0.1, 0.2, 0.3). Stay on it. Don't create a new branch.
- **Commit prefix:** `feat(ci): ...` per CONVENTIONS.md §8 (new scope: `ci`). Rationale: a green-PR CI gate is a feature, not a fix. Alternative `chore(ci): ...` if the commit is purely platform plumbing.
- **Commit granularity:** prefer a single commit covering `.github/workflows/ci.yml` + the root pom additions + `util/pom.xml` additions + the new util/ Java/test files + `frontend/.prettierrc.json` + `.prettierignore` + the README. Review fixes land in follow-up `chore(ci): ...` commits.
- **Push policy:** surface `fatal: could not read Username` to the user — same as Stories 0.1 / 0.2 / 0.3.

### Risk and predecessor notes

- **Predecessor:** Story 0.3 (dev docker-compose). No direct dependency — Story 0.3 ships `dev/docker-compose.yml`; Story 0.4 ships the CI that runs against it (when Epic 1+ adds Testcontainers). Sprint 0 has no live integration between the two. AC #7's `mvn -pl util -am test` baseline is Story 0.2 / Story 0.3's `21/21` — Story 0.4 brings it to at least `26/26`.
- **Successor:** Story 0.5 (Snowflake strict mode). Story 0.5 modifies `util/SnowflakeIdGenerator.java`; **does not** touch this story's additions. Both stories live on the same branch in Sprint 0.
- **R-09 (Boot 4 ecosystem immaturity):** ArchUnit `1.4.1` + Spotless `2.46.0` + Apicurio client `2.6.13.Final` are all version-pinned exactly. No floating.
- **Operational risk — `npx --yes prettier@3.3.3` flakiness:** Prettier 3.3.3 is stable; the **`--yes`** auto-fetches on every CI run. First run is ~30s slower than cached (no `package.json` for npm cache to anchor on in Sprint 0). Document this in Completion Notes; Epic 2 will add `package.json` and the cache anchors.
- **Operational risk — ArchUnit false positives in Sprint 0:** the rule "no `infrastructure` imports" is wired but matches nothing today (only `util/` exists). Confirm with a deliberate **counter-test**: temporarily add a class that imports from another module's `infrastructure/` — expect the test to fail. **Do this AS A LOCAL CHECK before declaring done**, then revert the counter-test. (Story 0.3 introduced the `test-infra.sh` static-validation harness; the same pattern: verify the gate is wired BEFORE shipping.)
- **Operational risk — Avro checker dependency:** `apicurio-registry-client:2.6.13.Final` is the version pulled by the dev compose (`apicurio/apicurio-registry-mem:2.6.13.Final` per Story 0.3 / completion notes). Same version in test-scope dep; no drift.

### Previous story intelligence (Story 0.3 — relevant carry-overs)

- **Pin-everything-to-a-tag discipline.** Story 0.3 pinned every Docker image. Apply same mindset to plugins/deps: every new entry has an exact version.
- **`dev/.env.example` + `.gitignore` template style** — `.prettierignore` follows the same `.gitignore` line-by-line convention (no shell comments needed).
- **Test-count discipline.** Story 0.2's review caught a count drift; Story 0.3 explicitly verified `21/21` before writing `21/21` in Completion Notes. **Same here:** verify `mvn -pl util -am test` exact new total BEFORE writing it.
- **Push credentials issue.** Surface and ask — same as Stories 0.1 / 0.2 / 0.3.
- **`dev/elasticsearch/Dockerfile` precedent (Story 0.3 deviation #4):** when a planned tool is not real, defer to architecture's alternative path. The same applies here: Checkstyle + ESLint are mentioned in architecture line 587 but Sprint 0's epics AC list does NOT include them. Defer to Epic 1.

### References

- Story source (epics): `_bmad-output/planning-artifacts/epics.md` §"Epic 0 > Story 0.4" (lines 418–430)
- Architecture (binding): `_bmad-output/planning-artifacts/architecture.md` §"Pattern enforcement" (lines 582–587), §"Project Structure" (lines 822–826), §"File Organization Patterns" (line 974)
- Architecture detail: `_bmad-output/planning-artifacts/architecture-detail.md` §"Detail: ADR-04" line 103 (Avro CI gate), §"Detail: ADR-01" line 97 (BOM source of truth)
- NFR-MIG-2 (binding): `_bmad-output/planning-artifacts/epics.md` (line 1051) — Avro backward + forward compat CI
- ADR-15 (binding): `_bmad-output/planning-artifacts/architecture.md` line 224 — Avro schema compat: strict backward + forward, CI gate
- Conventions: `_bmad-output/CONVENTIONS.md` §8 commit prefixes, §2 Java modules (Maven)
- Risk register: `_bmad-output/planning-artifacts/addendum.md` §A4 R-09 (Boot 4 ecosystem library lag — version pinning)
- Predecessor stories: `_bmad-output/implementation-artifacts/0-3-dev-docker-compose-postgres-kafka-kraft-es-redis-apicurio-minio.md`, `…/0-2-bootstrap-multi-module-maven-monorepo.md`, `…/0-1-fix-util-parent-pom-blocker-r-01.md`
- Build command refs: `_bmad-output/DEVOPS-RUNBOOK.md` line 45, `_bmad-output/LOCAL-DEV-SETUP-CHECKLIST.md` line 104, `_bmad-output/SPRINT-0-ONBOARDING.md` line 141
- Reviewer checklist: `_bmad-output/REVIEWER-GUIDE.md` line 215
- PR checklist: `_bmad-output/CONTRIBUTING.md` lines 120–121, 155

## Dev Agent Record

### Agent Model Used

claude-sonnet (project-dev) — BMAD bmad-dev-story workflow v1

### Debug Log References

### Completion Notes List

### File List

## Change Log

## Senior Developer Review (AI)

<!-- To be filled by the reviewer after the dev agent submits. -->
