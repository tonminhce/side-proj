---
audience: release-manager, SRE, PM, architect
project: side-project
date: 2026-07-06
how-to-use: pre-launch procedure for v1.0.0. Sequential checklist.
---

# Release Process — v1.0.0

> **Goal:** Ship v1.0.0 to production with all 15 risks mitigated and verified.
> **Sequential checklist** — each step is gated by the previous. Do not skip.
> **RTO target:** 30 min rollback if v1.0 breaks in production.

---

## 1. Pre-release checklist (T-2 weeks)

### Code complete

- [ ] All 11 sprints done (Sprint 0..10)
- [ ] All 57 stories marked `done` in `sprint-status.yaml`
- [ ] No `in_progress` or `review` stories left

### Tests complete

- [ ] Unit tests pass 100% (`mvn test`)
- [ ] Integration tests pass 100% (`mvn verify -Dit.test=*IT`)
- [ ] Critical-path tests pass 100x consecutively:
  - `InventoryReservationServiceIT#hundredConcurrentReservationsYieldsOneSuccess`
  - `PaymentServiceIT#idempotencyKeyIsReusedAcrossRetries`
  - `ReturnsServiceIT#cumulativeRefundSafetyBlocks`
  - `AuthServiceIT#accountLockoutAfter5FailedLogins`
- [ ] End-to-end saga test passes (`SagaE2EIT`)

### Documentation complete

- [ ] All 19 quickref docs current
- [ ] Architecture + PRD + epics at `reviewCycle: 5` (or higher)
- [ ] Sprint 0 story (R-01) verified in production-like env
- [ ] All ADRs documented (26 total)
- [ ] All R-XX risks have `Verified by: <Story ID>` in `RISK-REGISTER.md`

### Risk verification

- [ ] All 7 P0 risks verified by chaos test (R-02, R-03, R-04, R-05, R-06, R-08, R-15)
- [ ] All 3 High risks verified (R-07, R-09, R-13)
- [ ] All Medium risks documented with mitigation (R-10, R-11, R-12, R-14)
- [ ] 1 chaos game day completed (per `OBSERVABILITY-RUNBOOK.md` §8)

### Performance

- [ ] Catalog p99 < 100ms (NFR-PERF-1) verified at 50k orders/day load
- [ ] Search p99 < 300ms (NFR-PERF-2) verified at 50k orders/day load
- [ ] Checkout availability 99.9% (NFR-AVAIL-1) verified at 50k orders/day load
- [ ] All error budgets within tolerance (per `OBSERVABILITY-RUNBOOK.md` §9)

### Security

- [ ] Pen test completed (R-15, R-05, AT-02 verified)
- [ ] OWASP dependency check clean
- [ ] Container image scan clean (Trivy)
- [ ] License check clean
- [ ] Vault policies reviewed (per ADR-18)

### Compliance

- [ ] Vietnam PDPD data export tested (per FR-46)
- [ ] Vietnamese tax-invoice cycle tested (per FR-78, LC-03)
- [ ] R2F (right to be forgotten) tested (per FR-49)

---

## 2. Staging deployment (T-1 week)

### Deploy to staging

```bash
# 1. Set K8s context
kubectl config use-context staging-cluster

# 2. Apply namespace + secrets (from Vault)
kubectl apply -f k8s/staging/namespace.yaml
kubectl apply -f k8s/staging/secrets.yaml  # generated from Vault

# 3. Deploy via ArgoCD
argocd app sync side-project-staging

# 4. Verify all services healthy
kubectl get pods -n side-project
# All 14 services + 2 BFFs + 2 frontends should be Running + Ready

# 5. Run smoke test
./scripts/smoke-test.sh staging
# Should pass all ~50 endpoint checks
```

### Soak test (T-1 week)

```bash
# Run k6 at 2x expected production load
k6 run --vus 1000 --duration 24h - <<EOF
import http from 'k6/http';
export default function () {
    http.get('https://staging.example.com/api/catalog/products');
    http.post('https://staging.example.com/api/cart/lines', JSON.stringify({...}), {headers: {'Content-Type': 'application/json'}});
    sleep(Math.random() * 5);
}
EOF

# Monitor for:
# - Memory leaks (should plateau within 4h)
# - Slow drift in p99 (should be stable)
# - Error rate > 0.1% (alert)
# - Outbox backlog > 1k (alert)
```

### Bug bash (T-1 week)

- Run a 2-day internal bug bash
- Invite engineering + SRE + PM + a few friendly users
- Track all bugs in `v1.0-bugs` GitHub label
- Severity-0/1 bugs must be fixed before release
- Severity-2 bugs: defer to v1.1
- Severity-3 bugs: accept for v1.0

---

## 3. Pre-launch checklist (T-1 day)

### Final checks

- [ ] All 7 P0 risks verified in staging
- [ ] All critical-path tests passed 100x consecutively in staging
- [ ] Staging has been running for 7+ days without critical incident
- [ ] No open Sev-0/1 bugs
- [ ] All ADRs reviewed and approved
- [ ] Documentation links all work (no 404s)
- [ ] Secrets in Vault (not in repo)
- [ ] DNS records point to staging
- [ ] On-call rotation set for launch day

### Pre-launch comms

- [ ] Status page entry created (per `OBSERVABILITY-RUNBOOK.md` §7)
- [ ] Slack `#side-project-launch` channel active
- [ ] Launch comms drafted (email, blog, social)
- [ ] Internal team briefed: "Launch is tomorrow 10am UTC"

### Team on standby

- [ ] Architect on call
- [ ] Dev Lead on call
- [ ] SRE on call
- [ ] PM on standby
- [ ] Security Lead on standby
- [ ] Rollback plan reviewed (per `DEVOPS-RUNBOOK.md` §11)

---

## 4. Launch day (T-0)

### 10:00 UTC — Go-live

```bash
# 1. Final pre-flight
./scripts/pre-flight-checklist.sh
# Confirms: 14 services healthy, 2 BFFs healthy, 2 frontends healthy, all critical alerts clear

# 2. DNS flip (ArgoCD sync to prod cluster)
kubectl config use-context prod-cluster
argocd app sync side-project-prod

# 3. Verify prod
kubectl get pods -n side-project
# All 18 deployments should reach Ready

# 4. Smoke test prod
./scripts/smoke-test.sh prod
# Should pass all ~50 endpoint checks

# 5. Announce launch
# Slack: "#side-project-launch is LIVE"
# Email: send to mailing list
# Blog: publish
```

### Hour 0-1: Tight monitoring

- Watch dashboards continuously
- SRE on call responds to any alert within 5 min
- Don't make any code changes during this hour unless P0 incident

### Hour 1-24: Active monitoring

- SRE continues to monitor
- Check p99 latencies every hour
- Check error rates every hour
- Respond to user feedback

---

## 5. Post-launch (T+1 to T+30 days)

### Daily (T+1 to T+7)

- Check SLO compliance (per `OBSERVABILITY-RUNBOOK.md` §9)
- Review top 3 alerts of the day
- Triage any user-reported bugs

### Weekly (T+7 to T+30)

- Sprint retrospective on the launch
- Update `RISK-REGISTER.md` with any new risks that emerged
- Add any new ADRs to `architecture.md`
- Plan v1.1 sprint backlog

### Monthly (T+30+)

- Run a chaos game day
- Review error budget burn
- Update `addendum.md` §A3 (capacity assumptions) with real numbers
- Plan next major release (v2.0?)

---

## 6. Rollback procedure (RTO: 30 min)

### When to rollback

- Critical user impact (e.g., checkout success rate < 95%)
- Data corruption detected
- Security incident
- Error rate spike to >5% for >5 minutes

### How to rollback

```bash
# 1. Announce rollback
# Slack: "Rolling back v1.0.0 due to <reason>"

# 2. Roll back via ArgoCD
argocd app rollback side-project-prod --revision <previous-good-revision>
# ArgoCD automatically rolls back all 18 deployments to previous version

# 3. Verify rollback
kubectl get pods -n side-project
# All should reach Ready with previous-version images

# 4. Smoke test
./scripts/smoke-test.sh prod
# Should pass

# 5. Announce rollback complete
# Slack: "v1.0.0 rolled back; v0.9.x restored"

# 6. Post-mortem (within 24h)
# Use bmad-retrospective workflow
# Update RISK-REGISTER.md with new risks
# Update architecture if needed
```

### Rollback verification

After rollback:
- [ ] All services healthy
- [ ] Smoke test passes
- [ ] Error rate < 0.5%
- [ ] No customer reports
- [ ] On-call rotation continues for monitoring

---

## 7. v1.0 → v1.1 transition

After 2-4 weeks of stable v1.0:

1. **Triage** all v1.0-era bug reports
2. **Categorize** into v1.1 (high-priority fixes) vs v1.2 (improvements) vs deferred
3. **Plan** v1.1 sprint (1-2 weeks, focus on critical bug fixes + minor improvements)
4. **Release** v1.1 with full regression + chaos test cycle
5. **Communicate** changelog to users

---

## 8. Launch-day commands cheatsheet

```bash
# Pre-flight
./scripts/pre-flight-checklist.sh

# Deploy
argocd app sync side-project-prod

# Health check
kubectl get pods -n side-project
./scripts/smoke-test.sh prod

# Rollback
argocd app rollback side-project-prod --revision <revision>

# Scale
kubectl scale deployment/catalog-prod -n side-project --replicas=10

# Logs
kubectl logs -n side-project -l app=catalog-prod --tail=100 -f

# Debug specific
kubectl port-forward -n side-project svc/catalog-prod 8080:8080
```

---

## 9. Cross-references

- **Sprint status:** `_bmad-output/implementation-artifacts/sprint-status.yaml`
- **On-call runbooks:** `OBSERVABILITY-RUNBOOK.md` §7 (alerts)
- **Chaos experiments:** `OBSERVABILITY-RUNBOOK.md` §8
- **Risk register:** `RISK-REGISTER.md` (15 risks with mitigations)
- **Architecture:** `architecture.md`
- **Operational runbook:** `DEVOPS-RUNBOOK.md`
- **Security model:** `SECURITY-MODEL.md`
- **Story automator:** `STORY-AUTOMATOR-CHEATSHEET.md`
- **Onboarding:** `AGENT-ONBOARDING.md`
