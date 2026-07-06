---
audience: anyone-opening-a-PR
project: side-project
date: 2026-07-06
how-to-use: conventions for opening a Pull Request. Required reading before any code change.
---

# Contributing — side-project

> **TL;DR:** Read the story's AC → write code matching architecture's patterns → open a PR with a clear title + link to story + run all CI checks locally → request review from the right person.

---

## 1. Before you start

### Read

- `AGENT-ONBOARDING.md` (project state)
- The story you're implementing (`epics.md`)
- The relevant ADR (`ADR-INDEX.md`)
- If changing architecture → `DECISION-LOG-CHEATSHEET.md`
- If changing security → `SECURITY-MODEL.md`

### Confirm

- Story is in `sprint-status.yaml` with status `ready-for-dev`
- Sprint Lead has assigned the story
- No other PR is already in progress for the same story (check `git log --oneline | grep "Story-N.M"`)

### Create a feature branch

```bash
# Branch naming: <type>/<scope>-<short-desc>
# Types: feat, fix, refactor, docs, test, chore
# Scope: story id (e.g., story-1.6) or area (e.g., catalog, payment)

git checkout main
git pull
git checkout -b fix/story-1.6-reservation-deadlock
# or
git checkout -b feat/story-2.5-saga-state-machine
# or
git checkout -b docs/update-architecture-cycle-5
```

---

## 2. While you're coding

### Match the architecture's patterns

Per `architecture.md` §"Implementation Patterns":

- **Structured JSON logging** with MDC traceId, spanId (see DEVOPS-RUNBOOK.md)
- **Given/When/Then** for AC verification
- **EventListener** with `processed_event` dedup table
- **util `BaseEntity`** for new JPA entities (auto-includes audit fields)
- **util `SnowflakeIdGenerator`** for any new aggregate ID
- **util `JasperUtils`** for any report rendering
- **Outbox pattern** for atomic state change + event publish
- **HMAC signing** for every event (per ADR-20)

### Code style

```bash
# Java formatter (Spring Boot Spotless plugin)
mvn spotless:apply

# TypeScript / Frontend (Prettier)
cd frontend/storefront
npm run format
```

### Don't

- ❌ Don't commit `.env` files (use Vault, per ADR-18)
- ❌ Don't log PAN-shaped fields (per R-15, ADR-23)
- ❌ Don't add libraries without checking architecture compatibility
- ❌ Don't modify ADRs in architecture.md without following DECISION-LOG-CHEATSHEET
- ❌ Don't push directly to main — always use PRs
- ❌ Don't commit secrets (API keys, passwords)
- ❌ Don't hardcode URLs, ports, or service names
- ❌ Don't skip tests ("will add later")
- ❌ Don't merge your own PR — always have a reviewer

---

## 3. PR title and description

### Title format

```
<type>(<scope>): <description> [story N.M]
```

Examples:
- `fix(inventory): fix deadlock in FOR UPDATE reservation [story 1.6]`
- `feat(catalog): add per-locale ES index bootstrap [story 6.1]`
- `docs(architecture): add Q5 closure procedure [cycle 5]`
- `chore(deps): bump Stripe SDK to 12.34.5 [story 3.1]`

### Description template

```markdown
## What

<1-paragraph summary of the change>

## Why

<Reference the story + its FRs. e.g., "Implements Story 1.6 (FR-9: reservation with TTL). Solves DI-01 (oversell race).">

## Changes

- <bullet list of major changes>
- <file paths affected>

## Testing

- [ ] Unit tests pass (`mvn -pl <module> test`)
- [ ] Integration tests pass (`mvn -pl <module> verify -Dit.test=*IT`)
- [ ] For critical-path: ran 100x consecutively
- [ ] For perf-sensitive: latency measured

## Risks mitigated

- <reference risk ID if applicable, e.g., "DI-01 (oversell race) — Story 1.6 fix">

## Risks introduced

- <any new risks, with mitigations>

## Architecture compliance

- [ ] No new ADRs introduced (or new ADR drafted per DECISION-LOG-CHEATSHEET)
- [ ] No security controls relaxed (per SECURITY-MODEL.md)
- [ ] No PAN-shaped data in logs

## Reviewer

@<reviewer-username> — please review [aspects]

## Linked

- Closes #<issue-number> (if applicable)
- Implements Sprint N Story N.M
```

---

## 4. CI checks (run locally before pushing)

```bash
# 1. Build
mvn -B clean install -DskipTests

# 2. Unit tests
mvn -B test

# 3. Integration tests
mvn -B verify -Dit.test=*IT

# 4. Lint (Spotless + Prettier)
mvn -B spotless:check && (cd frontend && npm run lint)

# 5. Avro compat check (if event schema changed)
./scripts/check-avro-compat.sh

# 6. Build Docker images
./scripts/build-images.sh

# 7. Scan for security issues
mvn -B org.owasp:dependency-check-maven:check
```

If any fails, **fix before pushing**. CI will reject the PR anyway.

### Optional: act (local CI runner)

```bash
# Install: brew install act
act -j build  # Run the build job locally
```

---

## 5. Review process

### Who reviews

| Files touched | Reviewer |
|---|---|
| `architecture.md` | Architect (senior dev) + 1 other senior |
| `prd.md` | PM + Architect |
| `epics.md` | Sprint Lead + Epic Owner |
| Service code (any) | Epic Owner + 1 peer dev |
| Security-sensitive (auth, payment, PCI) | Security Lead + Epic Owner |
| Frontend (`frontend/`) | Frontend Lead + 1 peer |
| Platform (`platform/`, `helm/`, `devops`) | Platform Lead |

### Review checklist (for the reviewer)

- [ ] PR title matches the convention
- [ ] Description references the story + FR + risk
- [ ] CI is green
- [ ] Code matches architecture's patterns (see §2 above)
- [ ] Tests cover the AC (Given/When/Then)
- [ ] No new ADRs were introduced (or they're in a separate PR with rationale)
- [ ] No security controls relaxed
- [ ] No `.env` files, no hardcoded secrets
- [ ] No PAN-shaped data in logs
- [ ] No comments like "TODO: refactor" without a linked issue
- [ ] Test coverage didn't drop (check the CI coverage report)

### Approval + merge

- 1 approval is required for routine changes
- 2 approvals for: arch changes, security changes, schema changes, new dependencies
- After approval, the author (or auto-merge) merges

---

## 6. Post-merge

### Update sprint-status.yaml

```yaml
# After merge, update _bmad-output/implementation-artifacts/sprint-status.yaml
# Move story from `in-progress` or `review` to `done`:
1-6-reservation-with-ttl-fr-9-solves-di-01-root-cause: done
```

### Update the story's documentation (if relevant)

If your PR changed:
- A new ADR → update `architecture.md` + `ADR-INDEX.md` + bump `reviewStatus`
- A new event schema → update `architecture-detail.md` §"Detail: ADR-04"
- A new risk → update `RISK-REGISTER.md`
- A new pattern → update `architecture.md` §"Implementation Patterns"
- A new environment variable → update `DEVOPS-RUNBOOK.md` + Vault

### Communicate

- Slack channel: `#side-project` (announce "Story N.M merged to main")
- Update sprint burndown chart
- If it was a Critical risk mitigation, tag it: `closes R-XX`

---

## 7. Common PR scenarios + best practices

### Scenario 1: "I'm adding a new event"

```bash
# 1. Add Avro schema in services/<x>/src/main/avro/<topic>.avsc
# 2. Run mvn compile — generates Java class from Avro
# 3. Use in your use case
outbox.append(new MyEvent(snowflakeId(), ...));

# 4. Register in Apicurio
curl -X POST 'http://localhost:8080/apis/registry/v2/groups/<group>/artifacts' \
  -H 'Content-Type: application/json' \
  -d '{"artifactId": "MyEvent", "artifactType": "AVRO", "content": "..."}'

# 5. CI Avro compat check
./scripts/check-avro-compat.sh
# Should pass — your new event is backward + forward compat
```

### Scenario 2: "I'm adding a new entity"

```bash
# 1. Create entity in services/<x>/src/main/java/.../domain/<Entity>.java
# 2. Extend BaseEntity (from util) — auto-includes audit fields
public class Product extends BaseEntity { ... }
# 3. Add @SoftUk if it's soft-deletable
@SoftUk(fields = {"sku"})
# 4. Create Flyway migration
touch services/<x>/src/main/resources/db/migration/V002__add_products.sql
# 5. Add unit test
# 6. Add integration test
```

### Scenario 3: "I'm adding a new external integration (e.g., new carrier)"

```bash
# 1. Add adapter in services/<x>/src/main/java/.../carrier/
public class DhlShipmentAdapter implements ShipmentService { ... }
# 2. Register bean in config class
@Bean
public ShipmentService shipmentService(DhlConfig config) { ... }
# 3. Add config to application.yml
dhl:
  api-key: ${DHL_API_KEY}
  api-secret: ${DHL_API_SECRET}
# 4. Add to Vault
vault kv put secret/dhl api_key=... api_secret=...
# 5. Add unit + integration test
# 6. Document in DEVOPS-RUNBOOK.md
```

### Scenario 4: "I'm fixing a Critical risk"

```bash
# 1. Reference the risk in commit + PR
git commit -m "fix(inventory): solve DI-01 oversell race via FOR UPDATE + TTL

Implements Story 1.6. R-XX mitigation.

- Add SELECT FOR UPDATE on inventory_ledger
- Add 15-min TTL with sweeper
- 100x concurrent test passes"

# 2. PR description must reference the risk + root cause
## Risks mitigated
DI-01 (oversell race) — Story 1.6 fix

# 3. After merge, update RISK-REGISTER.md
# (mark as mitigated + verification)

# 4. Update architecture's Critical-Risk-to-ADR binding table
# (the row for R-XX now has a verified binding)
```

---

## 8. What NOT to do

| ❌ Don't | ✅ Do instead |
|---|---|
| Push directly to main | Open a PR, get review |
| Merge your own PR | Get a teammate to review + merge |
| Add new dependency without checking arch compatibility | Discuss in PR or Slack |
| Add TODO comments | Open a GitHub issue, link from comment |
| Hardcode secrets | Use Vault (per ADR-18) |
| Log PAN-shaped fields | Use Stripe Elements iframe (per ADR-23) |
| Skip tests because "we'll add later" | Write tests as part of the same PR |
| Refactor unrelated code in a feature PR | Keep PRs focused; split refactor into separate PR |
| Comment "this is hacky but works" | Refactor to be clean, or open an issue |
| Add a new file to `prds/prd-.../` run-folder | Add to flat `prd.md`; only run-folder holds the canonical copy at creation time |

---

## 9. Code review etiquette

### As a reviewer

- Review within 1 business day
- Be specific: "Line 47: this comparison should be `equals` not `==`" not "this is wrong"
- Distinguish blocking vs non-blocking comments: "🔴 must fix" / "🟡 suggestion" / "🟢 nice to have"
- Approve when ready; don't withhold for stylistic preferences

### As an author

- Respond to every review comment (resolve, defer, or discuss)
- Re-request review after pushing fixes
- Don't take it personally — review is about the code, not you
- If you disagree, explain why calmly with data

---

## 10. Cross-references

- **Architecture patterns to follow:** `architecture.md` §"Implementation Patterns"
- **Decision log (ADRs):** `DECISION-LOG-CHEATSHEET.md`
- **Security model (must follow):** `SECURITY-MODEL.md`
- **Story coverage:** `epics.md`
- **Sprint status:** `_bmad-output/implementation-artifacts/sprint-status.yaml`
- **All quickref index:** `AGENT-ONBOARDING.md`
- **CI/CD pipeline:** `DEVOPS-RUNBOOK.md` §13
- **Test patterns:** `INTEGRATION-TEST-CHEATSHEET.md`
