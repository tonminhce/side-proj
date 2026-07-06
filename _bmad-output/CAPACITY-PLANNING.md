---
audience: SRE, architect, PM
project: side-project
date: 2026-07-06
how-to-use: how to scale the platform from launch (50k orders/day) to 500k orders/day. Pair with addendum.md §A3.
---

# Capacity Planning — side-project

> **Source:** `addendum.md` §A3 (assumptions). This file = how to scale.
> **Approach:** Plan for 10x launch load. Build in headroom for 2x. Don't over-provision.
> **Bottlenecks to watch:** Saga state recovery time, Outbox bridge, Kafka consumer lag, ES index size, Postgres connection pool.

---

## 1. Launch capacity targets (per addendum A3)

| Metric | Launch | Notes |
|---|---|---|
| Orders / day | 50,000 | `addendum.md` A3 |
| Concurrent checkouts (peak) | 500 | 1% of daily |
| Catalog reads / day | 5,000,000 | 100 reads per order |
| Search queries / day | 100,000 | 2 searches per order |
| Latency p99 — catalog read | < 100ms | NFR-PERF-1 |
| Latency p99 — search | < 300ms | NFR-PERF-2 |
| Latency p99 — checkout | < 800ms | — |
| Availability — checkout | 99.9% | NFR-AVAIL-1 |
| Error budget — checkout | 43.2 min/month | per addendum A3 |

---

## 2. Component-level capacity

### Per-service: instances × CPU × memory

| Service | Launch | 2x | 5x | 10x |
|---|---|---|---|---|
| catalog | 2 × 1 CPU / 1 GB | 4 × 1 CPU / 1 GB | 8 × 2 CPU / 2 GB | 16 × 2 CPU / 2 GB |
| inventory | 2 × 1 CPU / 1 GB | 4 × 1 CPU / 1 GB | 8 × 2 CPU / 2 GB | 16 × 2 CPU / 2 GB |
| cart | 2 × 1 CPU / 512 MB | 4 × 1 CPU / 512 MB | 8 × 1 CPU / 1 GB | 16 × 2 CPU / 1 GB |
| checkout | 2 × 2 CPU / 2 GB | 4 × 2 CPU / 2 GB | 8 × 4 CPU / 4 GB | 16 × 4 CPU / 4 GB |
| payment | 2 × 2 CPU / 1 GB | 4 × 2 CPU / 1 GB | 8 × 4 CPU / 2 GB | 16 × 4 CPU / 2 GB |
| order | 2 × 2 CPU / 2 GB | 4 × 2 CPU / 2 GB | 8 × 4 CPU / 4 GB | 16 × 4 CPU / 4 GB |
| fulfillment | 2 × 1 CPU / 1 GB | 4 × 1 CPU / 1 GB | 8 × 2 CPU / 2 GB | 16 × 2 CPU / 2 GB |
| returns | 1 × 1 CPU / 512 MB | 2 × 1 CPU / 512 MB | 4 × 1 CPU / 1 GB | 8 × 2 CPU / 1 GB |
| customer | 2 × 1 CPU / 1 GB | 4 × 1 CPU / 1 GB | 8 × 2 CPU / 2 GB | 16 × 2 CPU / 2 GB |
| search | 2 × 2 CPU / 2 GB | 4 × 2 CPU / 2 GB | 8 × 4 CPU / 4 GB | 16 × 4 CPU / 4 GB |
| notification | 2 × 1 CPU / 1 GB | 4 × 1 CPU / 1 GB | 8 × 1 CPU / 1 GB | 16 × 2 CPU / 1 GB |
| pricing | 1 × 1 CPU / 512 MB | 2 × 1 CPU / 512 MB | 4 × 1 CPU / 1 GB | 8 × 1 CPU / 1 GB |
| invoice | 1 × 1 CPU / 1 GB | 2 × 1 CPU / 1 GB | 4 × 2 CPU / 2 GB | 8 × 2 CPU / 2 GB |
| **Modulith outbox bridge** | 2 × 2 CPU / 2 GB | 4 × 2 CPU / 2 GB | 8 × 4 CPU / 4 GB | 16 × 4 CPU / 4 GB |

**Total at launch:** ~25 CPU / 18 GB. At 10x: ~250 CPU / 180 GB.

---

## 3. Database capacity (per addendum A3)

### Per-service DBs

| Service | DB size (launch) | DB size (10x) | Connections |
|---|---|---|---|
| catalog | 5 GB | 50 GB | 50 |
| inventory | 10 GB | 100 GB (ledger grows) | 50 |
| cart | 1 GB | 10 GB | 20 |
| checkout | 100 MB (transient) | 1 GB | 20 |
| payment | 1 GB | 10 GB | 20 |
| order | 50 GB (7-year retention) | 500 GB | 100 |
| fulfillment | 10 GB | 100 GB | 50 |
| returns | 1 GB | 10 GB | 20 |
| customer | 5 GB | 50 GB | 50 |
| search | 100 MB | 1 GB | 10 |
| notification | 5 GB | 50 GB | 20 |
| admin | 100 MB | 1 GB | 10 |
| pricing | 100 MB | 1 GB | 10 |
| invoice | 50 GB (7-year tax retention) | 500 GB | 100 |

**Postgres sizing rule:** `max_connections` total = sum of all service connection pool maxes. Use PgBouncer in prod to multiplex connections (1 connection per 10 actual clients).

---

## 4. Kafka capacity

| Topic | Partitions (launch) | Retention | Throughput (events/sec) |
|---|---|---|---|
| `catalog.product.created` | 6 | 7 days | 0.1 (50k/day = 0.6/sec) |
| `catalog.product.updated` | 6 | 7 days | 0.5 |
| `catalog.product.price_changed` | 3 | 7 days | 0.05 |
| `inventory.reserved` | 12 | 3 days | 0.6 |
| `inventory.released` | 12 | 3 days | 0.5 |
| `inventory.allocated` | 6 | 3 days | 0.3 |
| `inventory.shipped` | 6 | 30 days | 0.3 |
| `inventory.adjusted` | 3 | 30 days | 0.01 |
| `cart.line.added` | 12 | 1 day | 5.7 (high rate) |
| `cart.expired` | 6 | 1 day | 0.5 |
| `checkout.started` | 12 | 1 day | 0.6 |
| `checkout.completed` | 12 | 1 day | 0.6 |
| `checkout.compensated` | 6 | 7 days | 0.1 |
| `payment.captured` | 12 | 30 days | 0.6 |
| `payment.refunded` | 6 | 30 days | 0.05 |
| `payment.failed` | 6 | 7 days | 0.1 |
| `payment.disputed` | 3 | 90 days | 0.01 |
| `orders.placed` | 12 | 30 days | 0.6 |
| `orders.amended` | 6 | 7 days | 0.1 |
| `shipment.dispatched` | 6 | 30 days | 0.3 |
| `shipment.delivered` | 6 | 30 days | 0.3 |
| `shipment.exception` | 3 | 30 days | 0.01 |
| `returns.created` | 6 | 7 days | 0.05 |
| `refund.issued` | 3 | 30 days | 0.05 |
| `tax.invoice.issued` | 3 | 7 years (tax) | 0.6 |
| **Total** | **~170 partitions** | | **~13 events/sec** |

### Kafka cluster sizing

| Component | Launch | 5x | 10x |
|---|---|---|---|
| Brokers | 3 (KRaft) | 5 | 7 |
| Total partitions per broker | ~60 | ~50 | ~50 |
| Replication factor | 3 | 3 | 3 |
| Storage per broker | 200 GB | 1 TB | 2 TB |
| Retention per partition | 1-7 days (most) | 1-7 days | 1-7 days |
| Total storage | 600 GB | 5 TB | 14 TB |

---

## 5. Elasticsearch capacity (per addendum A3)

| Index | Size (launch) | Size (10x) | Shards | Replicas |
|---|---|---|---|---|
| `catalog_vi_prod` | 5 GB | 50 GB | 3 | 2 |
| `catalog_en_prod` | 1 GB | 10 GB | 1 | 2 |
| `search_sessions` | 1 GB | 5 GB | 1 | 1 |
| **Total** | **~7 GB** | **~65 GB** | | |

### Per-locale index pattern (per architecture "Elasticsearch read-side")

```yaml
# ES cluster
cluster:
  name: side-project-prod
  nodes: 3 master + 6 data + 3 coordinating
  
# Per-index settings
index:
  number_of_shards: 3  # launch; 5-10 at 10x
  number_of_replicas: 2  # for HA
  refresh_interval: 5s  # near-real-time

# Cluster needs ~3x total data size
# 50 GB data * 3 (primary + 2 replicas) = 150 GB total
```

---

## 6. Redis capacity

Per `CACHING-STRATEGY.md` §4 (memory budget).

| Component | Launch | 10x |
|---|---|---|
| Memory per instance | 1 GB | 4 GB |
| Instances | 1 (single) | 3 (cluster) |
| Total memory | 1 GB | 12 GB |
| Cache hit rate (target) | 70% | 85% |
| Network throughput | 100 MB/s | 1 GB/s |

---

## 7. Network capacity (per addendum A3)

| Path | Launch bandwidth | Notes |
|---|---|---|
| Public → BFF | 500 MB/s | Most traffic; CDN-fronted |
| BFF → Services | 1 GB/s | Internal; aggregated |
| Service → Kafka | 500 MB/s | Multiple producers |
| Kafka → Service | 1 GB/s | Multiple consumers |
| Service → Postgres | 200 MB/s | Per-service DB connections |
| Service → Redis | 100 MB/s | Low-latency |
| Service → ES | 200 MB/s | Read-side |
| Service → Apicurio | 50 MB/s | Schema registry |
| Service → Vault | 10 MB/s | Secret management |
| Service → Stripe | 100 MB/s | External API |
| Service → Carriers | 50 MB/s | External API |

---

## 8. Capacity planning workflow

### Quarterly

1. **Review** current capacity utilization (Grafana dashboards)
2. **Forecast** growth for next quarter (PM input)
3. **Plan** scaling actions for next quarter
4. **Test** scale (load test at 2x current load)
5. **Update** this document

### Annually

1. **Re-architect** if scaling pattern changes (e.g., single-region → multi-region)
2. **Negotiate** vendor contracts (Stripe, AWS, Vault)
3. **Review** database partitioning strategy
4. **Plan** for next major version (v2.0?)

### Load test (per quarter)

```bash
# Use k6 (per INTEGRATION-TEST-CHEATSHEET.md §3)
k6 run --vus 1000 --duration 30m load-test.js
# Target: 2x current production load

# Verify:
# - p99 < SLO
# - error rate < 0.1%
# - outbox drains (no backlog)
# - Kafka consumer lag stays under threshold
```

---

## 9. Auto-scaling strategy

### HPA (Horizontal Pod Autoscaler)

```yaml
# Per-service HPA
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: catalog-prod
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: catalog-prod
  minReplicas: 2
  maxReplicas: 20
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
  behavior:
    scaleUp:
      stabilizationWindowSeconds: 60
    scaleDown:
      stabilizationWindowSeconds: 300  # longer to prevent flapping
```

### VPA (Vertical Pod Autoscaler)

Use for:
- Database (Postgres) — adjust CPU/memory based on load
- Kafka brokers — adjust heap size

### Cluster autoscaler (CA)

- Min nodes: 10
- Max nodes: 50
- Scale-up trigger: any node > 80% CPU for 5 min
- Scale-down trigger: any node < 30% CPU for 15 min

---

## 10. Capacity bottlenecks to watch

### Per-service: bottleneck per load level

| Load level | Likely bottleneck | Mitigation |
|---|---|---|
| 50k/day (launch) | Single replicas (HA) | 2+ replicas per service |
| 100k/day | Kafka consumer lag | Add consumer concurrency |
| 200k/day | ES index size | Add index rollover; archive to cold storage |
| 500k/day | Outbox bridge throughput | Multiple bridges (one per shard) |
| 1M/day | DB write contention | Partition by `customer_id` or `warehouse_id` |
| 5M/day | Kafka storage | Compaction enabled; archive to S3 |
| 10M/day | Network | Multi-region; read replicas |

---

## 11. Cost estimates (per addendum A3 + extrapolated)

### Per-month cost (50k orders/day launch)

| Component | Launch | 5x | 10x |
|---|---|---|---|
| K8s compute | $500 | $2,000 | $4,000 |
| Postgres (managed, 14 instances) | $300 | $1,200 | $2,400 |
| Kafka (managed, 3 brokers) | $200 | $500 | $900 |
| Elasticsearch (3 nodes) | $300 | $1,000 | $2,000 |
| Redis (1 GB) | $50 | $200 | $400 |
| Vault (managed) | $50 | $100 | $200 |
| Apicurio (managed) | $100 | $200 | $400 |
| Stripe fees | $15k (0.3% × $5M GMV) | $75k | $150k |
| SendGrid (1M emails/month) | $200 | $800 | $1,500 |
| Cloud egress | $300 | $1,200 | $2,400 |
| Monitoring (OTel, LGTM) | $200 | $800 | $1,500 |
| **Compute subtotal** | $1,910 | $7,200 | $14,800 |
| **Stripe subtotal** | $15,000 | $75,000 | $150,000 |
| **Total** | ~$17,000/month | ~$82,000/month | ~$165,000/month |

These are rough estimates. Actual costs depend on:
- Cloud provider pricing (AWS, GCP, Azure)
- Negotiated rates
- Reserved instance discounts
- Data transfer patterns

---

## 12. Performance budget (per NFR-PERF)

| Endpoint | p50 | p95 | p99 | SLO breach alert |
|---|---|---|---|---|
| `GET /api/catalog/products` | 30ms | 70ms | 100ms | p99 > 100ms for 10min |
| `GET /api/search/products` | 100ms | 250ms | 300ms | p99 > 300ms for 10min |
| `POST /bff/storefront/checkout` | 200ms | 500ms | 800ms | p99 > 800ms for 10min |
| `POST /api/cart/lines` | 50ms | 150ms | 300ms | p99 > 300ms for 10min |
| `POST /api/payment/webhooks/stripe` | 50ms | 100ms | 200ms | p99 > 200ms for 5min |
| `GET /api/orders/{uuid}/timeline` | 30ms | 80ms | 200ms | p99 > 200ms for 10min |
| `GET /bff/storefront/me` | 20ms | 50ms | 100ms | p99 > 100ms for 10min |

---

## 13. Decision matrix: when to scale

| Signal | Action |
|---|---|
| p99 > SLO for 10 min | Auto-scale HPA (1 more pod) |
| p99 > SLO for 30 min | Investigate; consider code optimization |
| CPU > 70% for 15 min | Auto-scale HPA |
| CPU > 70% for 1 hour | Investigate; consider VPA |
| Outbox backlog > 10k | Add bridge pod |
| Kafka consumer lag > 30s | Add consumer concurrency |
| ES query time > 1s | Add index replicas / cache |
| DB connection saturation | Add PgBouncer or scale out DB |
| Memory pressure (Redis) | Flush rate-limit keys; consider cluster |

---

## 14. Cross-references

- **Launch assumptions:** `addendum.md` §A3 (50k/day baseline)
- **SLO / error budget:** `OBSERVABILITY-RUNBOOK.md` §9
- **Operational runbook:** `DEVOPS-RUNBOOK.md`
- **Redis strategy:** `CACHING-STRATEGY.md`
- **Data model:** `DATA-MODEL.md`
- **API contract:** `API-CONTRACT.md`
- **Onboarding:** `AGENT-ONBOARDING.md`
- **Glossary:** `GLOSSARY.md`
- **Release process:** `RELEASE-PROCESS.md`
