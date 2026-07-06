---
audience: PM, release-manager, SRE
project: side-project
date: 2026-07-06
how-to-use: how to maintain release changelog. Auto-generated from commits + manual risk callouts.
---

# Changelog — side-project

> **Standard:** [Keep a Changelog](https://keepachangelog.com/) v1.1.0.
> **Auto-generation:** via `conventional-changelog` from commit messages (per `CONTRIBUTING.md` §3).
> **Where this lives:** `_bmad-output/CHANGELOG.md` — committed to repo so it's versioned.

---

## 1. Format

```markdown
# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- New feature

### Changed
- Modified feature

### Deprecated
- Soon-to-be removed feature

### Removed
- Removed feature

### Fixed
- Bug fix

### Security
- Security fix

## [1.0.0] - 2026-XX-XX

### Added
- Initial release
- ...
```

---

## 2. Categories (Keep a Changelog standard)

| Category | When to use |
|---|---|
| **Added** | New feature, new endpoint, new event schema |
| **Changed** | Modification to existing feature (with backward compat) |
| **Deprecated** | Soon-to-be removed feature (still works) |
| **Removed** | Removed feature (no longer works) |
| **Fixed** | Bug fix |
| **Security** | Security fix or hardening (R-XX mitigation, dependency CVE) |

---

## 3. Conventional Commits (auto-generation source)

### Format

```
<type>(<scope>): <description>

[body]

[footer(s)]
```

### Types

- **feat:** New feature → "Added" section
- **fix:** Bug fix → "Fixed" section
- **refactor:** Code change without behavior change → "Changed" section
- **perf:** Performance improvement → "Changed" section
- **docs:** Documentation only → "Changed" section
- **test:** Test addition (no production code change) → (typically not in changelog)
- **chore:** Build / tooling change → (typically not in changelog)
- **ci:** CI change → (typically not in changelog)
- **revert:** Revert previous change → (in "Removed" or "Changed")
- **security:** Security fix → "Security" section

### Scopes (per project)

```
auth, catalog, inventory, cart, checkout, payment, order, fulfillment, returns, customer, search, notification, admin, pricing, invoice, util, bff, frontend, helm, devops, architecture, prd, epics, docs
```

### Examples

```
feat(catalog): add per-locale ES index bootstrap [story 6.1]
fix(payment): solve DI-02 double-capture via stable idempotency key [story 3.1] [R-03]
refactor(architecture): split into main + detail companion files
docs(readme): add Quick Start section
security(vault): rotate HMAC service keys [R-22]
perf(cache): add jittered TTL to prevent cache stampede
chore(deps): bump Stripe SDK to 12.34.5
```

---

## 4. Auto-generation

### Setup

```bash
# Install conventional-changelog
npm install -g conventional-changelog-cli
# Or in repo:
npm install --save-dev conventional-changelog
```

### Config (`.versionrc`)

```json
{
  "header": "# Changelog\n\nAll notable changes to this project will be documented in this file.\n\nThe format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/).\n",
  "types": [
    {"type": "feat", "section": "Added"},
    {"type": "fix", "section": "Fixed"},
    {"type": "refactor", "section": "Changed"},
    {"type": "perf", "section": "Changed"},
    {"type": "docs", "section": "Changed"},
    {"type": "test", "section": "Changed"},
    {"type": "revert", "section": "Removed"},
    {"type": "security", "section": "Security"}
  ]
}
```

### Generate

```bash
# Generate from git tags
conventional-changelog -p conventionalcommits -i CHANGELOG.md -s

# Or, with custom config:
./node_modules/.bin/conventional-changelog -c .versionrc -i CHANGELOG.md -s
```

### CI integration (per `CONTRIBUTING.md` §4)

```yaml
# .github/workflows/release.yml
name: Release
on:
  push:
    tags: ['v*']
jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }  # full history for changelog
      - uses: actions/setup-node@v4
      - name: Generate changelog
        run: npx conventional-changelog -i CHANGELOG.md -s
      - name: Commit changelog
        run: |
          git config user.email "ci@example.com"
          git config user.name "CI"
          git add CHANGELOG.md
          git commit -m "docs(changelog): update for ${{ github.ref_name }}"
          git push
```

---

## 5. Manual entries (per release)

Some entries need manual callouts — auto-generation doesn't capture them:

### Risk mitigations (high-priority)

```markdown
### Security
- **Mitigates R-XX (risk name)** — brief description
```

### Compliance + audit

```markdown
### Security
- **VN tax-invoice credentials configured** (per Story 9.2b) — Q5 closure complete
- **PDPD data export endpoint live** (per Story 5.2) — LC-01 verified
```

### Breaking changes (use `### Changed` or `### Removed` with `BREAKING:` prefix)

```markdown
### Changed
- **BREAKING:** Saga state machine moved to per-aggregate column (from JSONB blob); require Postgres migration V015.
```

---

## 6. Versioning strategy (Semantic Versioning)

### Format: MAJOR.MINOR.PATCH

- **MAJOR:** Breaking change (e.g., v1.0 → v2.0 for saga state machine overhaul)
- **MINOR:** New feature, backward-compat (e.g., v1.0 → v1.1 for new search filter)
- **PATCH:** Bug fix, no new feature (e.g., v1.0.0 → v1.0.1 for hot fix)

### Pre-1.0 (during v0.x)

- **MINOR = breaking** (per semver pre-1.0)
- **PATCH = feature addition**

For side-project: 0.x during alpha/beta, 1.0 for first production release.

### Tagging

```bash
# Tag a release
git tag -a v1.0.0 -m "v1.0.0 release"
git push origin v1.0.0

# Tag a pre-release
git tag -a v0.9.0-beta.1 -m "v0.9.0-beta.1"
git push origin v0.9.0-beta.1
```

---

## 7. Release entry template (per `RELEASE-PROCESS.md`)

For each release, copy this template into CHANGELOG.md:

```markdown
## [VERSION] - YYYY-MM-DD

### Sprint N: Foundation (or per epic)
- **Story 0.1** (R-01) — util parent pom fixed
- **Story 0.2** — multi-module monorepo bootstrap
- **Story 0.3** — dev docker-compose (Postgres + Kafka + ES + Redis + Apicurio + MinIO)
- **Story 0.4** — CI scaffold (GitHub Actions + Archunit + Spotless + Prettier)
- **Story 0.5** (R-22) — Snowflake strict mode
- **Architecture:** All 26 ADRs documented; Saga = Spring Modulith outbox; 13 services + 2 BFFs + 2 frontends
- **Risk mitigations:** R-01 fixed; R-22 fixed
- **Compliance:** No compliance changes (this is foundational release)

### Sprint N+1: Catalog + Inventory
- **Story 1.1** — CatalogService Maven module bootstrap
- **Story 1.2** — Product aggregate + variant graph
- ... etc

### Security
- **Mitigates R-XX (risk name)** — brief description

### Fixed
- ...

### Changed
- ...

### Removed
- (if any)

### Deprecated
- (if any)
```

---

## 8. Pre-1.0 changelog (v0.x)

For 0.x releases, use this format:

```markdown
## [0.1.0-alpha.1] - 2026-08-01

### Sprint 0: Foundation
- **Story 0.1** (R-01) — util parent pom fixed
- ...

### Architecture
- 26 ADRs documented
- 13 services mapped

### Security
- Vault policies set up
- HMAC keys generated (R-22)

### Known limitations
- Multi-warehouse stock not yet implemented (Sprint 1)
- VN tax-invoice specifics pending (Q5, Sprint 9)
- A11y not yet audited (per addendum A3)
```

---

## 9. CHANGELOG file (this file) — initial entries

```markdown
# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.0.0-planning] - 2026-07-06

### Added
- Initial BMad planning artifacts (10/10 quality):
  - Brainstorming session (189 ideas, 13 root-cause drills, 15 risks)
  - PRD (82 FRs, 24 NFRs, 5 Qs all resolved)
  - Architecture (26 ADRs, 10 detail sections)
  - Epics & Stories (11 epics, 57 stories, 82/82 FRs pinned)
  - Implementation readiness report
  - Sprint status (57 stories in backlog, 0 in progress)
  - 37 quickref docs (8,000+ lines, 40,000+ words)

### Architecture
- 13 service bounded contexts (per ADR-02)
- Spring Modulith outbox saga (per ADR-01)
- 30+ Kafka topics with Avro strict compat (per ADR-15)
- Per-locale ES indices (per ADR-25)
- 15 risk mitigations (R-01..R-15) bound to ADRs and stories
- 5 Open Questions (Q1..Q5) all resolved
  - Q1 saga: Modulith outbox (ADR-01)
  - Q2 multi-warehouse: single-warehouse v1 (ADR-06)
  - Q3 B2C vs marketplace: B2C v1 (ADR-07)
  - Q4 soft-delete uniqueness: @SoftUk (ADR-05)
  - Q5 VN tax-invoice specifics: credential schema (ADR-26 + Story 9.2b)

### Security
- VN PDPD data export (per FR-46 / Story 5.2)
- R2F (right to be forgotten, per FR-49)
- Stripe Elements iframe (per FR-29 / ADR-23)
- HMAC event signing (per ADR-20)
- OTel log redaction (per FR-29)
- 11 hard rules (per SECURITY-MODEL.md §12)

### Compliance
- VN tax-invoice plan (per FR-78 / ADR-26)
- PDPD data export (per FR-46)
- PCI-DSS scope minimized (per ADR-23)

### Known limitations
- 11 sprints remaining before v1.0
- All stories are backlog (none in progress)
- VN tax-invoice credentials need accountant input (Q5, Sprint 9)

### Documentation
- 37 quickref docs (~72K words):
  - Architecture: ADR-INDEX, ARCHITECTURE-QUICKREF, ARCHITECTURE-DIAGRAMS
  - Domain: API-CONTRACT, DATA-MODEL, SECURITY-MODEL, COMPLIANCE-VN
  - Dev/QA/Ops: SPRINT-0/1, EPIC-1-STORIES, INTEGRATION-TEST, FRONTEND, DEVOPS, QA-AGENT, CONTRIBUTING, CACHING
  - Operational: RISK-REGISTER, OBSERVABILITY, ALERTING, CAPACITY, DISASTER, ON-CALL
  - Governance: REVIEWER, FEATURE-FLAGS
  - Process: RELEASE, BUG-TRIAGE, COMPLIANCE-AUDIT
  - Infra: KAFKA, METRICS, A11Y, CACHING
  - Meta: AGENT-INTERACTION, AGENT-ONBOARDING, GLOSSARY, LOCAL-DEV-SETUP
  - Last BMad: STORY-AUTOMATOR
```

---

## 10. Tooling

### Recommended: `git-cliff`

`git-cliff` is similar to `conventional-changelog` but more configurable and faster (written in Rust).

```bash
# Install
brew install git-cliff

# Generate changelog
git-cliff --tag v1.0.0 --output CHANGELOG.md
```

### Alternative: `release-please` (Google)

`release-please` automates the entire release process (changelog + tag + GitHub release).

```bash
# GitHub Action
- uses: googleapis/release-please-action@v3
  with:
    release-type: java
    package-name: side-project
```

### Recommended for side-project

- **Conventional-changelog** for v1 (we have it already via commit messages)
- **release-please** if we want to automate GitHub releases

---

## 11. Cross-references

- **Release process:** `RELEASE-PROCESS.md`
- **PR conventions (commit format):** `CONTRIBUTING.md`
- **Sprint status:** `_bmad-output/implementation-artifacts/sprint-status.yaml`
- **Architecture (release sequencing):** `architecture.md` §"Decision Impact Analysis"
- **Risk register (high-priority release items):** `RISK-REGISTER.md`
- **Feature flags (kill switches for release):** `FEATURE-FLAGS.md`
- **Glossary:** `GLOSSARY.md`
