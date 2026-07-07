# side-proj — Guides

> Everything you need to run, build, debug, and verify the **side-proj** e-commerce platform on your local Mac.

## Index

| File | Purpose | Read time |
|------|---------|-----------|
| **[00-quickstart.md](00-quickstart.md)** | 5-minute copy-paste: infra → build → start → curl. **Start here.** | 5 min |
| **[10-build-and-start.md](10-build-and-start.md)** | All build commands, all start commands, env-var matrix, port matrix. | 10 min |
| **[20-runtime-smoke.md](20-runtime-smoke.md)** | What to curl on each service, expected responses, what passes the smoke test. | 10 min |
| **[30-known-issues.md](30-known-issues.md)** | Documented bugs + workarounds (F1–F17 + post-orchestrator). | read as needed |
| **[40-verify-curl.md](40-verify-curl.md)** | Per-service curl cookbook with happy-path + error-path. | read as needed |
| **[50-services-overview.md](50-services-overview.md)** | What each service does, which DB it owns, which port it uses. | 5 min |
| **[60-troubleshoot.md](60-troubleshoot.md)** | Common errors + how to fix them. | search by symptom |

## TL;DR

```bash
cd /Users/tonminh-mac/IdeaProjects/side-proj

# 1. Bring up infra (postgres, kafka, redis, es, minio, opa, apicurio)
cd dev && docker compose -f docker-compose.yml up -d
cd ..

# 2. Build util + 5 services
mvn -pl util install -q -o -DskipTests
mvn -pl services/catalog,services/inventory,services/cart,services/checkout,services/payment -am package -q -o -DskipTests

# 3. Start ONE service (catalog)
SPRING_PROFILES_ACTIVE=dev \
POSTGRES_HOST=localhost POSTGRES_PORT=5432 \
POSTGRES_CATALOG_USER=catalog_user POSTGRES_CATALOG_PASSWORD=catalog_pass POSTGRES_CATALOG_DB=catalog_db \
mvn -f services/catalog/pom.xml spring-boot:run -Dspring-boot.run.arguments=--server.port=8091

# 4. Verify (in another terminal)
curl http://localhost:8091/actuator/health | jq .
```

**Working state (as of 2026-07-07):** 4/5 services boot cleanly (catalog, inventory, cart, payment). Checkout fails — see [30-known-issues.md](30-known-issues.md#issue-2-checkout-saga-2-5-entity-scanning).
