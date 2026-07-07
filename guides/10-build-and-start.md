# 10 — Build & Start (full reference)

> All the commands, env-var matrix, port matrix, and edge cases for running side-proj locally.

## 1. Build matrix

| Module | What it is | Build command | Why |
|--------|-----------|---------------|-----|
| `util` | shared library (BeanUtils, JPA base, Modulith outbox) | `mvn -pl util install -q -o -DskipTests` | services depend on it; install to `~/.m2` so services find it |
| `services/catalog` | CatalogService (Story 1.1) | `mvn -pl services/catalog -am package -q -o -DskipTests` | `-am` builds util first |
| `services/inventory` | InventoryService (Story 1.5) | `mvn -pl services/inventory -am package` | |
| `services/cart` | CartService (Story 2.1) | `mvn -pl services/cart -am package` | |
| `services/checkout` | CheckoutService (Story 2.3) | `mvn -pl services/checkout -am package` | |
| `services/payment` | PaymentService (Story 3.1) | `mvn -pl services/payment -am package` | |

**Build all 5 services + util in one go:**

```bash
cd /Users/tonminh-mac/IdeaProjects/side-proj
mvn -pl util install -q -o -DskipTests
mvn -pl services/catalog,services/inventory,services/cart,services/checkout,services/payment \
  -am package -q -o -DskipTests
```

**Build with tests (slow — for CI / before PR):**

```bash
mvn -pl util install -o -DskipTests=false
mvn -pl services/catalog -am verify
```

## 2. Service port matrix

| Service | Configured port (`server.port`) | Used in this guide | Why 8091/93/95/94/96? |
|---------|--------|--------|--------|
| catalog | `8081` | `8091` | avoid apicurio (8081) |
| inventory | `8083` | `8093` | convention |
| cart | `8085` | `8095` | convention |
| checkout | `8084` | `8094` | convention |
| payment | `8086` | `8096` | no overlap |

To use the **default ports** instead, drop `--server.port=...` from the `mvn spring-boot:run` command.

## 3. Env-var matrix (dev profile)

Every service needs these minimum env vars. Names follow the pattern `POSTGRES_<UPPER_SVC>_USER / _PASSWORD / _DB`.

| Service | Env vars |
|---------|----------|
| catalog | `POSTGRES_CATALOG_USER`, `POSTGRES_CATALOG_PASSWORD`, `POSTGRES_CATALOG_DB` |
| inventory | `POSTGRES_INVENTORY_USER`, `POSTGRES_INVENTORY_PASSWORD`, `POSTGRES_INVENTORY_DB` + `CATALOG_EVENTS_HMAC_SECRET` (for Story 1.5 in-process consumer to verify Stripe HMAC) |
| cart | `POSTGRES_CART_USER`, `POSTGRES_CART_PASSWORD`, `POSTGRES_CART_DB` |
| checkout | `POSTGRES_CHECKOUT_USER`, `POSTGRES_CHECKOUT_PASSWORD`, `POSTGRES_CHECKOUT_DB` |
| payment | `POSTGRES_PAYMENT_USER`, `POSTGRES_PAYMENT_PASSWORD`, `POSTGRES_PAYMENT_DB` |

**All services also need:**

- `POSTGRES_HOST=localhost`
- `POSTGRES_PORT=5432`
- `SPRING_PROFILES_ACTIVE=dev`

The `dev` profile:
- Maps the multi-tenant routing datasource to the local `*_db`
- Excludes `UtilsAutoConfiguration` (pre-existing util bug — see [30-known-issues.md](30-known-issues.md))
- Loads `DevSecurityConfig` that permits `/api/**` (dev-only auth bypass)

## 4. Start a service

```bash
# Pattern: env + mvn spring-boot:run + port arg
SPRING_PROFILES_ACTIVE=dev \
POSTGRES_HOST=localhost POSTGRES_PORT=5432 \
POSTGRES_<SVC>_USER=<svc>_user POSTGRES_<SVC>_PASSWORD=<svc>_pass POSTGRES_<SVC>_DB=<svc>_db \
[ CATALOG_EVENTS_HMAC_SECRET=dev-only-secret-do-not-use-in-prod ]  # for inventory
mvn -f services/<svc>/pom.xml spring-boot:run \
  -Dspring-boot.run.arguments=--server.port=<PORT>
```

`<SVC>` is uppercase service name. `<svc>` is lowercase.

## 5. Start ALL 4 working services in one script

```bash
cd /Users/tonminh-mac/IdeaProjects/side-proj
bash <<'BASH'
start_svc() {
  local svc=$1 port=$2
  local UP=$(echo "$svc" | tr a-z A-Z)
  env \
    POSTGRES_HOST=localhost POSTGRES_PORT=5432 \
    POSTGRES_${UP}_USER=${svc}_user POSTGRES_${UP}_PASSWORD=${svc}_pass POSTGRES_${UP}_DB=${svc}_db \
    CATALOG_EVENTS_HMAC_SECRET=dev-only-secret-do-not-use-in-prod \
    SPRING_PROFILES_ACTIVE=dev \
    nohup mvn -f services/$svc/pom.xml spring-boot:run \
      -Dspring-boot.run.arguments=--server.port=$port \
      > /tmp/smoke/$svc.log 2>&1 &
  echo "started $svc PID=$! port=$port"
}
start_svc catalog  8091
start_svc inventory 8093
start_svc cart      8095
start_svc payment  8096
BASH

# Wait ~150s for Spring Boot boot
sleep 150

# Check health
for port in 8091 8093 8095 8096; do
  curl -sS -m 5 http://localhost:$port/actuator/health | jq -c .
done
```

## 6. Build outputs (where to find jars)

```
util/target/util-0.0.1-SNAPSHOT.jar
services/catalog/target/catalog-1.0-SNAPSHOT.jar
services/inventory/target/inventory-1.0-SNAPSHOT.jar
services/cart/target/cart-1.0-SNAPSHOT.jar
services/checkout/target/checkout-1.0-SNAPSHOT.jar
services/payment/target/payment-1.0-SNAPSHOT.jar
```

## 7. Common gotchas

- **First build is slow** (5+ min) — Maven downloads every dep. After that, ~30s.
- **`-o` flag** = offline mode. Without it, Maven hits central. Useful when offline; remove if you want fresh dep checks.
- **JDK 25 required** — `./mvnw -v` shows what `java` is wired. Project sets `<release>25</release>`.
- **mvn install for util is sticky** — after `mvn -pl util install`, util jar is in `~/.m2`. Services find it without re-install. Only re-install when util code changes.
- **Don't use `mvn clean`** unless you want to rebuild from scratch — `clean` wipes target/ which means re-compile.

## Next

- [20-runtime-smoke.md](20-runtime-smoke.md) — what to curl to verify each service is real
- [30-known-issues.md](30-known-issues.md) — pre-existing bugs + workarounds
- [60-troubleshoot.md](60-troubleshoot.md) — common errors and fixes
