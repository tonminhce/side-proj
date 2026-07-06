---
audience: dev-agent (Amelia) + PM
project: side-project
date: 2026-07-06
how-to-use: how the Story Automator works + how to invoke it. Last BMad step.
---

# Story Automator Cheatsheet — side-project

> **What is Story Automator:** the BMad workflow that automates the build cycle for all stories in an epic, end-to-end. It runs Create → Dev → QA → Review → Retrospective steps with resumable orchestration.
> **When to use:** When you have a Sprint with a clear set of stories (e.g., Sprint 1, Sprint 5) and want to build them all systematically.

---

## 1. What does Story Automator do?

The Story Automator (`bmad-story-automator` skill) is a BMad workflow that:

1. **Iterates** through every story in a Sprint (per `sprint-status.yaml`)
2. For each story, runs a 5-step cycle:
   - **Create** — generate a story-level file (`stories/<epic>-<story>-<title>.md`) with full context
   - **Dev** — implement the code per the story's AC
   - **QA** — verify the implementation against the AC + run integration tests
   - **Review** — code review (could be human or another agent)
   - **Retrospective** — capture lessons learned
3. **Updates** `sprint-status.yaml` after each story (backlog → in-progress → review → done)
4. **Pauses** if blocked (e.g., test failure, missing credentials)
5. **Resumable** — pick up where it left off

This is the final BMad step. It assumes:
- All upstream BMad artifacts are complete (brainstorm → PRD → arch → epics → readiness → sprint plan)
- The dev platform is running
- The util/ library is built and installed

---

## 2. Prerequisites (5 min)

Before running the Story Automator:

```bash
# 1. Dev platform is up
docker compose -f dev/docker-compose.yml ps
# All 8 services healthy

# 2. util/ builds
mvn -pl util -am clean install -DskipTests
# BUILD SUCCESS

# 3. Sprint is defined in sprint-status.yaml
cat _bmad-output/implementation-artifacts/sprint-status.yaml | head -30
# Confirm all stories for target Sprint are in 'backlog' state

# 4. Stories are properly mapped to epics
grep -E "epic-[0-9]+-retrospective" _bmad-output/implementation-artifacts/sprint-status.yaml
# Confirm each epic has a retrospective
```

---

## 3. Invocation

### Via the skill

```bash
# In your Claude Code session:
"Run bmad-story-automator for Sprint 1"

# Or: "Run the story automator for epic 1 (Catalog + Inventory)"

# The skill will:
# 1. Read sprint-status.yaml to find stories in 'backlog'
# 2. Read epics.md for story AC
# 3. For each story: create the per-story file, implement, test, review
# 4. Update sprint-status.yaml after each story completes
```

### What the automator does per story

For each story (e.g., Story 1.6):

1. **Reads the story's AC** from `epics.md`
2. **Reads the relevant ADR** (e.g., ADR-12 for saga state)
3. **Reads the relevant quickref** (e.g., `SPRINT-1-DEV-HANDBOOK.md` for Sprint 1)
4. **Generates** `services/inventory/stories/1-6-reservation-with-ttl.md` with:
   - Full AC (Given/When/Then)
   - Implementation guidance
   - Test plan
   - Dependencies on prior stories
5. **Implements** the code (calls the dev agent for the actual coding)
6. **Verifies** via tests (calls QA agent for test patterns)
7. **Updates** `sprint-status.yaml` to `done`
8. **Captures** retrospective notes (call retro step)

### Throughput expectations

| Sprint | Stories | Expected time | Notes |
|---|---|---|---|
| Sprint 0 | 5 (foundations) | 1-2 days | Lots of environment setup |
| Sprint 1 | 8 (Catalog + Inventory) | 1-2 weeks | Includes R-01 mitigation verification |
| Sprint 2 | 5 (Cart + Checkout) | 1 week | Saga complexity |
| Sprint 3 | 5 (Payment) | 1-2 weeks | Critical risk mitigations (R-03, R-05, R-15) |
| Sprint 4-10 | 5-7 each | 1-1.5 weeks each | Mostly additive |
| **Total v1** | **~55 stories** | **~5-7 months solo / 3-4 months with 3 devs** | Per architecture plan |

---

## 4. Sprint 1 expected workflow

The automator runs Sprint 1 in this order:

```
Story 0.1 (R-01 fix) → blocks everything
↓
Story 0.2 (monorepo bootstrap) → unblocks services
↓
Story 0.3 (docker-compose) → unblocks runtime
↓
Story 0.4 (CI scaffold) → unblocks PR
↓
Story 0.5 (Snowflake strict) → unblocks production deploy
↓
Sprint 0 done → Sprint 1 starts
↓
Story 1.1 → 1.2 → 1.3 → 1.4 → 1.5 → 1.6 → 1.7 → 1.8
↓ (each may have dependencies on the prior)
Sprint 1 done → Sprint 2 starts
```

### Story dependency map (Sprint 1)

```
1.1 (CatalogService module)  ──→ 1.2 (Product aggregate)
                                  ├──→ 1.3 (Avro events)
                                  └──→ 1.4 (Admin UI)
1.5 (InventoryService module) ──→ 1.6 (Reservation with TTL)
                                  ├──→ 1.7 (Multi-warehouse)
                                  └──→ 1.8 (Inventory events + @SoftUk)
```

---

## 5. When does it pause?

The automator pauses if:
- A test fails (after 1 retry)
- A merge conflict
- A blocking question (e.g., "what's the Stripe account ID for prod?")
- A critical risk isn't mitigated (e.g., R-22 not deployed before Sprint 5)

When it pauses, the human (you) is asked to resolve. Then the automator resumes from where it paused.

### Resuming after a pause

```bash
# In your Claude Code session:
"Resume the story automator — last blocked at Story 1.6"

# The automator will pick up from the last completed story
# and continue with the next one
```

---

## 6. Monitoring progress

### Check `sprint-status.yaml` after each story

```bash
cat _bmad-output/implementation-artifacts/sprint-status.yaml
```

### Check the per-story file

```bash
ls services/<module>/stories/
# Each completed story has a .md file with implementation notes
```

### Check the retrospective

```bash
# Per epic, after all stories done, a retrospective .md is generated
ls <module>/retrospectives/  # or _bmad-output/retrospectives/
```

---

## 7. Common issues + fixes

### Issue: "Build failed in Story X"

```bash
# Check the build error
mvn -pl <module> clean install

# Check the story file for explicit guidance
cat services/<module>/stories/<story>.md

# Fix the issue, then resume
"Resume the story automator — last blocked at Story X"
```

### Issue: "Test passed locally but failed in CI"

This usually means:
- Local environment has implicit state (DB rows, env vars) that CI doesn't
- Test relies on a specific Kafka topic that doesn't exist in CI
- Test uses Testcontainers but CI disables Docker

Fix: ensure tests are hermetic (use Testcontainers, no shared state).

### Issue: "Story depends on a story that doesn't exist yet"

The automator will surface this. Two options:
1. Implement the dependency first (re-order the sprint)
2. Stub the dependency (acceptable for early sprints)

---

## 8. What comes after Story Automator?

The Story Automator is the **last BMad step** in the planning pipeline. After all sprints are done:

1. **Sprint 10 finishes** → all 55 stories implemented
2. **Final integration test** runs
3. **Full chaos game day** runs
4. **Performance validation** against NFRs
5. **Security scan** + pen test
6. **Production deploy** (K8s + Helm + ArgoCD per ADR-17)
7. **Ongoing maintenance** (SRE, security, capacity)

The BMad pipeline ends; project lifecycle continues.

---

## 9. Cross-references

- **Per-story handbook:** `SPRINT-1-DEV-HANDBOOK.md` (Sprint 1) or `EPIC-1-STORIES-QUICKREF.md` (per-story lookup)
- **Test patterns:** `INTEGRATION-TEST-CHEATSHEET.md`
- **QA workflows:** `QA-AGENT-HANDBOOK.md`
- **Architecture patterns:** `architecture.md` §"Implementation Patterns"
- **PR conventions:** `CONTRIBUTING.md`
- **Sprint status:** `_bmad-output/implementation-artifacts/sprint-status.yaml`
- **Full epics:** `_bmad-output/planning-artifacts/epics.md`
- **Operational runbook:** `DEVOPS-RUNBOOK.md`
- **Onboarding:** `AGENT-ONBOARDING.md`
- **Local setup:** `LOCAL-DEV-SETUP-CHECKLIST.md`
- **Sprint 0 specifics:** `SPRINT-0-ONBOARDING.md`
