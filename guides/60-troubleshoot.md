# 60 — Troubleshoot

> Symptom-based quick fixes. Find your error, follow the recipe.

## Quick checks first

```bash
# 1. Infra running?
docker compose -f dev/docker-compose.yml ps

# 2. Service logs (latest 50 lines)
tail -50 /tmp/smoke/<service>.log
# (e.g., tail -50 /tmp/smoke/catalog.log)

# 3. Caused by chain in the log
grep "Caused by" /tmp/smoke/<service>.log | head -5

# 4. Is the service actually listening?
lsof -i :8091  # catalog
```

---

## Symptom: `Failed to connect to localhost port <port>`

**Possible causes (in order of likelihood):**

1. **Service didn't start** — check the log
   ```bash
   tail -100 /tmp/smoke/<service>.log | grep -E "ERROR|Application run failed"
   ```
2. **Port conflict** — something else owns the port
   ```bash
   lsof -i :<port>  # e.g., lsof -i :8091
   ```
3. **Maven not finished compiling** — wait for "BUILD SUCCESS" in terminal
4. **Service silently exited** — `pkill -f "spring-boot:run"` and check exit code

---

## Symptom: `Caused by: org.springframework.beans.factory.BeanCreationException`

Look at the next line of the error for the specific bean that failed. Cross-reference [30-known-issues.md](30-known-issues.md).

Common cases:
- `UnsatisfiedDependencyException` → missing `@Bean` somewhere
- `NoSuchBeanDefinitionException: No qualifying bean of type 'X'` → need a `@Bean` for X or `@ComponentScan` covers it
- `BeanDefinitionStoreException: Annotation-specified bean name 'X' for bean class [...] conflicts with existing` → see Issue #6 in [30-known-issues.md](30-known-issues.md)

---

## Symptom: `Caused by: org.springframework.util.PlaceholderResolutionException: Could not resolve placeholder 'X'`

A `@Value("${X}")` somewhere can't find property `X` in the environment. Common cases:
- `folder.temp.timeToLive` — util's `FileUtil` needs this. Means `UtilsAutoConfiguration` is being loaded into the service and a `@Component` is being scanned. See [30-known-issues.md](30-known-issues.md#issue-7-utilautoconfiguration-leaking-components-fixed-).
- `excel.sheet.password` — same root cause as above.
- `${POSTGRES_CATALOG_USER}` — env var not set. Check your env block.

Fix: ensure the env var is in the `env` prefix of the `mvn` command, OR set defaults in `application.yml`.

---

## Symptom: `Caused by: java.net.ConnectException: Connection refused`

The service is running but can't reach something it depends on. Common cases:
- **Postgres** — `docker compose ps` to check; if postgres is down, `docker compose up -d`
- **Redis** — same
- **Kafka** — same
- **Other service** (cross-service call) — that service must be up first

Verify network:
```bash
docker exec ecommerce-platform-postgres-1 psql -U postgres -d catalog_db -c "SELECT 1"
docker exec ecommerce-platform-redis-1 redis-cli ping
docker exec ecommerce-platform-kafka-1 kafka-topics --list --bootstrap-server localhost:9092 2>&1 | head -5
```

---

## Symptom: HTTP 401 on `/api/*`

**Expected (by design).** See [30-known-issues.md](30-known-issues.md#issue-8-all-services-use-oauth2-resource-server-by-design).

Use `/actuator/health` (always whitelisted) or direct DB peek.

---

## Symptom: HTTP 403 on webhook

**Same as 401** — Spring Security blocks it. Even `DevSecurityConfig` doesn't always win. Use the DB to verify webhook dedup:

```bash
docker exec ecommerce-platform-postgres-1 psql -U postgres -d payment_db -c \
  "SELECT event_id, count(*) FROM webhook_dedup GROUP BY event_id"
```

---

## Symptom: Service takes 5+ minutes to start

This is normal for the first run. After the first build, services start in 30-60s.

If it hangs longer, check the log for what's slow:
```bash
tail -200 /tmp/smoke/<service>.log | grep -E "Started|Started in|started in"
```

The most common slow start is:
- Flyway running migrations on cold start (~10-30s for first boot)
- Hibernate entity scanning (especially with cross-service deps)
- Spring context refresh with many beans

---

## Symptom: `OutOfMemoryError` or `PermGen space`

Increase heap:
```bash
export MAVEN_OPTS="-Xmx2g -Xms512m"
mvn -pl services/<svc> -am spring-boot:run
```

Or:
```bash
java -Xmx2g -jar services/<svc>/target/<svc>-1.0-SNAPSHOT.jar
```

---

## Symptom: Service crashes after a few minutes

Check for OOM-killer in macOS Console.app, or check `ulimit`:
```bash
ulimit -n
# Should be 1024+ for Spring Boot apps
```

---

## Symptom: Tests pass locally but fail in CI

Common causes:
- Tests rely on `localhost` resolution — make sure CI has Postgres
- Tests use a specific timezone — set `TZ=UTC`
- Tests check timestamps — use `Clock` injection

---

## Nuclear option: full reset

```bash
# 1. Stop everything
pkill -f "spring-boot:run"
docker compose -f dev/docker-compose.yml down -v

# 2. Clean
mvn clean
docker system prune -f

# 3. Rebuild from scratch
mvn -pl util install -q -DskipTests
mvn -pl services/catalog,services/inventory,services/cart,services/checkout,services/payment -am package -q -DskipTests

# 4. Restart infra
cd dev && docker compose -f docker-compose.yml up -d
```

---

## Next

- [30-known-issues.md](30-known-issues.md) — documented bugs
- [10-build-and-start.md](10-build-and-start.md) — build reference
- [README.md](README.md) — back to index
