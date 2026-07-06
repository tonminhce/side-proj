---
audience: orchestrator + sub-agents
project: side-project
date: 2026-07-06
how-to-use: how multiple BMad agents (PM, Architect, Dev, QA, Reviewer) collaborate. Use for orchestration.
---

# Agent Interaction — side-project

> **Goal:** Document how sub-agents (Amelia, Winston, John, Paige, etc.) collaborate on this project. Who reads what, who writes what, who decides what.

---

## 1. The agent cast (BMad-defined)

Per `_bmad/bmm/config.yaml` + `config.user.yaml`:

| Agent | Role | Key file(s) | Tools |
|---|---|---|---|
| **Mary (PM)** | Business analyst | `addendum.md`, `risk-register.md` | research, stakeholder analysis |
| **John (PM)** | Product manager | `prd.md` | requirements, JTBD framing |
| **Winston (Architect)** | System architect | `architecture.md`, `architecture-detail.md` | ADRs, technical decisions |
| **Amelia (Dev)** | Senior engineer | `epics.md` | code, AC, tests |
| **Paige (Tech Writer)** | Documentation | quickref docs | documentation best practices |
| **Sally (UX Designer)** | UX specialist | (not in v1, per PRD §17) | design patterns |

External (sub-spawned by orchestrator):
- Research agents (per-task)
- Implementation agents (per-story)
- QA agents (per-PR)

---

## 2. The BMad agent collaboration flow

### Phase 1: Brainstorming
- **Who:** Mary (PM) + user
- **Reads:** user prompt + project context
- **Writes:** `_bmad-output/brainstorming/brainstorming-session-{date}.md`
- **Inputs to:** PRD

### Phase 2: PRD
- **Who:** John (PM)
- **Reads:** brainstorming session + market/domain/technical research
- **Writes:** `prd.md` + `addendum.md`
- **Inputs to:** Architecture

### Phase 3: Architecture
- **Who:** Winston (Architect)
- **Reads:** prd.md + brainstorming + research
- **Writes:** `architecture.md` + `architecture-detail.md`
- **Inputs to:** Epics & stories

### Phase 4: Epics & Stories
- **Who:** Amelia (Dev) + Paige (Tech Writer)
- **Reads:** prd.md + architecture.md
- **Writes:** `epics.md`
- **Inputs to:** Implementation readiness + Sprint planning

### Phase 5: Implementation Readiness
- **Who:** PM + Architect
- **Reads:** all artifacts
- **Writes:** `implementation-readiness-report-{date}.md`
- **Inputs to:** Sprint planning

### Phase 6: Sprint Planning
- **Who:** PM + Dev
- **Reads:** epics.md
- **Writes:** `sprint-status.yaml`
- **Inputs to:** Story Automator

### Phase 7: Story Automator
- **Who:** Multiple sub-agents (per story)
- **Reads:** sprint-status.yaml + relevant artifacts
- **Writes:** code + tests + story-level docs

---

## 3. The orchestrator's responsibility

When user issues a command, the orchestrator decides:

1. **Which workflow(s) to invoke**
2. **Which agent(s) to spawn**
3. **In what order**
4. **What context to pass**
5. **How to merge outputs**

### Decision matrix

| User intent | Workflow | Agents |
|---|---|---|
| "Brainstorm new feature" | bmad-brainstorming | Mary |
| "Write PRD for X" | bmad-prd | John |
| "Plan architecture for X" | bmad-create-architecture | Winston |
| "Break into epics" | bmad-create-epics-and-stories | Amelia + Paige |
| "Check readiness" | bmad-check-implementation-readiness | orchestrator (PM + Architect roles) |
| "Plan sprint" | bmad-sprint-planning | PM + Dev |
| "Build story X" | bmad-story-automator | Dev agent + QA agent + Reviewer agent |
| "Review code" | bmad-code-review | Reviewer agent |
| "Generate X" | (per-feature) | bmad-generate-project-context |
| "Help me debug X" | bmad-investigate | (orchestrator-driven investigation) |
| "Help with architecture decision" | bmad-correct-course | PM + Architect |

---

## 4. Agent handoff patterns

### Pattern A: Read-then-write (sequential)

```
Agent A reads prior artifacts → writes artifact 1
  ↓
Agent B reads artifact 1 → writes artifact 2
  ↓
Agent C reads artifact 2 → writes artifact 3
```

**Example:** Brainstorm → PRD → Architecture → Epics

### Pattern B: Multi-read (parallel)

```
Orchestrator spawns Agents A, B, C in parallel
Each reads all prior artifacts
Each writes their own artifact
Orchestrator merges outputs
```

**Example:** Multiple quickref docs built in parallel (per AGENT-ONBOARDING creation pattern)

### Pattern C: Critique-iterate (loop)

```
Agent A writes artifact 1
  ↓
Agent B critiques artifact 1 → suggests changes
  ↓
Agent A applies changes → artifact 1.1
  ↓
Repeat until satisfied
```

**Example:** PRD review before finalization (per BMad PRD §"Reviewer Gate")

### Pattern D: Validate-after-write

```
Agent A writes artifact 1
  ↓
Validator sub-agent reviews artifact 1 → returns verdict
  ↓
If PASS: artifact 1 is done
If FAIL: agent A iterates based on feedback
```

**Example:** Implementation readiness review (per `IMPLEMENTATION-READINESS-REPORT.md`)

---

## 5. Sub-agent isolation patterns

### What sub-agents do (per task)

- Read specific input docs (e.g., a specific epic)
- Write specific output (e.g., a specific story-level doc)
- Stay within their scope (don't modify other docs)

### What sub-agents do NOT do

- Modify input docs
- Modify other agents' outputs
- Make architecture decisions (escalate to orchestrator)
- Make priority decisions (escalate to orchestrator)

### Example: building a quickref doc

```python
# Orchestrator spawns sub-agent
sub_agent = spawn_agent(
    role="doc-writer",
    instructions="""
    Write AGENT-ONBOARDING.md based on:
    - planning-artifacts/prd.md
    - planning-artifacts/architecture.md
    - planning-artifacts/epics.md
    - implementation-artifacts/sprint-status.yaml
    
    Style: 11 sections, agent-friendly, with cross-references to canonical docs.
    Length: 100-300 lines.
    Audience: future-agents.
    """,
    output="AGENT-ONBOARDING.md"
)

# Sub-agent reads input docs, writes output doc
# Orchestrator verifies (per BMad: review cycle if needed)
```

---

## 6. Conflict resolution

When 2 sub-agents disagree, or a sub-agent's output conflicts with prior artifacts:

### Step 1: Identify the conflict

```python
# Orchestrator scans output for conflicts
if sub_agent_a_output.contradicts(prior_artifact):
    log_conflict(sub_agent_a_output, prior_artifact)
```

### Step 2: Resolve

| Conflict type | Resolver |
|---|---|
| **Factual disagreement** (e.g., "Story 1.6 says FR-9" vs "epics.md says FR-9") | Use prior artifact (epics.md is canonical) |
| **Style disagreement** (e.g., "Use bullet list" vs "Use table") | Orchestrator decides (use most-appropriate style) |
| **Substantive disagreement** (e.g., "Use Saga A" vs "Use Saga B") | Escalate to user (Architect + PM + orchestrator) |
| **Missing data** (e.g., "Where's FR-50?") | Re-spawn sub-agent with clarification prompt |

### Step 3: Update canonical

After resolution:
- Update canonical doc (`architecture.md`, `prd.md`, `epics.md`)
- Bump `reviewCycle` in frontmatter
- Sub-agent re-runs with new canonical

---

## 7. State management

### Each agent's state

- **What was read:** read-only artifacts
- **What was written:** output artifacts
- **What was decided:** decisions logged in `.decision-log.md` (per workflow)
- **What's open:** deferred items logged in Open Questions

### Orchestrator's state

- Current workflow + step
- Sub-agents spawned + their outputs
- Pending decisions
- Backlog of work

### Where state is stored

- **Per workflow:** in the workflow's output file (e.g., `prd.md` frontmatter)
- **Decisions:** `.decision-log.md` (per workflow)
- **Sub-agent outputs:** output file
- **Orchestrator state:** implicit (per Claude Code session)

---

## 8. Agent quality standards

### All sub-agents must

- Read input docs completely (per BMad §"Just-In-Time Loading")
- Write output per template (per BMad §"PRD Discipline" / "Architecture Discipline" / etc.)
- Include cross-references to other artifacts
- Use the project's conventions (naming, file structure, etc.)
- Match the canonical doc's style (per `CONTRIBUTING.md` conventions)

### What to do if quality is below standard

1. Orchestrator reviews sub-agent output
2. If low quality: request changes (specific feedback)
3. Sub-agent revises
4. Re-review
5. Iterate until acceptable

---

## 9. Specific agent roles in this project

### Mary (Business Analyst / PM)

- **When to invoke:** strategic decisions, brainstorming, problem space
- **Inputs:** user prompts, project context
- **Outputs:** brainstorm sessions, risk registers
- **Tools:** research, analysis, JTBD framing

### John (Product Manager)

- **When to invoke:** product planning, requirements
- **Inputs:** brainstorming, research
- **Outputs:** PRD, addendum
- **Tools:** JTBD, stakeholder analysis

### Winston (Architect)

- **When to invoke:** technical decisions, architecture
- **Inputs:** PRD, research
- **Outputs:** ADRs, architecture.md
- **Tools:** tradeoff analysis, decision records

### Amelia (Dev)

- **When to invoke:** implementing stories
- **Inputs:** epics, ADRs
- **Outputs:** code, tests, story-level docs
- **Tools:** code generation, testing, refactoring

### Paige (Tech Writer)

- **When to invoke:** documentation
- **Inputs:** all artifacts
- **Outputs:** quickref docs
- **Tools:** documentation best practices, cross-references

### Quality Assurance agent

- **When to invoke:** testing, validation
- **Inputs:** code, AC
- **Outputs:** test reports, validation results
- **Tools:** testing patterns, chaos engineering

### Reviewer agent

- **When to invoke:** code review, architecture review
- **Inputs:** code, architecture
- **Outputs:** review comments, approval/rejection
- **Tools:** code review checklist, security analysis

---

## 10. Examples of agent interactions

### Example 1: Adding a new quickref doc

```
User: "Tạo thêm quickref / audit"
  ↓
Orchestrator:
  - Reads AGENT-ONBOARDING.md to understand state
  - Identifies gap: A11Y-CHECKLIST.md (mentioned but not created)
  ↓
Spawn sub-agent #1: write A11Y-CHECKLIST.md
  - Inputs: WCAG docs, architecture-detail.md ADR-10, FRONTEND-HANDBOOK.md
  - Output: A11Y-CHECKLIST.md
  - Verify: file is well-formed, cross-refs valid, content aligns with existing
  ↓
Output: A11Y-CHECKLIST.md
```

### Example 2: Debugging a Sev-0 incident

```
User: "Saga stuck in PAYMENT_PENDING for 1 hour"
  ↓
Orchestrator:
  - Recognizes this is a saga-recovery issue (per ALERTING-RUNBOOK.md)
  ↓
Spawn sub-agent #1: investigate (read logs, DB, traces)
  - Inputs: incident ticket, ALERTING-RUNBOOK.md, ADR-12
  - Output: incident report (root cause, mitigation)
  ↓
Orchestrator: apply mitigation (e.g., manual recovery)
  - Updates sprint-status.yaml
  - Notifies user
  ↓
Spawn sub-agent #2: post-mortem
  - Inputs: incident report, BUG-TRIAGE.md
  - Output: post-mortem (per template)
```

### Example 3: Architecture decision change

```
User: "Add a new event schema"
  ↓
Orchestrator:
  - Recognizes this needs:
    1. New Kafka topic
    2. New Avro schema
    3. ADR update
  ↓
Spawn sub-agent #1: design new schema
  - Inputs: KAFKA-TOPIC-LIFECYCLE.md, existing Avro schemas
  - Output: new schema file (in `services/<x>/src/main/avro/`)
  ↓
Orchestrator: review compat (CI must pass)
  ↓
Spawn sub-agent #2: add topic + ADR
  - Inputs: new schema, architecture.md
  - Output: ADR-27 (new), architecture.md update
  - Update KAFKA-TOPIC-LIFECYCLE.md
```

---

## 11. Communication patterns

### When to escalate to user

- Architecture decision with multiple valid choices
- Bug triage result that's Sev-0/1
- Release blocked
- Compliance violation found
- Missing data required to proceed

### When NOT to escalate

- Routine implementation
- Documentation updates
- Test failures that have a clear fix
- Code style issues

### Communication channels

- **Orchestrator ↔ user:** direct conversation
- **Orchestrator ↔ sub-agent:** internal messaging
- **Sub-agent ↔ user:** rare (orchestrator mediates)

---

## 12. Cross-references

- **All BMad workflow skill files:** `.claude/skills/bmad-*/SKILL.md`
- **All artifacts (canonical + quickref):** `_bmad-output/`
- **Onboarding:** `AGENT-ONBOARDING.md`
- **Glossary:** `GLOSSARY.md`
- **PR conventions:** `CONTRIBUTING.md`
- **Reviewer guide:** `REVIEWER-GUIDE.md`
- **Bug triage:** `BUG-TRIAGE.md`
- **Disaster recovery:** `DISASTER-RECOVERY.md`
- **Release process:** `RELEASE-PROCESS.md`
- **On-call roster:** `ON-CALL-ROSTER.md`
- **Capacity planning:** `CAPACITY-PLANNING.md`
- **Compliance audit:** `COMPLIANCE-AUDIT-CHECKLIST.md`
- **A11y:** `A11Y-CHECKLIST.md`
- **Kafka topic lifecycle:** `KAFKA-TOPIC-LIFECYCLE.md`
- **Metrics dictionary:** `METRICS-DICTIONARY.md`
