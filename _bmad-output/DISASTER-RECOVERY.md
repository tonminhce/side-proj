---
audience: SRE, ops, on-call
project: side-project
date: 2026-07-06
how-to-use: disaster scenarios + RTO/RPO + recovery procedures. Pair with DEVOPS-RUNBOOK.md + BUG-TRIAGE.md.
---

# Disaster Recovery — side-project

> **Definition:** Disaster = any event that causes significant service degradation or data loss, requiring recovery beyond normal ops.
> **RTO** (Recovery Time Objective): how long to restore service.
> **RPO** (Recovery Point Objective): how much data loss is acceptable.

---

## 1. RTO / RPO per resource

| Resource | RTO | RPO | Strategy |
|---|---|---|---|
| **Postgres (per-service DB)** | 30 min | 1 hour | Daily snapshot + WAL streaming |
| **Kafka topics** | 1 hour | 5 min | Mirror Maker 2 (live replication) |
| **Elasticsearch indices** | 30 min | 1 hour | Daily snapshot (S3) |
| **Redis** | 5 min | 0 (ephemeral) | Cache rebuild from source-of-truth |
| **Vault** | 30 min | 24 hours | Daily snapshot to encrypted S3 |
| **Apicurio schemas** | 30 min | 0 (Git) | Git + CI |
| **Application code (Git)** | 5 min | 0 (Git) | GitHub + GHCR + ArgoCD |
| **K8s manifests (Git)** | 5 min | 0 (Git) | GitHub + ArgoCD |
| **Container images** | 5 min | 0 (registry) | GHCR + retention |
| **VN tax invoices (PDFs)** | 1 hour | 24 hours | Daily backup to S3 |

---

## 2. Disaster scenarios + recovery procedures

### Scenario 1: Single region outage (AWS Singapore region down)

**Impact:** All production traffic affected.

**RTO:** 30 min (time to spin up new region)
**RPO:** 5 min (replication lag)

#### Recovery procedure

```bash
# 1. Confirm outage
aws ec2 describe-instance-status --region ap-southeast-1
# If status: impaired, region is degraded

# 2. Activate DR region
terraform apply -var-file=dr.tfvars -auto-approve
# Provisions K8s cluster in ap-northeast-1

# 3. Restore data
# 3a. Postgres (per-service)
for svc in catalog inventory cart checkout payment order fulfillment returns customer search notification admin pricing invoice; do
    ./scripts/restore-postgres.sh --service $svc --from-snapshot latest
done

# 3b. Kafka (topics already mirrored, just check)
./scripts/check-kafka-mirrors.sh

# 3c. Elasticsearch (from S3 snapshot)
for idx in catalog_vi_prod catalog_en_prod; do
    curl -X POST "es-dr:9200/_snapshot/snap_$idx/_restore?wait_for_completion=true"
done

# 3d. Vault (from S3 snapshot)
vault operator raft snapshot restore /snapshots/vault-$(date +%F).snap

# 4. Update DNS
aws route53 update-hosted-zone-records --hosted-zone-id Z123 \
    --change-batch file://failover-to-dr.json
# Failover to DR region

# 5. Verify
./scripts/smoke-test.sh dr
# All ~50 endpoint checks should pass
```

---

### Scenario 2: Postgres corruption (per-service DB)

**Impact:** Specific service data lost. Other services unaffected.

**RTO:** 30 min
**RPO:** 1 hour (daily snapshot) OR 5 min (WAL streaming) depending on timing

#### Recovery procedure

```bash
# 1. Identify the affected DB
kubectl get pods -n side-project -l app=catalog-prod
# If catalog-prod is healthy but DB corrupt, see the PG logs

# 2. Stop writes to the affected DB
# (Scale the service to 0 replicas to prevent new writes)
kubectl scale deployment/catalog-prod -n side-project --replicas=0

# 3. Identify the latest good snapshot
# List available snapshots
aws rds describe-db-snapshots --db-instance-identifier catalog-prod
# Find the most recent snapshot BEFORE the corruption

# 4. Restore from snapshot (option A: full restore)
./scripts/restore-postgres.sh --service catalog --from-snapshot $(date -d "1 hour ago" +%Y-%m-%d-%H)

# OR option B: point-in-time recovery
aws rds restore-db-instance-to-point-in-time \
    --source-db-instance-identifier catalog-prod \
    --target-db-instance-identifier catalog-prod-dr \
    --restore-time 2026-07-06T10:00:00Z

# 5. Switch over (if using option B)
kubectl set env deployment/catalog-prod DB_HOST=catalog-prod-dr
kubectl scale deployment/catalog-prod -n side-project --replicas=2

# 6. Verify
./scripts/smoke-test.sh catalog
psql -c "SELECT COUNT(*) FROM products;"
# Should match pre-corruption count (or pre-PIT time)

# 7. Post-mortem (within 24h)
```

---

### Scenario 3: Kafka broker loss (1 of 3 brokers dies)

**Impact:** Brief unavailability (~30s) while controller re-elects. No data loss (replication factor 3).

**RTO:** 30 sec (auto)
**RPO:** 0 (replicated)

#### Recovery procedure

```bash
# KRaft auto-elects new controller
# No action needed; verify
docker exec -it kafka kafka-metadata-quorum --bootstrap-server localhost:9092 --status

# If a broker is permanently lost, replace it
kubectl apply -f k8s/kafka-broker-3.yaml
# New broker joins, partitions rebalance
```

---

### Scenario 4: Kafka cluster total loss (catastrophic)

**Impact:** All events lost. Events are in `outbox` table on each service; consumers can't proceed.

**RTO:** 1 hour
**RPO:** 0 (events are still in service outbox tables; replay them)

#### Recovery procedure

```bash
# 1. Bring up new Kafka cluster (per scenario 1)

# 2. Each service's outbox table has events that weren't published
# Replay from each service's outbox
for svc in catalog inventory cart checkout payment order fulfillment returns customer search notification admin pricing invoice; do
    echo "Replaying outbox for $svc"
    psql -h localhost -U $svc -d $svc -c "SELECT id, event_type, payload FROM outbox WHERE published_at IS NULL ORDER BY id;"
done

# 3. Publish all unpublished events
# (per service's outbox replay tool)
./scripts/replay-outbox.sh --service catalog
./scripts/replay-outbox.sh --service inventory
# ... etc

# 4. Verify consumers
# (each service's processed_event table should now have those events)
```

---

### Scenario 5: Container image registry (GHCR) outage

**Impact:** Cannot pull new images. Existing pods continue to run.

**RTO:** 5 min (use cached images; GHCR mirrors to docker.io)
**RPO:** 0 (images already in cluster)

#### Recovery procedure

```bash
# 1. Check GHCR status
curl -s https://status.github.com/api/v2/status.json | jq '.statuses[] | select(.name | contains("Container"))'

# 2. If GHCR is down, configure cluster to use cached images
# Update image pull policy to IfNotPresent
kubectl set image deployment/catalog-prod -n side-project catalog=ghcr.io/your-org/catalog:v1.0.0 --local

# 3. Or use mirror registry
kubectl set image deployment/catalog-prod -n side-project catalog=docker.io/your-org/catalog:v1.0.0
```

---

### Scenario 6: GitHub outage (source control + CI)

**Impact:** Cannot merge PRs, no CI runs. Existing deploys continue.

**RTO:** 5 min (CDN-served mirror)
**RPO:** 0 (Git is the source of truth)

#### Recovery procedure

```bash
# 1. Check GitHub status
# (GitHub has multi-region)

# 2. If GitHub is down, use mirror
# Configure GitHub Actions to mirror to alternate (e.g., GitLab)
# (set up at architecture time)

# 3. ArgoCD can pull from local cache
# (already configured per DEVOPS-RUNBOOK)
```

---

### Scenario 7: Vault loss (secrets)

**Impact:** All services lose access to secrets. Service startup fails (per ADR-18).

**RTO:** 30 min (from snapshot)
**RPO:** 24 hours (last snapshot)

#### Recovery procedure

```bash
# 1. Restore Vault from snapshot
vault operator raft snapshot restore /snapshots/vault-$(date +%F).snap

# 2. Verify
vault status
vault kv list secret/stripe
vault kv list secret/events/hmac/

# 3. Restart services to re-fetch secrets
for svc in catalog inventory cart checkout payment order fulfillment returns customer search notification admin pricing invoice; do
    kubectl rollout restart deployment/$svc-prod -n side-project
done

# 4. Verify
./scripts/smoke-test.sh
# Should pass; secrets are restored
```

---

### Scenario 8: Stripe API outage

**Impact:** All checkouts fail.

**RTO:** N/A (we can't fix Stripe; can only wait)
**RPO:** N/A (no data loss)

#### Recovery procedure

```bash
# 1. Confirm Stripe is having issues
# https://status.stripe.com

# 2. Enable checkout kill switch (per FEATURE-FLAGS.md)
vault kv put secret/ff/feature.kill-switch.checkout value="true"
kubectl rollout restart deployment/checkout-prod -n side-project

# 3. Show "We're experiencing payment processing issues" banner
# (already handled by FR-38 carrier-degraded pattern)

# 4. Wait for Stripe to recover
# (we'll get webhook events from Stripe when it does)

# 5. Disable kill switch
vault kv put secret/ff/feature.kill-switch.checkout value="false"
kubectl rollout restart deployment/checkout-prod -n side-project
```

---

### Scenario 9: Vietnamese Tax Authority API outage

**Impact:** Daily invoice batch fails. Invoices are generated but not submitted to tax authority.

**RTO:** N/A (we can't fix tax authority; can only retry)
**RPO:** Up to 24 hours (until next batch)

#### Recovery procedure

```bash
# 1. Confirm tax authority API is down
# (per their status page)

# 2. Continue generating invoices locally
# (invoices are in our DB; just not submitted)

# 3. Retry batch hourly
./scripts/invoice-batch.sh --retry
# Cron: 0 * * * * (every hour)

# 4. When tax authority recovers, batch succeeds
```

---

### Scenario 10: ELT miss (events lost between service → outbox → Kafka)

**Impact:** Specific event(s) lost. Subscribers in inconsistent state.

**RTO:** Manual replay
**RPO:** 0 (data is in outbox)

#### Recovery procedure

```bash
# 1. Identify missing events
# Compare events emitted in last 24h vs events consumed
psql -h localhost -U catalog -d catalog -c "SELECT COUNT(*) FROM outbox WHERE published_at IS NULL;"
# If count > 0, bridge is broken or events are pending

# 2. Check bridge
kubectl logs -n side-project -l app=modulith-bridge | grep -E "(error|outbox)"

# 3. Restart bridge
kubectl rollout restart deployment/modulith-bridge -n side-project

# 4. Verify outbox drains
psql -h localhost -U catalog -d catalog -c "SELECT COUNT(*) FROM outbox WHERE published_at IS NULL;"
# Should be 0 within 1 min
```

---

## 3. Backup strategy (per `DEVOPS-RUNBOOK.md` §15)

### Automated backups

| Resource | Frequency | Retention | Storage |
|---|---|---|---|
| Postgres (per-service) | Daily snapshot + WAL streaming | 7 days snapshots, 24h WAL | S3 with lifecycle |
| Elasticsearch | Daily snapshot | 7 days | S3 |
| Kafka | Mirror Maker 2 (live) | Indefinite | S3 archive (nightly compaction) |
| Vault | Daily snapshot | 30 days | Encrypted S3 |
| Application code | Continuous (Git) | Indefinite | GitHub |
| Container images | On push | 90 days | GHCR |

### Backup verification (quarterly)

```bash
# Test restore: pick a random snapshot, restore to a test cluster, verify data
for svc in catalog inventory cart checkout payment order fulfillment returns customer search notification admin pricing invoice; do
    ./scripts/test-restore.sh --service $svc --snapshot $(date -d "1 day ago" +%Y-%m-%d)
done
```

---

## 4. Disaster recovery drills (per `OBSERVABILITY-RUNBOOK.md` §8)

### Quarterly (Q1, Q2, Q3, Q4)

| Drill | Scenario | Participants | Success criteria |
|---|---|---|---|
| Q1 | Single service failure | SRE + Dev team | Recovery < 30 min |
| Q2 | Single region failure | SRE + Architect + PM | Recovery < 2 hours |
| Q3 | Full data loss (per-service) | SRE + Dev team | Recovery < 1 hour |
| Q4 | All infrastructure (K8s + DB + Kafka) | SRE + Architect | Recovery < 4 hours |

### Drill report

After each drill, write a brief report:

```markdown
# DR Drill Report — YYYY-MM-DD

**Scenario:** [which scenario]
**Time to recover:** [X min]
**Issues encountered:** [list]
**Action items:** [list with owners]
**Score:** [Pass / Partial / Fail]
```

---

## 5. Roles and responsibilities during DR

| Role | Person/team | Responsibility |
|---|---|---|
| **Incident commander** | On-call SRE (per `BUG-TRIAGE.md`) | Coordinates recovery |
| **Database lead** | DBA / SRE | Restores DBs from snapshots |
| **Application lead** | Dev team | Restarts services, verifies |
| **Network lead** | SRE | DNS changes, traffic reroute |
| **Communications** | PM | User-facing status updates |
| **Escalation** | Architect | If > 1 hour to recover |

---

## 6. Communication during DR

### Internal (Slack `#side-project-oncall`)

```
🔴 DISASTER RECOVERY in progress — side-project prod

What: [one-sentence]
Impact: [X% of users affected]
RTO target: [X min]
Started: [timestamp UTC]
Status: [recovering / monitoring / resolved]
Next update: [in 15 min]

Incident commander: @<name>
```

### External (status page + email to high-value customers)

```
🔴 Service Incident — side-project

We're currently experiencing [brief description].
Impact: [X% of users / specific features]
Started: [timestamp]
Status: investigating / identified / mitigated / resolved
Next update: [in 30 min]
```

---

## 7. Post-drill improvements

After each drill, capture:
- What went well
- What didn't
- Action items with owners + due dates
- Update this document with learnings

Common improvements:
- **RTO didn't meet target**: add more replicas; add automated runbook
- **RPO missed window**: increase snapshot frequency
- **Manual steps that should be automated**: script them
- **Communication delayed**: improve status page automation

---

## 8. Cross-references

- **Operational runbook:** `DEVOPS-RUNBOOK.md` §15 (backups)
- **Release + rollback:** `RELEASE-PROCESS.md` §6
- **Bug triage:** `BUG-TRIAGE.md`
- **Chaos experiments (validate DR scenarios):** `OBSERVABILITY-RUNBOOK.md` §8
- **Capacity planning:** `CAPACITY-PLANNING.md`
- **SLO / error budget:** `OBSERVABILITY-RUNBOOK.md` §9
- **Glossary:** `GLOSSARY.md`
- **Onboarding:** `AGENT-ONBOARDING.md`
