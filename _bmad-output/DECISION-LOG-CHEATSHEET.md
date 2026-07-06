---
audience: architect, senior dev
project: side-project
date: 2026-07-06
how-to-use: how to add / update an Architecture Decision Record (ADR). Required reading before making binding technical changes.
---

# Decision Log Cheatsheet — side-project

> **Why this exists:** The architecture document is the **single source of truth** for binding technical decisions. When you change a decision, you must record the change so other agents / reviewers can see the new state.
> **Convention:** Michael Nygard ADR format, extended with status + binding scope.

---

## 1. When do you need a new ADR?

You need a new ADR when you make a decision that:
- **Constrains future implementation** (e.g., "we use Postgres 16", "we use Spring Modulith outbox")
- **Affects more than one service** (e.g., "events use HS256 HMAC")
- **Would be expensive to reverse** (e.g., "we adopt Saga-based checkout")
- **Resolves an Open Question** from the PRD (Q1..Q5)

You do **NOT** need an ADR for:
- Implementation details within a single service (e.g., "we use a HashMap for this in-memory cache")
- Library version pinning (already covered by version matrix in `addendum.md` A4)
- Configuration values (use application.yml + Vault)

---

## 2. ADR format

### Template

```markdown
### ADR-NN — [Short title, imperative form]

**Status:** Proposed | Accepted | Deprecated | Superseded by ADR-MM
**Date:** YYYY-MM-DD
**Deciders:** [name(s) or "Architecture skill"]
**Supersedes:** ADR-XX (if any)

**Context**

[2-3 sentences: the situation requiring a decision. The forces at play. Why this is non-trivial.]

**Decision**

[The decision itself. Imperative form: "We will..." or "The system shall..."]

**Rationale**

[Bullet list of reasons. Reference risks (R-XX), root causes (DI-XX), and Open Questions (Q-X) addressed.]

**Implementation guidance**

[How to implement: tech stack, packages, patterns. Reference to architecture-detail.md if deep dive needed.]

**Consequences**

[Positive consequences: what this enables.]
[Negative consequences: what this forecloses, what risks remain.]

**Alternatives considered**

- **Alternative A:** [description]. Why rejected: [reason].
- **Alternative B:** [description]. Why rejected: [reason].
```

### Example (real ADR-01)

```markdown
### ADR-01 — Use Spring Modulith outbox for v1 saga

**Status:** Accepted
**Date:** 2026-07-06
**Deciders:** Architecture skill (binding Q1 saga decision)
**Supersedes:** None

**Context**

Q1 in the PRD offered two candidates: Spring Modulith outbox (default Green-Hat) vs Spring Statemachine + Debezium outbox + Kafka transactions. Saga coordination needs to be reliable without operational complexity.

**Decision**

We will use Spring Modulith outbox as the v1 saga architecture. All 13 services are logical modules in a single deployment. No Debezium in v1.

**Rationale**

- Q1 in the PRD: Spring Modulith outbox is the Green-Hat default binding.
- Modulith outbox eliminates the Debezium+Kafka-transactions operational complexity (R-04) for v1.
- The reference-impl audience benefits from a working monolith more than from a fragile microservice.
- Saga is intra-process; no network round-trips.

**Implementation guidance**

- All 13 services start as **logical modules** in a single Modulith deployment unit.
- Inter-module communication: direct method calls (intra-JVM) for synchronous flows; outbox + Kafka for cross-domain events.
- A saga is implemented as a state machine on the order aggregate (Spring Modulith's `@ApplicationModule`-scoped state machine, NOT Spring Statemachine the library).
- Each service has its own outbox table; Modulith outbox bridge publishes outbox rows to Kafka topics.

**Consequences**

- ✅ Single deployment unit; simpler ops.
- ✅ No Debezium learning curve.
- ❌ No per-service scaling until v2 split.
- ❌ Process-level blast radius (one module's bug can crash the process).

**Alternatives considered**

- **Spring Statemachine + Debezium outbox + Kafka transactions:** More representative of production microservice patterns. Why rejected: operational complexity (Debezium + Kafka transactions) outweighs benefits for a reference impl.
- **Hand-rolled saga in plain Spring:** No library overhead. Why rejected: re-invents a well-trodden pattern; Spring Modulith outbox is the canonical Spring approach.
```

---

## 3. Numbering convention

- **ADR-NN:** Sequential numbering, zero-padded 2 digits (ADR-01, ADR-02, ..., ADR-26)
- **Current count:** 26
- **Next ADR:** If you're adding ADR-27, check first that no one else has added ADR-27+ in their branch
- **Deprecated ADRs** stay in the document with `Status: Deprecated`; do not delete
- **Superseded ADRs** are replaced; mark with `Supersedes: ADR-XX` and `Superseded by: ADR-MM` in their headers

---

## 4. Status state machine

```
Proposed → Accepted
                ↓
             Deprecated (when decision is no longer binding)
                ↓
             Superseded by ADR-MM (when replaced by a new ADR)
```

- **Proposed:** drafted, awaiting review
- **Accepted:** binding; all implementations must follow
- **Deprecated:** no longer recommended; still in the document for history
- **Superseded by ADR-MM:** replaced; pointer to the new ADR

---

## 5. Where to put the ADR

### In `architecture.md`

The "Core Architectural Decisions" section contains:
- An **ADR Index** table (current ADRs and their status)
- A **detail** for each ADR (full description)

**To add a new ADR:**
1. Add a row to the ADR Index table at the top
2. Add a `### Detail: ADR-NN` subsection in the appropriate place (usually after related ADRs)

**To mark as Deprecated:**
1. Update the ADR's `Status:` field
2. Update the ADR Index table row
3. Add a note in the "Detail" section explaining why and when

**To Supersede:**
1. Create the new ADR
2. Mark the old ADR as `Superseded by ADR-NN` in its header
3. Update the ADR Index

### In the frontmatter

When you make changes to ADRs, also update the architecture.md frontmatter:
```yaml
reviewStatus: 'cycle-N-clean'      # increment N
reviewDate: 'YYYY-MM-DD'           # today's date
```

---

## 6. Workflow: Adding a new ADR

### Step 1: Identify the need

Ask:
- Is this decision binding for more than one service?
- Is this decision expensive to reverse?
- Is this a previously-deferred Open Question?

If yes to any: write an ADR.

### Step 2: Draft the ADR

Use the template (Section 2). Be specific:
- **Context** = why this decision needs to be made
- **Decision** = imperative form, not "we could do X" but "we will do X"
- **Rationale** = bullets, with refs to R-XX/DI-XX/Q-X
- **Consequences** = positive AND negative
- **Alternatives considered** = at least 1, with reason rejected

### Step 3: Add to architecture.md

1. Open `_bmad-output/planning-artifacts/architecture.md`
2. Find "Core Architectural Decisions" section
3. Add row to ADR Index table (new ADR-NN)
4. Add `### Detail: ADR-NN — [title]` subsection
5. If detail is long → put in `architecture-detail.md` companion
6. Update frontmatter `reviewStatus: 'cycle-(N+1)-clean'` and `reviewDate`
7. Update `_bmad-output/AGENT-ONBOARDING.md` if it changes the overall story
8. Update `_bmad-output/ADR-INDEX.md` to add the 1-paragraph summary

### Step 4: Propagate to dependent docs

| Doc | What to update |
|---|---|
| `prd.md` | If the ADR resolves an Open Question, update §13 with the resolution status |
| `epics.md` | If the ADR affects a story's AC, update the relevant stories |
| `addendum.md` | If a previously-rejected alternative is now in scope, move it out of A2 |
| `RISK-REGISTER.md` | If the ADR changes a risk mitigation, update the mitigation field |
| `IMPLEMENTATION-READINESS-REPORT.md` | If the ADR changes scope, the readiness verdict may change |
| `_bmad-output/AGENT-ONBOARDING.md` | If the high-level flow changes |
| `_bmad-output/ARCHITECTURE-QUICKREF.md` | If a key decision is added/changed |
| `_bmad-output/ADR-INDEX.md` | Add the new ADR entry |

### Step 5: Validate

- [ ] ADR has all 7 sections (Context, Decision, Rationale, Implementation guidance, Consequences, Alternatives considered, Status/Date/Deciders)
- [ ] ADR index table updated
- [ ] Architecture frontmatter `reviewStatus` bumped
- [ ] All dependent docs cross-checked
- [ ] Sprint story updates identified (if any)

---

## 7. Workflow: Updating an existing ADR

### When to update (not create new)

- The decision itself is unchanged, but implementation details need clarification
- A risk is newly mitigated (e.g., we just added a chaos test that proves the mitigation works)
- A new alternative was considered and rejected
- Status changes (Accepted → Deprecated; Accepted → Superseded by ADR-MM)

### Step 1: Identify the change

Ask:
- Is this a clarification (no real change to the decision)?
- Is this a substantive change (the decision itself changes)?

For clarification: edit the existing ADR (note the date of update).
For substantive change: consider creating a new ADR that supersedes.

### Step 2: Edit the ADR

```markdown
### ADR-01 — Use Spring Modulith outbox for v1 saga

**Status:** Accepted (last updated 2026-07-06)
**Date:** 2026-07-06
**Updated:** 2026-07-06 (clarified outbox bridge poll interval)
**Deciders:** Architecture skill

[existing content...]

**Updated implementation guidance** (2026-07-06):

- **Outbox bridge poll interval:** 500ms (was "configurable, default 1s")
- **Batch size:** 100 events per poll (newly added)
- **Failure mode when Kafka is down:** outbox table grows; sweeper drops rows older than 7 days; alert via Telegram.
```

### Step 3: Update dependent docs

- `architecture.md` frontmatter: `reviewStatus: 'cycle-(N+1)-clean'`
- `_bmad-output/ADR-INDEX.md`: updated 1-paragraph summary if relevant
- Other dependent docs as needed

---

## 8. Workflow: Deprecating or Superseding an ADR

### Deprecate (no replacement)

```markdown
### ADR-XX — [title]

**Status:** **Deprecated** (as of YYYY-MM-DD)
**Reason:** [why this decision is no longer recommended]
**Superseded by:** None (kept for historical reference)
```

Use this when:
- We no longer follow the decision, but no new decision has been made
- The decision was specific to v1 and won't apply to v2

### Supersede (new ADR replaces)

1. Create the new ADR (ADR-MM) with full template
2. Mark the old ADR with:
   ```markdown
   **Status:** **Superseded by ADR-MM** (as of YYYY-MM-DD)
   **Reason:** [what changed and why the new decision is preferred]
   ```
3. In the ADR Index table, add cross-reference: `ADR-XX (superseded by ADR-MM)`
4. In the new ADR (ADR-MM) header, reference: `Supersedes: ADR-XX`

---

## 9. ADR review checklist

Before merging an ADR to architecture.md, verify:

- [ ] **Context** explains WHY this decision is needed (not just what's being decided)
- [ ] **Decision** is in imperative form ("We will...") and is unambiguous
- [ ] **Rationale** has at least 3 bullets
- [ ] Each rationale bullet references a specific constraint (R-XX, DI-XX, NFR-XX, Q-X) or principle
- [ ] **Implementation guidance** is concrete enough to start coding
- [ ] **Consequences** lists BOTH positive and negative
- [ ] **Alternatives considered** has at least 1 entry with reason rejected
- [ ] **Status** is set correctly (Proposed, Accepted, Deprecated, Superseded)
- [ ] **Date** is correct
- [ ] **Deciders** are listed
- [ ] Cross-references in dependent docs are updated

---

## 10. Common pitfalls

### ❌ Don't write a "Premature Decision"

```markdown
<!-- ❌ WRONG: Too early to commit -->
### ADR-XX — Use Kafka 4.0.1.5 (exact patch)
**Decision:** Lock Kafka to version 4.0.1.5.
```

- Kafka patch versions are pinned in `addendum.md` A4 (version matrix), not in ADRs.
- ADRs are for *structural* decisions, not *version* decisions.

### ❌ Don't write an "Implementation Detail"

```markdown
<!-- ❌ WRONG: Not a binding decision, just code organization -->
### ADR-XX — Use Lombok @Builder on DTOs
**Decision:** All DTOs use @Builder.
```

- This is a code style convention, not a binding architecture decision.
- Put in `CONTRIBUTING.md` or a code-style guide.

### ❌ Don't make a Decision that conflicts with an existing ADR

Before writing an ADR, check the existing 26 ADRs. If your decision conflicts with one, you have 2 options:
1. Update the existing ADR (if you can justify why)
2. Supersede the existing ADR (if the new decision is genuinely better)

Never silently write a contradictory ADR.

### ❌ Don't write an ADR without exploring alternatives

An ADR is a **record of why you chose X over Y**. If you can't articulate why Y was rejected, you don't have a decision — you have an assumption.

---

## 11. The 26 current ADRs (cross-reference)

| ADR | Status | Title | First-decided |
|---|---|---|---|
| ADR-01 | Accepted | Use Spring Modulith outbox (Q1) | 2026-07-06 |
| ADR-02 | Accepted | 13 services; Auth folded into Customer | 2026-07-06 |
| ADR-03 | Accepted | Per-service database | 2026-07-06 |
| ADR-04 | Accepted | Kafka 4 KRaft + Avro + Apicurio | 2026-07-06 |
| ADR-05 | Accepted | Soft-delete uniqueness via `@SoftUk` | 2026-07-06 |
| ADR-06 | Accepted | Single-warehouse v1 (Q2) | 2026-07-06 |
| ADR-07 | Accepted | B2C v1 (Q3) | 2026-07-06 |
| ADR-08 | Accepted | VN tax-invoice (Q5) | 2026-07-06 |
| ADR-09 | Accepted | REST + BFF | 2026-07-06 |
| ADR-10 | Accepted | Next.js 15 App Router | 2026-07-06 |
| ADR-11 | Accepted | Idempotency key strategy | 2026-07-06 |
| ADR-12 | Accepted | Saga = intra-process state machine | 2026-07-06 |
| ADR-13 | Accepted | Rate-limiter Lua with `redis.call('TIME')` | 2026-07-06 |
| ADR-14 | Accepted | Per-service outbox + Modulith bridge (no Debezium) | 2026-07-06 |
| ADR-15 | Accepted | Avro strict compat, CI gate | 2026-07-06 |
| ADR-16 | Accepted | OTel + LGTM + Chaos Mesh | 2026-07-06 |
| ADR-17 | Accepted | K8s + Helm + ArgoCD | 2026-07-06 |
| ADR-18 | Accepted | HashiCorp Vault secrets | 2026-07-06 |
| ADR-19 | Accepted | OPA/Rego admission | 2026-07-06 |
| ADR-20 | Accepted | HMAC event signing (HS256) | 2026-07-06 |
| ADR-21 | Accepted | Webhook dedup on Stripe `event.id` | 2026-07-06 |
| ADR-22 | Accepted | Snowflake strict mode (R-22) | 2026-07-06 |
| ADR-23 | Accepted | PCI scope (Stripe Elements + log redaction) | 2026-07-06 |
| ADR-24 | Accepted | Card-testing defense | 2026-07-06 |
| ADR-25 | Accepted | VN diacritic search | 2026-07-06 |
| ADR-26 | Accepted | VN tax-invoice (Jasper + QR + serialized) | 2026-07-06 |

(For 1-paragraph summary of each, see `_bmad-output/ADR-INDEX.md`.)

---

## 12. Examples of past ADR patterns

### A clean ADR cycle (3 examples)

#### 1. **Propose → Accept in same session** (small change)

```
1. Architect writes ADR-XX in architecture.md
2. reviewStatus: cycle-N-wip
3. PR review by 1-2 senior devs
4. reviewStatus: cycle-N-clean
5. ADR-Index table updated
```

#### 2. **Propose → Discuss → Accept** (large change)

```
1. ADR-YY in architecture.md with status=Proposed
2. Open PR; 1-2 reviewers comment
3. Architect revises → re-review
4. Once approved: status=Accepted
5. If related stories change → epics.md updated
```

#### 3. **Supersede** (e.g., v1 to v2 saga architecture change)

```
1. Architecture skill writes ADR-NN for v2 saga (e.g., "extract checkout as microservice")
2. Old ADR-01 marked Superseded by ADR-NN
3. ADR Index updated with both rows
4. epics.md v2 stories added
```

---

## 13. The decision-log.md relationship

The architecture document has a `### Decision Impact Analysis` section. Each sprint listed there is a *consequence* of the ADRs. The decision log is the bridge between ADRs and the sprint plan.

When you add an ADR, also check if `architecture.md` §"Decision Impact Analysis" needs updating.

When you change an ADR, check if any Sprint plan assumptions need updating.

---

## 14. Tools and templates

### ADR template (copy-paste)

```markdown
### ADR-NN — [Short title, imperative form]

**Status:** Proposed
**Date:** YYYY-MM-DD
**Deciders:** 
**Supersedes:** None

**Context**

[2-3 sentences]

**Decision**

We will [do X].

**Rationale**

- 
- 
- 

**Implementation guidance**

- 

**Consequences**

- ✅ 
- ❌ 

**Alternatives considered**

- **Alternative A:** [desc]. Why rejected: [reason].
```

### Quick check: am I about to violate an existing ADR?

Before writing, run:
```bash
grep "^\*\*Decision" _bmad-output/planning-artifacts/architecture.md | head -30
# + grep ADR-XX in your draft to ensure no conflict
```

If your new ADR conflicts, supersede or update the existing one — don't add a contradictory one.

---

## 15. Cross-references

- **All current ADRs:** `architecture.md` §"Core Architectural Decisions"
- **1-paragraph summary of each:** `_bmad-output/ADR-INDEX.md`
- **1-page quick ref:** `_bmad-output/ARCHITECTURE-QUICKREF.md`
- **Deep dives (6 ADRs):** `_bmad-output/planning-artifacts/architecture-detail.md`
- **PRD Open Questions (Q1-Q5):** `prd.md` §13
- **Risk register (R-XX):** `_bmad-output/RISK-REGISTER.md`
- **Story coverage of ADRs:** `epics.md` §"FR Coverage Map" (per FR-XXX → module)
