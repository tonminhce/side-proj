---
audience: SRE, on-call engineer, PM, dev
project: side-project
date: 2026-07-06
how-to-use: how on-call rotation works. Pair with BUG-TRIAGE.md (incident response) + REVIEWER-GUIDE.md.
---

# On-Call Roster — side-project

> **Goal:** Clear ownership for incidents. Everyone knows who's on call, who to escalate to, and what the SLA is.
> **Convention:** Follow the sun is NOT used (we're a single-region project). Each rotation is 1 week.

---

## 1. Rotation structure

### Levels

| Level | Role | Primary | Backup | When active |
|---|---|---|---|---|
| **L1** | On-call SRE | Primary | Backup SRE | 24/7, every week |
| **L2** | Tech Lead (Architect) | Primary | Senior Dev | 9am–6pm Mon–Fri (local) |
| **L3** | Release Manager | Primary | PM | 9am–5pm Mon–Fri |
| **L4** | Vietnamese Tax Accountant (external) | n/a | n/a | Async, < 24h response |

### Weekly rotation

| Week | Primary on-call | Backup | Notes |
|---|---|---|---|
| Week 1 | SRE A | SRE B | |
| Week 2 | SRE B | SRE A | |
| Week 3 | SRE A | SRE B | |
| Week 4 | SRE B | SRE A | |

Rotation managed by PagerDuty (or similar). Schedules set 4 weeks in advance.

---

## 2. When you're on call

### Pre-week (Friday before your on-call week)

- [ ] Check the schedule: PagerDuty
- [ ] Verify your phone is on, notifications enabled
- [ ] Verify VPN access works
- [ ] Verify Vault access works
- [ ] Verify K8s dashboard access works
- [ ] Verify Grafana / Loki / Tempo access works
- [ ] Verify GitHub access works
- [ ] Review recent Sev-0/1 incidents from the past 2 weeks
- [ ] Review open Sev-2/3 bugs
- [ ] Verify no vacation / time-off planned

### During the week

- [ ] Respond to alerts within SLA (per `ALERTING-RUNBOOK.md`):
  - **Critical (Sev-0):** page → 5 min
  - **High (Sev-1):** page → 30 min
  - **Medium (Sev-2):** Slack → 4 hours
  - **Low (Sev-3):** file issue, no alert
- [ ] Check dashboards at start of day (15 min):
  - Service health
  - Error rates
  - Latency p99
  - Outbox backlog
  - Kafka consumer lag
- [ ] Document any incidents in `BUG-TRIAGE.md` workflow
- [ ] Escalate per §3 below

### Post-week (Friday end of on-call week)

- [ ] Hand off to the next on-call (15 min)
  - Walk through the week's incidents
  - Open issues
  - Pending investigations
- [ ] Write a brief recap (in `#side-project-oncall` Slack channel)

---

## 3. Escalation matrix

| Severity | Primary on-call (L1) | Backup SRE | L2 (Tech Lead) | L3 (Release Manager) | Vietnamese Tax Accountant (L4) |
|---|---|---|---|---|---|
| **Sev-0 (Critical)** | Page immediately → 5 min response | If no response 15 min | If still no response 30 min | 1 hour | (async, only if VN tax-invoice) |
| **Sev-1 (High)** | Page immediately → 30 min response | 1 hour | 2 hours | 4 hours | n/a |
| **Sev-2 (Medium)** | Slack notification → 4 hours | Same day | Next business day | n/a | n/a |
| **Sev-3 (Low)** | File issue → next sprint | n/a | n/a | n/a | n/a |

### Specific scenarios

| Scenario | Escalate to | Why |
|---|---|---|
| **Outbox bridge failure** | L2 (Tech Lead) | Architecture change may be needed |
| **Card-testing in prod** | L2 + L3 (Compliance) | PCI scope concern |
| **VN tax-invoice misbehavior** | L2 + L4 (Accountant) | Compliance |
| **Customer data leak** | L2 + L3 (Legal) | PDPD breach notification required |
| **Stripe API outage** | L2 (we can't fix Stripe; just wait) | n/a |
| **VN tax authority API outage** | L2 + L4 | Compliance + retry |
| **K8s cluster failure** | L2 (Platform lead) | Infra decision |
| **Postgres corruption** | L2 + DBA | Data recovery |

---

## 4. Handoff procedure

### Weekly handoff (Friday)

```markdown
# On-Call Handoff: [Date]

## From: @<outgoing>
## To: @<incoming>

## This week
- Incidents: [list, or "none"]
- Open issues: [list, or "none"]
- Pending investigations: [list, or "none"]

## Looking ahead
- Known risks: [list, or "none"]
- Upcoming deploys: [list, or "none"]
- Capacity concerns: [list, or "none"]

## Carry-over items
- [item 1]
- [item 2]
```

### Per-incident handoff (when a Sev-0/1 is in progress)

```markdown
# Incident Handoff: [Incident ID]

## From: @<outgoing>
## To: @<incoming>

## Status
- Current severity: Sev-X
- Started at: [timestamp]
- Mitigation status: [in-progress / mitigated]
- Estimated recovery: [time]

## What we know
- [bullet 1]
- [bullet 2]

## What's still being investigated
- [bullet 1]
- [bullet 2]

## Action items
- [ ] [item 1]
- [ ] [item 2]
```

---

## 5. Compensation for on-call

### Standard rates (recommended; actual rates per company policy)

- **Weekday on-call (per week):** X% of base salary (per local labor law)
- **Weekend on-call (per weekend):** Y% of base salary
- **Holiday on-call (per holiday):** Z% of base salary
- **Paged response (per incident):** flat fee
- **Time worked (per incident):** overtime rate

### Time off (comp time)

- Each incident response is tracked
- Comp time accrued = hours worked during incident
- Use comp time within 1 month (or per local policy)
- Manager approves comp time

---

## 6. On-call health

### Avoid burnout

- **Max 1 week on-call per 4 weeks** (avoid consecutive rotations)
- **Comp time:** Take it after each incident > 4 hours
- **Handoff quality:** Don't rush it; 15 min is the minimum
- **Phone notifications:** Off during sleep hours (unless Sev-0)
- **Manager check-in:** Weekly 1:1 during on-call week

### Red flags

- ❌ On-call for 2+ weeks in a row
- ❌ Working > 12 hours/day on-call day
- ❌ Skip comp time for > 2 weeks
- ❌ Hand off without proper handoff
- ❌ Skip post-incident reviews

If you see these patterns, escalate to manager immediately.

---

## 7. Tooling setup

### PagerDuty

- [ ] Service: side-project
- [ ] Schedules: 4 weeks in advance
- [ ] Escalation policy: 1 (L1) → 2 (L2) → 3 (L3)
- [ ] Notifications: SMS + phone call (Sev-0) / Slack (Sev-1+) / email (Sev-2+)
- [ ] Quiet hours: 10pm-8am (configurable per on-call)

### Slack

- [ ] `#side-project-oncall` (Sev-0/1 alerts go here)
- [ ] `#side-project-launch` (release coordination)
- [ ] `#side-project-dev` (general dev channel)
- [ ] Per-service channels: `#catalog-alerts`, `#payment-alerts`, etc.
- [ ] Slackbot reminders for handoffs

### Phone / SMS

- [ ] All on-calls have SMS-capable phone
- [ ] Phone numbers are kept up-to-date in PagerDuty

### Access (verify before on-call week starts)

- [ ] VPN (Tailscale or similar)
- [ ] K8s dashboard
- [ ] Grafana (read + write for ad-hoc alerts)
- [ ] ArgoCD (read + rollback capability)
- [ ] Vault (read all secrets)
- [ ] GitHub (read all repos, write to issues)
- [ ] PagerDuty / incident response tool
- [ ] Slack (read + write)
- [ ] Stripe dashboard (read)

---

## 8. Common scenarios during on-call

### Scenario 1: Critical alert fires (Sev-0)

1. **Acknowledge** in PagerDuty (within 5 min)
2. **Open** relevant dashboard in Grafana
3. **Follow** runbook in `ALERTING-RUNBOOK.md` for the specific alert
4. **Mitigate** (per `BUG-TRIAGE.md` step 3)
5. **Communicate** in `#side-project-oncall`
6. **Track** in incident ticket
7. **Escalate** if mitigation fails (per §3 above)
8. **Post-mortem** within 24h

### Scenario 2: Slack notification (Sev-1/2)

1. **Acknowledge** in Slack
2. **Check** dashboards
3. **Investigate** (per `ALERTING-RUNBOOK.md` + `BUG-TRIAGE.md`)
4. **File** an issue if needed
5. **Mitigate** if possible
6. **Reply** in Slack with action taken

### Scenario 3: Handoff (end of week)

1. **Run** handoff template (per §4)
2. **Update** schedule for next 4 weeks
3. **Confirm** with next on-call

### Scenario 4: Comp time dispute

1. **Reference** compensation rates (per §5)
2. **Discuss** with manager
3. **Adjust** if needed

---

## 9. On-call training

### New on-call checklist (first time)

- [ ] Shadow another on-call for 1 week
- [ ] Read all runbooks (`ALERTING-RUNBOOK.md`, `BUG-TRIAGE.md`, `DISASTER-RECOVERY.md`, `DEVOPS-RUNBOOK.md`, `OBSERVABILITY-RUNBOOK.md`)
- [ ] Read `AGENT-ONBOARDING.md`
- [ ] Read `RISK-REGISTER.md`
- [ ] Read `ARCHITECTURE-QUICKREF.md`
- [ ] Read `PROBLEM-DOMAINS-MAP.md`
- [ ] Read `API-CONTRACT.md`
- [ ] Set up tooling (PagerDuty, Slack, VPN, etc.)
- [ ] Pair with another SRE for 1 day during a real incident
- [ ] Get sign-off from manager

### Quarterly training

- New runbook walkthrough
- Post-mortem review
- Alert-tuning workshop

---

## 10. On-call charter

### What on-call does

- ✅ Respond to alerts within SLA
- ✅ Investigate Sev-0/1 immediately
- ✅ Mitigate before fixing
- ✅ Document incidents
- ✅ Communicate status
- ✅ Hand off cleanly

### What on-call does NOT do

- ❌ Make architectural changes during incident (escalate to L2)
- ❌ Sign off on new deployments (escalate to L3)
- ❌ Decide on legal/compliance matters (escalate to L3)
- ❌ Take more than 1 week on-call in a row (manager exception)
- ❌ Skip handoff (always do it)

---

## 11. Cross-references

- **Bug triage:** `BUG-TRIAGE.md`
- **Alerting runbook:** `ALERTING-RUNBOOK.md`
- **Disaster recovery:** `DISASTER-RECOVERY.md`
- **Release process:** `RELEASE-PROCESS.md`
- **Reviewer guide:** `REVIEWER-GUIDE.md`
- **Architecture quickref:** `ARCHITECTURE-QUICKREF.md`
- **Domain map:** `PROBLEM-DOMAINS-MAP.md`
- **API contract:** `API-CONTRACT.md`
- **Onboarding:** `AGENT-ONBOARDING.md`
- **Glossary:** `GLOSSARY.md`
