---
audience: on-call engineer, dev, SRE
project: side-project
date: 2026-07-06
how-to-use: when something breaks. Triage → mitigate → communicate → fix → post-mortem.
---

# Bug Triage — side-project

> **Goal:** Fast triage + mitigation when something breaks. Then proper fix + post-mortem.
> **Rule of thumb:** Mitigate first, fix second. Don't let perfect be the enemy of good.

---

## 1. Severity classification

| Severity | Definition | Example | SLA |
|---|---|---|---|
| **Sev-0** | Critical — production down, data loss, security incident | Checkout success rate < 50% | Page immediately, fix within 4 hours |
| **Sev-1** | Major — significant degradation, workaround possible | Search returns no results | Fix within 24 hours |
| **Sev-2** | Minor — bug affects a small percentage of users | Currency formatting wrong on a specific locale | Fix within 1 week |
| **Sev-3** | Cosmetic — visual / typo / minor UX issue | Button alignment off by 1px | Fix in next sprint |

---

## 2. Triage workflow (5 min)

When you see an alert or user report:

```
1. Verify (is it real?)
   ↓
2. Classify (Sev-0/1/2/3)
   ↓
3. Mitigate (stop the bleeding)
   ↓
4. Communicate (notify stakeholders)
   ↓
5. Investigate (root cause)
   ↓
6. Fix (proper solution)
   ↓
7. Verify (test in prod-like env)
   ↓
8. Post-mortem (within 24h for Sev-0/1)
```

### Step 1: Verify (30 sec)

- Check the alert: is it a real spike, or noise?
- Reproduce in staging if possible
- Check recent deploys (ArgoCD history)
- Check rate-limit / quota dashboards

**If false alarm:** silence the alert + open an issue to fix the alert logic.

### Step 2: Classify (30 sec)

Use the table above. When in doubt, classify higher (Sev-1 vs Sev-2) — better to over-react than under-react.

### Step 3: Mitigate (5 min)

Goal: stop the bleeding. Not the same as fixing root cause.

Options (in order of preference):
1. **Roll back** the most recent deploy (per `RELEASE-PROCESS.md` §6)
2. **Feature flag off** the affected feature
3. **Rate limit** the affected endpoint
4. **Drain** the affected service
5. **Patch forward** (if you know the fix)

### Step 4: Communicate (2 min)

- **Sev-0/1**: Slack `#side-project-oncall` + status page update
- **Sev-2**: Slack channel
- **Sev-3**: GitHub issue

### Step 5: Investigate (varies)

- Grafana → Tempo → find a slow trace from the affected timeframe
- Grafana → Loki → search for error logs by traceId
- Identify root cause + write a hypothesis

### Step 6: Fix (varies)

- Branch off main
- Implement fix + test
- PR + review (Sev-0/1: expedited, < 2h; others: normal)
- Merge + deploy

### Step 7: Verify (varies)

- Re-run the scenario that triggered the bug
- Run chaos test if relevant
- Watch dashboards for 30 min after deploy
- Confirm SLO is back in compliance

### Step 8: Post-mortem (within 24h for Sev-0/1)

Per `bmad-retrospective` skill. Document:
- What happened (timeline, scope, user impact)
- Why it happened (root cause)
- What we did to mitigate
- What we'll do to prevent recurrence
- Action items with owners

---

## 3. Common incident patterns (from the project's risk register)

### Pattern 1: Outbox backlog growing (R-04)

**Symptom:** `outbox_publish_failures_total` rate > 0.1/sec; `outbox_pending_rows` > 10k.

**Likely cause:** Kafka unavailable, OR outbox bridge is stuck, OR outbox table write contention.

**Mitigate:**
```bash
# 1. Check Kafka
docker exec -it kafka kafka-topics --bootstrap-server localhost:9092 --list
# If Kafka is down, restart it
docker compose -f dev/docker-compose.yml restart kafka

# 2. Check outbox bridge
kubectl logs -n side-project deploy/modulith-bridge --tail=100
# If stuck, restart
kubectl rollout restart deployment/modulith-bridge -n side-project

# 3. Check DB
# SELECT COUNT(*) FROM outbox WHERE published_at IS NULL;
# If count > 100k, page DBA
```

**Root cause + fix:** Check network connectivity between bridge and Kafka; check outbox table indexes.

---

### Pattern 2: Inventory reservation deadlocks (R-02/DI-01)

**Symptom:** `InsufficientStockException` rate spikes; saga compensations > 100/min.

**Likely cause:** Long-held FOR UPDATE locks; transactions taking too long; hot variant (popular product).

**Mitigate:**
```bash
# 1. Identify the deadlock
psql -c "SELECT * FROM pg_locks WHERE NOT granted LIMIT 10;"

# 2. Check the hot variant
psql -c "SELECT variant_id, COUNT(*) FROM inventory_ledger WHERE created_at > now() - interval '5 minutes' GROUP BY variant_id ORDER BY 2 DESC LIMIT 5;"

# 3. If necessary, kill long-running transactions
psql -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE state = 'active' AND query_start < now() - interval '30 seconds' AND query LIKE '%FOR UPDATE%';"
```

**Root cause + fix:** Add statement_timeout, ensure reservations complete quickly, add retry with jitter.

---

### Pattern 3: Card-testing burst (R-05/AT-01)

**Symptom:** `payment_capture_total{outcome="failure"}` rate spikes; rate-limit blocks.

**Likely cause:** Card-testing attack. Real cards or test cards in prod.

**Mitigate:**
```bash
# 1. Check the rate-limit dashboard
# Look for IPs / cards hitting limits

# 2. Block the source at gateway
iptables -I INPUT -s <attacker_ip> -j DROP
# or in cloud: add to WAF deny list

# 3. Block the BIN at Stripe
# (in Stripe dashboard, add BIN to block list)
```

**Root cause + fix:** Add automatic BIN blocking, improve rate-limit thresholds, add IP-reputation feed.

---

### Pattern 4: VN tax-invoice fails to generate (R-06/Q5)

**Symptom:** `MissingTaxAuthorityCredentialException` in logs; invoices not generated.

**Likely cause:** Accountant revoked credentials, or credentials row is missing/expired.

**Mitigate:**
```bash
# 1. Check the credentials row
psql -c "SELECT merchant_tax_code, reviewed_at, reviewed_by FROM vietnam_tax_authority_credential;"

# 2. If missing or expired, contact the accountant (per Story 9.2b ceremony)

# 3. As a stopgap, fall back to dev stub mode (per architecture §"Detail: ADR-26")
```

**Root cause + fix:** Add automatic credential-expiry alerts (per `RISK-REGISTER.md` ADR-26 detail).

---

### Pattern 5: Vietnamese diacritic search returns no results (R-07)

**Symptom:** Search returns 0 results for "áo sơ mi" but "ao so mi" returns 100.

**Likely cause:** Vietnamese analyzer not configured correctly; per-locale index missing.

**Mitigate:**
```bash
# 1. Check the index template
curl -s 'http://es:9200/catalog_vi_prod/_settings?pretty' | grep analyzer

# 2. Test the analyzer directly
curl -s -X POST 'http://es:9200/catalog_vi_prod/_analyze?pretty' \
  -H 'Content-Type: application/json' \
  -d '{"analyzer": "vi_text", "text": "áo sơ mi"}'

# 3. If broken, reindex
curl -X POST 'http://es:9200/_reindex?wait_for_completion=false' -d '...'
```

**Root cause + fix:** Pin analyzer config in index template; add analyzer-correctness test to CI.

---

### Pattern 6: Snowflake ID collision in production (R-22)

**Symptom:** `snowflake_worker_id_source` metric shows 2 (SecureRandom) instead of 1 (podname).

**Likely cause:** POD_NAME not set in deployment; deployment name doesn't end with `-<id>`.

**Mitigate:**
```bash
# 1. Identify the affected pod
kubectl get pods -l app=catalog-prod -o yaml | grep POD_NAME
# Should be set

# 2. If not set, restart pod with proper env var
kubectl set env deployment/catalog-prod POD_NAME=catalog-prod-0
kubectl rollout restart deployment/catalog-prod
```

**Root cause + fix:** Add validation in Helm chart; reject deployments where POD_NAME doesn't match `<service>-<N>`.

---

## 4. Communication templates

### Sev-0 incident announcement

```
🔴 INCIDENT in progress — side-project prod

What: <one-sentence user-facing description>
Impact: <X% of users affected, key flows broken>
Started: <timestamp UTC>
Status: Mitigating now
Next update: in 15 min

Incident commander: @<name>
SRE on call: @<name>
Eng on call: @<name>

Updates: <link to incident channel>
```

### Sev-0 incident resolved

```
✅ RESOLVED — <one-sentence description>

Duration: <X minutes>
User impact: <X% of users for Y minutes>
Root cause: <one-line>
Fix: <rolled back / patched forward>
Post-mortem: <link to doc, due within 24h>
```

### Sev-2/3 bug report (GitHub)

```markdown
## Summary

<one-sentence>

## Environment

- Version: v1.0.X
- Date: <YYYY-MM-DD>
- User impact: <count or percentage>

## Steps to reproduce

1. ...
2. ...
3. ...

## Expected

...

## Actual

...

## Logs / Screenshots

<paste here>

## Severity

- [ ] Sev-0 (critical, prod down)
- [ ] Sev-1 (major, workaround exists)
- [x] Sev-2 (minor)
- [ ] Sev-3 (cosmetic)

## Linked

- Sprint: N
- Story: N.M
- Risk: R-XX (if applicable)
```

---

## 5. Post-mortem template (per bmad-retrospective skill)

```markdown
# Incident Post-Mortem: <title>

**Date:** YYYY-MM-DD
**Duration:** X minutes
**Severity:** Sev-N
**Incident commander:** @<name>

## Timeline (UTC)

- HH:MM — first alert
- HH:MM — page acknowledged
- HH:MM — root cause hypothesis
- HH:MM — mitigation applied
- HH:MM — verified resolved
- HH:MM — post-mortem scheduled

## What happened

<2-3 paragraph narrative>

## Why it happened

<root cause analysis. Use 5 Whys if helpful.>

## User impact

<X% of users affected for Y minutes. Specific flows affected.>

## What we did to mitigate

<bullet list>

## What we'll do to prevent recurrence

- [ ] Action item 1 (owner: @name, due: date)
- [ ] Action item 2 (owner: @name, due: date)
- [ ] Update RISK-REGISTER.md with new risk (if applicable)
- [ ] Update ARCHITECTURE-QUICKREF.md / ADR-INDEX.md (if applicable)
- [ ] Add a chaos test to prevent regression

## Lessons learned

<1-2 paragraphs>
```

---

## 6. Pre-built dashboards for incident response

Per `OBSERVABILITY-RUNBOOK.md`:

- **01 System Overview** — SLO compliance, error rates (open first)
- **02 Catalog Service** — if catalog-related
- **03 Inventory Service** — if inventory-related
- **04 Checkout Saga** — if saga-related
- **05 Payment Service** — if payment-related
- **06 Risk Mitigations** — R-XX specific panels
- **07 On-call Essentials** — top 5 panels for on-call

---

## 7. When to call for help

| Situation | Who to call |
|---|---|
| Sev-0 incident | Everyone on call + PM + Architect |
| Sev-1 incident | SRE + Dev Lead |
| Sev-2 bug | File GitHub issue, assign in next sprint |
| Sev-3 bug | Backlog |
| Security incident (any severity) | Security Lead + Legal (per `SECURITY-MODEL.md`) |
| Data loss | SRE + Architect + Legal |
| Compliance violation | Compliance officer + Legal |

---

## 8. Cross-references

- **All risks being monitored:** `RISK-REGISTER.md`
- **Architecture patterns to follow:** `architecture.md`
- **Operational runbook:** `DEVOPS-RUNBOOK.md`
- **Observability + chaos:** `OBSERVABILITY-RUNBOOK.md`
- **Release process:** `RELEASE-PROCESS.md`
- **Security model:** `SECURITY-MODEL.md`
- **Onboarding:** `AGENT-ONBOARDING.md`
- **Glossary:** `GLOSSARY.md`
