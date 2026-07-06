---
audience: any-dev-getting-the-dev-platform-running
project: side-project
date: 2026-07-06
how-to-use: 30-minute checklist to get dev platform up. Lighter than SPRINT-0-ONBOARDING.md.
---

# Local Dev Setup — 30-min Checklist

> **Goal:** Get the dev platform running + util/ building in 30 minutes. For first-time setup. For Sprint 0 specific tasks (R-01 fix, monorepo bootstrap, etc.), see `SPRINT-0-ONBOARDING.md`.

---

## 0. Prerequisites (5 min)

| Tool | Version | Install | Verify |
|---|---|---|---|
| Java | 25 (LTS) | `brew install openjdk@25` or `sdkman install java 25-tem` | `java --version` |
| Maven | 3.9+ | `brew install maven` | `mvn --version` |
| Docker | 24+ | [docker.com](https://docker.com) | `docker --version` |
| Docker Compose | v2 | (Bundled with Docker Desktop) | `docker compose version` |
| Git | 2.40+ | `brew install git` | `git --version` |

If you don't have these, ask your lead. On macOS, you can also use `dev-bootstrap.sh` (per platform) which auto-installs.

---

## 1. Clone the repo (2 min)

```bash
git clone https://github.com/your-org/side-project.git
cd side-project
```

Verify you got the right version:

```bash
ls
# Should see: pom.xml, util/, dev/, services/, bff/, frontend/, platform/, helm/, docs/, _bmad-output/

cat _bmad-output/AGENT-ONBOARDING.md | head -10
# 2-min skim — get oriented
```

---

## 2. Bring up the dev platform (5 min)

The dev platform = 8 containers (Postgres, Kafka, Redis, ES, Apicurio, MinIO, MailHog, pgAdmin).

```bash
cd dev
docker compose up -d
# Wait ~60 seconds for all services to be healthy
docker compose ps
```

Expected output (8 services, all "healthy"):

```
NAME                STATUS              PORTS
postgres            Up (healthy)       5432
kafka               Up (healthy)       9092, 9093
redis               Up (healthy)       6379
elasticsearch       Up (healthy)       9200
apicurio            Up (healthy)       8080
minio               Up (healthy)       9000, 9001
mailhog             Up (healthy)       1025, 8025
pgadmin             Up (healthy)       5050
```

### Quick verification (1 min)

```bash
# Postgres
psql -h localhost -U catalog -d catalog -c "SELECT 1;"  # password: catalog

# Kafka
docker exec -it kafka kafka-topics --bootstrap-server localhost:9092 --list

# Redis
redis-cli ping  # → PONG

# Elasticsearch
curl -s http://localhost:9200/ | jq -r .version.number

# Apicurio
curl -s 'http://localhost:8080/apis/registry/v2/groups' | jq

# MinIO console
open http://localhost:9001  # login: minioadmin / minioadmin
```

If any fails, see `DEVOPS-RUNBOOK.md` §11 (Troubleshooting).

---

## 3. Build util/ (2 min)

This is the critical step (R-01). If this fails, see `SPRINT-0-ONBOARDING.md` for the fix.

```bash
cd ..
mvn -pl util -am clean install -DskipTests
# Expected: BUILD SUCCESS
```

If you see `ParentNotFoundException`, the parent pom (`../pom.xml`) is broken — apply the fix from `SPRINT-0-ONBOARDING.md` §2.

---

## 4. Build the rest of the monorepo (5 min)

```bash
mvn validate
# Expected: BUILD SUCCESS

mvn test
# All unit tests pass
```

If services are not yet scaffolded, this will show only util/ tests passing. That's fine — services come in Sprint 1+.

---

## 5. Set up Vault (5 min, optional in v0.1)

```bash
# Vault is in dev mode (no auth required for local)
docker run -d --name vault -p 8200:8200 \
  -e VAULT_DEV_ROOT_TOKEN_ID=root \
  -e VAULT_DEV_LISTEN_ADDRESS=0.0.0.0:8200 \
  hashicorp/vault:1.15

export VAULT_ADDR=http://localhost:8200
export VAULT_TOKEN=root

# Seed dev secrets
vault kv put secret/stripe STRIPE_API_KEY=sk_test_xxx STRIPE_WEBHOOK_SECRET=whsec_xxx
vault kv put secret/events/hmac/catalog-service hmac_key=$(openssl rand -hex 32)
vault kv put secret/events/hmac/inventory-service hmac_key=$(openssl rand -hex 32)
# ... etc per service

# Verify
vault kv get secret/stripe
```

---

## 6. (Optional) Set up the dev BFF + frontend

For early UI work (Sprint 2+):

```bash
# BFF (no service dependency yet; can run standalone)
cd bff/storefront-bff  # when scaffolded
mvn spring-boot:run

# Frontend
cd frontend/storefront
npm install
npm run dev  # http://localhost:3000

cd ../admin
npm install
npm run dev  # http://localhost:3001
```

---

## 7. Verify your setup (5 min)

Run this smoke test:

```bash
# 1. dev platform up
docker compose -f dev/docker-compose.yml ps
# All 8 services healthy

# 2. util builds
mvn -pl util -am clean install -q
# Exit 0

# 3. (if you have a service scaffolded) service runs
mvn -pl services/catalog spring-boot:run -Dspring-boot.run.profiles=dev
# Service listens on :8080

# 4. Kafka topic created
docker exec -it kafka kafka-topics --bootstrap-server localhost:9092 --create --topic test --partitions 1
docker exec -it kafka kafka-topics --bootstrap-server localhost:9092 --list
# Should show: test

# 5. ES responds
curl -s 'http://localhost:9200/_cluster/health?pretty' | jq .status
# Should show: "green" or "yellow"
```

If all 5 pass, you're ready for Sprint 1. Read `SPRINT-0-ONBOARDING.md` for Sprint 0 story tasks.

---

## 8. Reset (when things go wrong)

```bash
# Full nuclear reset
cd dev
docker compose down -v  # -v removes volumes
docker compose up -d

# Reset only the database (preserves Kafka, ES, etc.)
docker compose restart postgres
docker volume rm side-project_postgres-data
docker compose up -d postgres
```

---

## 9. Daily workflow

```bash
# Morning: start the dev platform
cd dev
docker compose up -d

# Check status
docker compose ps

# Tail logs
docker compose logs -f --tail=100

# Stop when done
docker compose down
```

---

## 10. Common gotchas

| Symptom | Likely cause | Fix |
|---|---|---|
| `mvn install` fails with "ParentNotFoundException" | R-01 — util/ parent pom broken | See `SPRINT-0-ONBOARDING.md` §2 |
| Port 5432 already in use | Postgres already running locally | `lsof -i :5432` and kill, or use docker-compose down |
| Kafka "advertised.listeners" warning | Docker network issue | `docker compose restart kafka` |
| ES takes 60+ seconds to start | Normal for first boot | Wait; subsequent starts are fast |
| Vault connection refused | Vault container not running | `docker run -d --name vault ...` (see §5) |
| Out of disk space | Docker volumes accumulated | `docker system prune -a --volumes` (nuclear) |

---

## 11. IDE setup (optional, 10 min)

### IntelliJ IDEA

1. **File → Open** → select project root
2. **Maven** should auto-detect; if not, **View → Tool Windows → Maven** → **+** → select `pom.xml`
3. **Project Structure** → **SDKs** → add Java 25
4. **Plugins** → install: Lombok, MapStruct, Spring Boot Helper
5. **Code Style** → import util's `checkstyle.xml` (if exists)

### VS Code

1. **File → Open Folder** → select project root
2. Install extensions: Extension Pack for Java, Spring Boot Extension Pack, ESLint, Prettier
3. **Settings → Java → Configuration** → set JDK to Java 25
4. **Settings → Workspace Trust** → enable

---

## 12. What's next?

- **Sprint 0 work** (if you're assigned): `SPRINT-0-ONBOARDING.md` (R-01 fix, monorepo, etc.)
- **Sprint 1 work** (Catalog + Inventory): `SPRINT-1-DEV-HANDBOOK.md`
- **Architecture questions**: `ADR-INDEX.md` + `architecture.md`
- **Per-story lookup**: `EPIC-1-STORIES-QUICKREF.md`
- **Just want to explore**: `AGENT-ONBOARDING.md`
- **Run chaos tests**: `QA-AGENT-HANDBOOK.md`
- **Production-style deploy**: `DEVOPS-RUNBOOK.md` §10
