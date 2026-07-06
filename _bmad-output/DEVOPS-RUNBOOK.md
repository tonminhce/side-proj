---
audience: dev, SRE, ops
project: side-project
date: 2026-07-06
how-to-use: practical ops. Local dev setup, common commands, troubleshooting, K8s deploy, Vault config. Copy-paste ready.
---

# DevOps Runbook — side-project

> **Stack (binding):**
> - Java 25 + Maven
> - Kafka 4 (KRaft mode)
> - PostgreSQL 16+ (per-service DB)
> - Redis 7 (rate-limiter + cache)
> - Elasticsearch 8.x (per-locale indices)
> - Apicurio Registry 2.6 (Avro schema)
> - HashiCorp Vault (secrets)
> - Docker + Docker Compose (local)
> - Kubernetes + Helm + ArgoCD (prod)

---

## 1. Local dev setup

### One-time setup

```bash
# 1. Install tools (macOS — replace with apt/dnf for Linux)
brew install openjdk@25 maven docker docker-compose kafkacat

# 2. Clone
git clone https://github.com/your-org/side-project.git
cd side-project

# 3. Start the dev platform
cd dev
docker compose up -d
cd ..

# 4. Verify all 8 services are healthy
docker compose -f dev/docker-compose.yml ps
# Expected: postgres, kafka, redis, elasticsearch, apicurio, minio, mailhog, pgadmin (8 containers)

# 5. Verify util/ builds
mvn -pl util -am clean install
```

### dev/docker-compose.yml services

| Service | Port | Purpose |
|---|---|---|
| postgres | 5432 | Per-service DBs (catalog, inventory, cart, etc.) |
| kafka | 9092, 9093 | KRaft mode, no Zookeeper |
| redis | 6379 | Rate-limiter + cache |
| elasticsearch | 9200 | Per-locale `catalog_vi_<env>`, `catalog_en_<env>` |
| apicurio | 8080 | Avro schema registry |
| minio | 9000, 9001 | S3 substitute for file storage |
| mailhog | 1025, 8025 | SMTP simulator for local dev |
| pgadmin | 5050 | Postgres UI |

### Connection strings

```bash
# Postgres
psql -h localhost -U catalog -d catalog  # all services have a DB by that name

# Kafka
kafkacat -b localhost:9092 -L  # list topics

# Redis
redis-cli -h localhost

# Elasticsearch
curl -u elastic:elastic http://localhost:9200/_cat/indices?v

# Apicurio
open http://localhost:8080  # web UI

# MinIO
open http://localhost:9001  # console login: minioadmin/minioadmin
```

---

## 2. Running a service

### Single service in dev mode

```bash
# Pick a service
cd services/catalog

# Run with dev profile
mvn spring-boot:run \
  -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.jvmArguments="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=5005"

# Service listens on http://localhost:8080
# Remote debug on port 5005
```

### Multiple services (run each in its own terminal)

```bash
# Terminal 1: catalog
cd services/catalog && mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Terminal 2: inventory
cd services/inventory && mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Terminal 3: cart
cd services/cart && mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

### Frontend (Next.js)

```bash
cd frontend/storefront
npm install
npm run dev  # http://localhost:3000

# In another terminal
cd frontend/admin
npm install
npm run dev  # http://localhost:3001
```

---

## 3. Reset state (when things go wrong)

### Reset Kafka

```bash
# Delete all topics
docker exec -it kafka kafka-topics --bootstrap-server localhost:9092 --list
docker exec -it kafka kafka-topics --bootstrap-server localhost:9092 --delete --topic '*'
# Or: full reset (nuclear)
docker compose -f dev/docker-compose.yml down kafka
docker volume rm side-project_kafka-data
docker compose -f dev/docker-compose.yml up -d kafka
```

### Reset Postgres

```bash
# Drop and recreate all service databases
docker exec -it postgres psql -U postgres -c "DROP DATABASE catalog;"
docker exec -it postgres psql -U postgres -c "CREATE DATABASE catalog;"

# Or: full reset (nuclear)
docker compose -f dev/docker-compose.yml down postgres
docker volume rm side-project_postgres-data
docker compose -f dev/docker-compose.yml up -d postgres
```

### Reset Elasticsearch (per-locale indices)

```bash
# Delete and let SearchService re-create
curl -X DELETE 'http://localhost:9200/catalog_vi_dev'
curl -X DELETE 'http://localhost:9200/catalog_en_dev'

# Or: full reset
docker compose -f dev/docker-compose.yml down elasticsearch
docker volume rm side-project_es-data
docker compose -f dev/docker-compose.yml up -d elasticsearch
```

### Reset Redis

```bash
docker exec -it redis redis-cli FLUSHALL
```

### Reset outbox (when Modulith bridge is stuck)

```bash
# The outbox table is per-service. Mark all rows as published.
psql -h localhost -U catalog -d catalog -c "UPDATE outbox SET published_at = now() WHERE published_at IS NULL;"

# Or: full reset
psql -h localhost -U catalog -d catalog -c "TRUNCATE outbox;"
```

### Full platform reset (nuclear)

```bash
cd dev
docker compose down -v  # -v removes volumes
docker compose up -d
# Wait for all 8 services to be healthy
docker compose ps
```

---

## 4. Database operations

### Create a new service database

```sql
-- 1. Connect as postgres superuser
docker exec -it postgres psql -U postgres

-- 2. Create the database + user
CREATE DATABASE <service>_dev;
CREATE USER <service> WITH ENCRYPTED PASSWORD '<service>_dev';
GRANT ALL PRIVILEGES ON DATABASE <service>_dev> TO <service>;

-- 3. Update the service's application.yml
-- spring.datasource.url: jdbc:postgresql://localhost:5432/<service>_dev
-- spring.datasource.username: <service>
-- spring.datasource.password: <service>_dev
```

### Per-database user isolation (production-grade)

In production, each service uses its own database user with restricted permissions:

```sql
-- Catalog service: can read/write its tables only
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO catalog_user;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO catalog_user;

-- InventoryLedger is write-only by InventoryService
GRANT SELECT ON inventory_ledger TO catalog_user;  -- for joins
GRANT INSERT ON inventory_ledger TO inventory_user;  -- only inventory writes
```

### Migration management (Flyway)

```bash
# Create a new migration
touch services/catalog/src/main/resources/db/migration/V002__add_categories.sql
# Edit the file: V002__add_categories.sql
# Flyway auto-runs on app startup

# Verify migrations applied
psql -h localhost -U catalog -d catalog -c "SELECT * FROM flyway_schema_history;"

# Roll back a failed migration (only locally!)
mvn -pl services/catalog flyway:repair
```

---

## 5. Kafka operations

### List topics

```bash
docker exec -it kafka kafka-topics --bootstrap-server localhost:9092 --list
```

### Consume from a topic

```bash
# Read from beginning
docker exec -it kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic catalog.product.created \
  --from-beginning \
  --max-messages 5

# Read with key + value print
docker exec -it kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic catalog.product.created \
  --from-beginning \
  --property print.key=true \
  --property print.timestamp=true
```

### Inspect a topic's schema

```bash
# List Apicurio artifacts
curl -s 'http://localhost:8080/apis/registry/v2/groups/catalog/artifacts' | jq

# Get a specific artifact's content
curl -s 'http://localhost:8080/apis/registry/v2/groups/catalog/artifacts/CatalogProductCreated' | jq
```

### Reset consumer group offset

```bash
# Reset a consumer group to earliest
docker exec -it kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --reset-offsets \
  --to-earliest \
  --group catalog-service \
  --topic catalog.product.created \
  --execute
```

---

## 6. Redis operations

### Inspect rate-limiter state

```bash
# List all keys
redis-cli KEYS '*'

# Inspect a token bucket
redis-cli HGETALL "rate_limit:192.168.1.1:abc123"

# Reset a rate-limit bucket
redis-cli DEL "rate_limit:192.168.1.1:abc123"
```

### Reset Redis fully

```bash
docker exec -it redis redis-cli FLUSHALL
```

---

## 7. Elasticsearch operations

### List indices

```bash
curl -s 'http://localhost:9200/_cat/indices?v'
```

### Search Vietnamese

```bash
# Basic search with Vietnamese analyzer
curl -s -X POST 'http://localhost:9200/catalog_vi_dev/_search?pretty' \
  -H 'Content-Type: application/json' \
  -d '{
    "query": {
      "match": {
        "name": {
          "query": "ao so mi",
          "analyzer": "vi_text"
        }
      }
    }
  }'
```

### Reindex (alias-swap pattern, per NFR-MIG-3)

```bash
# 1. Create new index with new mapping
curl -X PUT 'http://localhost:9200/catalog_vi_dev_v2' -H 'Content-Type: application/json' -d '...new mapping...'

# 2. Reindex from old to new (with dual-write during transition)
curl -X POST 'http://localhost:9200/_reindex?wait_for_completion=false' \
  -H 'Content-Type: application/json' \
  -d '{
    "source": {"index": "catalog_vi_dev"},
    "dest": {"index": "catalog_vi_dev_v2"}
  }'

# 3. Atomic alias swap (zero-downtime)
curl -X POST 'http://localhost:9200/_aliases' -H 'Content-Type: application/json' -d '{
  "actions": [
    {"remove": {"index": "catalog_vi_dev", "alias": "catalog_search"}},
    {"add": {"index": "catalog_vi_dev_v2", "alias": "catalog_search"}}
  ]
}'

# 4. Drop old
curl -X DELETE 'http://localhost:9200/catalog_vi_dev'
```

---

## 8. Vault setup (per ADR-18)

### Local dev: start Vault in dev mode

```bash
# Add to dev/docker-compose.yml (Story 0.4)
docker run -d --name vault -p 8200:8200 \
  -e VAULT_DEV_ROOT_TOKEN_ID=root \
  -e VAULT_DEV_LISTEN_ADDRESS=0.0.0.0:8200 \
  hashicorp/vault:1.15

# Set VAULT_ADDR
export VAULT_ADDR=http://localhost:8200
export VAULT_TOKEN=root

# Enable KV engine (if not auto-enabled)
vault secrets enable -path=secret kv-v2
```

### Seed dev secrets

```bash
# Stripe (test mode)
vault kv put secret/stripe STRIPE_API_KEY=sk_test_xxx STRIPE_WEBHOOK_SECRET=whsec_xxx

# HMAC keys (per-service)
vault kv put secret/events/hmac/catalog-service hmac_key=$(openssl rand -hex 32)
vault kv put secret/events/hmac/inventory-service hmac_key=$(openssl rand -hex 32)
# ... etc per service

# Vietnamese tax authority (Q5 closure)
vault kv put secret/tax/0123456789 tax_authority_token="<encrypted>" reviewed_by="accountant-uuid"

# Postgres credentials (per-service)
vault kv put secret/postgres/catalog db_user=catalog db_password=catalog_dev
# ... etc
```

### App config to use Vault

```yaml
# application.yml (per-service)
spring:
  cloud:
    vault:
      uri: ${VAULT_URL:http://localhost:8200}
      token: ${VAULT_TOKEN:root}
      kv:
        enabled: true
        backend: secret
        default-context: ${service.name}

# Or use Spring Cloud Config Server for more sophisticated setups
```

### Verify Vault access

```bash
vault token lookup
vault kv get secret/stripe
vault kv get secret/events/hmac/catalog-service
```

---

## 9. OTel + LGTM (per ADR-16)

### Local: bring up LGTM via Docker

```bash
# dev/docker-compose.yml includes:
#   - grafana:3000
#   - prometheus:9090
#   - loki:3100
#   - tempo:3200

# Verify
curl -s http://localhost:9090/-/ready
curl -s http://localhost:3000/api/health
```

### View traces in Grafana → Tempo

1. Open http://localhost:3000
2. Go to Explore → Tempo
3. Search by service name (e.g., `service.name=catalog-service`)
4. Pick a trace; see the waterfall of spans

### View logs in Grafana → Loki

1. Open http://localhost:3000
2. Go to Explore → Loki
3. Query: `{service="catalog-service"} |= "error"`

### View metrics in Grafana → Prometheus

1. Open http://localhost:3000
2. Go to Explore → Prometheus
3. Query: `histogram_quantile(0.99, rate(http_server_requests_seconds_bucket{service="catalog-service"}[5m]))`

### Provisioned dashboards

Dashboards are provisioned from `platform/observability/grafana-dashboards/*.json` (Story 0.4). Edit there, not in the Grafana UI.

---

## 10. K8s deployment (per ADR-17)

### Chart structure (per service)

```
helm/
├── catalog/
│   ├── Chart.yaml
│   ├── values.yaml
│   ├── values-staging.yaml
│   ├── values-prod.yaml
│   └── templates/
│       ├── deployment.yaml
│       ├── service.yaml
│       ├── configmap.yaml
│       ├── secret.yaml           # references Vault
│       └── hpa.yaml              # horizontal pod autoscaler
├── inventory/
├── ... (one per service)
└── umbrella/                        # top-level chart that depends on all services
    ├── Chart.yaml
    └── values.yaml
```

### Deploy to staging

```bash
# 1. Set K8s context
kubectl config use-context staging-cluster

# 2. Apply secrets (from Vault, not in git)
kubectl apply -f k8s/secrets/staging-secrets.yaml

# 3. Deploy via Helmfile or ArgoCD
argocd app sync catalog-staging
# Or: helmfile apply

# 4. Verify
kubectl get pods -n side-project
kubectl logs -n side-project deploy/catalog-staging --tail=50
```

### Roll back a bad deploy

```bash
# ArgoCD
argocd app rollback catalog-staging

# Helm
helm history catalog -n side-project
helm rollback catalog 2 -n side-project  # to revision 2
```

---

## 11. Common troubleshooting

### Service won't start

```bash
# 1. Check logs
docker logs <container> 2>&1 | tail -100

# 2. Check if DB is reachable
psql -h localhost -U catalog -d catalog -c "SELECT 1;"

# 3. Check if Kafka is up
docker exec -it kafka kafka-topics --bootstrap-server localhost:9092 --list

# 4. Check Vault
curl -s $VAULT_ADDR/v1/sys/health | jq

# 5. Check port conflict
lsof -i :8080
```

### Outbox is stuck (events not publishing)

```bash
# 1. Check outbox table
psql -h localhost -U catalog -d catalog -c "SELECT COUNT(*), MAX(created_at) FROM outbox;"

# 2. Check Modulith bridge logs
docker logs catalog-service 2>&1 | grep -i "outbox\|bridge"

# 3. Check Kafka connectivity
docker exec -it catalog-service curl -s http://kafka:9092
```

### Consumer not processing events

```bash
# 1. Check consumer group lag
docker exec -it kafka kafka-consumer-groups --bootstrap-server localhost:9092 --describe --group catalog-service

# 2. Check processed_event table
psql -h localhost -U catalog -d catalog -c "SELECT COUNT(*) FROM processed_event;"

# 3. Check service logs for the consumer
docker logs catalog-service 2>&1 | grep -i "consumer\|processed\|hmac"
```

### Card-testing in dev (rate-limiter false positives)

```bash
# Whitelist your IP for dev
redis-cli SADD "rate_limit:whitelist" "192.168.1.1"
# (in production this is managed via OPA, not Redis directly)
```

### Vietnamese search returns no results

```bash
# 1. Verify the analyzer is configured
curl -s 'http://localhost:9200/catalog_vi_dev/_settings?pretty' | grep analyzer

# 2. Test the analyzer directly
curl -s -X POST 'http://localhost:9200/catalog_vi_dev/_analyze?pretty' \
  -H 'Content-Type: application/json' \
  -d '{"analyzer": "vi_text", "text": "áo sơ mi"}'

# 3. If the analyzer is missing, restart SearchService to re-create the index
```

---

## 12. Performance + capacity

### Latency targets (per NFR-PERF)

| Service | p50 | p95 | p99 |
|---|---|---|---|
| Catalog read | < 30ms | < 70ms | < 100ms |
| Search query | < 100ms | < 250ms | < 300ms |
| Cart read | < 50ms | < 150ms | < 300ms |
| Checkout start | < 200ms | < 500ms | < 800ms |
| Payment (Stripe API) | < 1s | < 2s | < 3s |

### Capacity targets (per addendum A3)

- **50,000 orders/day** at p95 < 300ms checkout
- 500 concurrent checkouts at peak
- 5,000,000 catalog reads/day
- 100,000 search queries/day

These are working assumptions from UJ-3 (Mai the SRE). Load testing in architecture phase validates or refutes.

---

## 13. CI/CD (per ADR-17)

### PR pipeline

```yaml
# .github/workflows/pr.yml
name: PR
on: [pull_request]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '25' }
      - name: Build
        run: mvn -B clean install -DskipTests
      - name: Unit tests
        run: mvn -B test
      - name: Integration tests
        run: mvn -B verify -P integration-tests
        env:
          TESTCONTAINERS_RYUK_DISABLED: 'true'
      - name: Archunit
        run: mvn -B test -Dtest='*ArchUnit*'
      - name: Avro compat check
        run: ./scripts/check-avro-compat.sh
      - name: Lint (Spotless + Prettier)
        run: mvn -B spotless:check && (cd frontend && npm run lint)
      - name: Build Docker images
        run: ./scripts/build-images.sh
```

### Deploy pipeline

```yaml
# .github/workflows/deploy.yml
name: Deploy
on:
  push:
    branches: [main]
jobs:
  deploy-staging:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Deploy to staging via ArgoCD
        run: argocd app sync side-project-staging
```

---

## 14. Health checks + SLOs

| Service | liveness probe | readiness probe | SLO |
|---|---|---|---|
| catalog | `GET /actuator/health/liveness` | `GET /actuator/health/readiness` | p99 < 100ms |
| inventory | same | same | p99 < 200ms |
| cart | same | same | p99 < 300ms |
| payment | same | same | p99 < 800ms |

---

## 15. Disaster recovery

### Backup cadence

| Resource | Frequency | Retention | Storage |
|---|---|---|---|
| Postgres (per-service) | Daily snapshot | 7 days | S3 (MinIO in dev) |
| Elasticsearch indices | Daily snapshot | 7 days | S3 |
| Kafka topics | Mirror Maker 2 (live) | Indefinite | S3 archive (nightly) |
| Vault | Daily snapshot | 30 days | Encrypted S3 |

### Recovery procedures (Story 10 hardening)

```bash
# 1. Postgres recovery
./scripts/restore-postgres.sh --service catalog --from-snapshot 2026-07-01

# 2. Elasticsearch recovery
curl -X POST 'http://es:9200/_snapshot/snapshot_20260701/_restore'

# 3. Vault recovery
vault operator raft snapshot restore /path/to/snapshot
```

---

## 16. Useful one-liners

```bash
# Find which service owns a Kafka topic
docker exec -it kafka kafka-topics --bootstrap-server localhost:9092 --describe --topic <topic>

# Find which consumer is lagging
docker exec -it kafka kafka-consumer-groups --bootstrap-server localhost:9092 --describe --all-groups | awk '$5 > 1000'

# Check all services' health at once
for svc in catalog inventory cart checkout payment; do
  echo -n "$svc: "
  curl -sf http://localhost:8080/actuator/health 2>/dev/null && echo "UP" || echo "DOWN"
done

# Tail logs from all services
docker compose -f dev/docker-compose.yml logs -f --tail=100

# Tail OTel collector output
docker logs otel-collector 2>&1 | grep -E "(trace|metric)"

# Watch the dev platform's CPU/memory
docker stats

# Reset everything and re-seed (most thorough)
cd dev && docker compose down -v && docker compose up -d
# Wait for healthy
./scripts/wait-for-healthy.sh
# Re-seed dev data
./scripts/seed-dev-data.sh
```
