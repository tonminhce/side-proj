---
baseline_commit: 6b6d952
---

# Story 3.4: Gateway rate-limiter with BIN velocity check (FR-81) — solves R-05

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the gateway,
I want the rate-limiter key to combine IP + card-fingerprint + ASN, with a global BIN velocity check,
So that card-testing attacks via residential proxies are blocked (R-05 / AT-01 root cause).

## Acceptance Criteria

1. **Given** the architecture binds a **`services/gateway/`** Maven module (new per ADR-13 + ADR-24 + `architecture.md:1086` row "R-05 → services/gateway + services/payment FR-81"), **When** Story 3.4 lands, **Then** a new Maven module `services/gateway/` is bootstrapped with packaging `jar`, parent `vn.vnpt:side-project:1.0-SNAPSHOT`, runtime artifact name `gateway`, port `8080` (gateway default per architecture), and dependencies: `util` (for `RedisConfig`, `SnowflakeIdGenerator`, `BaseEntity`), `spring-cloud-starter-gateway` (Spring Cloud 2025.1, Boot 4-compatible per Story 0.4's pinned matrix), `spring-boot-starter-actuator` (for `/actuator/health`), `spring-boot-starter-data-redis-reactive` (Reactive Redis for the gateway's reactive filter chain). NO `spring-boot-starter-web` (gateway uses WebFlux). NO JPA / Flyway / Postgres — the gateway is stateless + Redis-backed only. The `mvn validate` module count grows from 18 to **19** modules.
2. **Given** the architecture's `local-docs/04-rate-limiter-design.md` already specifies the Token Bucket algorithm + Lua script + multi-bucket atomic check (canonical source for the algorithm), **When** the dev agent wires the rate-limiter, **Then** the Lua script is bundled at `services/gateway/src/main/resources/lua/rate_limiter.lua` and loaded once at startup via `DefaultRedisScript<List>`. The script MUST use `redis.call('TIME')` (per **ADR-13 — fixes local-docs/04 bug** where the original spec used client-side `System.currentTimeMillis()`; client-side time breaks under multi-region deploys because Redis Lua can't trust the caller's clock). The script's KEYS are the bucket list (IP, fingerprint, ASN per ADR-24), ARGV are capacity / refill / now_ms (server-side via `redis.call('TIME')`) / ttl / cost / N (bucket count). Returns `{allowed(0/1), min_remaining, max_retry_after, blocked_idx}`. Phase-1 (peek every bucket) + Phase-2 (commit only on allow) prevent partial consumption — see `local-docs/04` lines 25-82 for the canonical algorithm.
3. **Given** the architecture's `architecture.md:233` ADR-24 binds rate-limiter keys to `IP + card-fingerprint + ASN`, **When** the dev agent builds the filter, **Then** the filter composes the rate-limit key tuple as `(ip, cardFingerprint, asn)` where `cardFingerprint = sha256(cardBin + cardLast4)` (a hash of the BIN + last-4, NOT the PAN — R-15 boundary; the PAN is never read by the gateway since the Stripe Elements iframe handles it client-side), `asn` is the autonomous-system number of the client IP (looked up via MaxMind GeoLite2 or a stub `// TODO: MaxMind GeoLite2 integration in v2` returning `"AS0_VN"` for v1; see Out-of-scope), and `ip` is taken from `X-Real-IP` (gateway owns this header per NFR-SEC-1 + `architecture.md:1048` row "Trust-boundary: gateway owns X-Real-IP from trusted hops"). The Lua call passes 3 KEYS (one per component). The rate-limit is **only enforced for payment-related paths** (`/api/payment/**`, `/bff/storefront/payment/**`, `/webhooks/stripe` excluded — webhooks have their own dedup contract). Non-payment paths go through the gateway un-filtered (this story's scope is payment-side defense; the general IP rate-limit lives in a follow-up gateway story).
4. **Given** R-05 mitigation requires a **global BIN velocity check** (per PRD §4.16 FR-81 + `addendum.md:20` "card-fingerprint hash + BIN velocity check"), **When** the dev agent implements it, **Then** a separate Lua script `services/gateway/src/main/resources/lua/bin_velocity.lua` runs the check: `(BIN, currentWindowStart) → count` reads from `rl:bin:<bin>:<window_start>` (Redis sorted set with timestamps as scores), trims entries older than `window_minutes`, and returns the count. If `count > N` (config: `gateway.bin-velocity.max-attempts: 10`, `gateway.bin-velocity.window-minutes: 60` — Story 3.4 defaults; tune later per ops data), the filter rejects with HTTP 429 + `Retry-After: <window_remaining_seconds>`. The BIN is the first 6 digits of the card (extracted from the request body's `cardBin` field OR the `cardFingerprint` query parameter — verify which the BFF sends). For v1, the BIN extraction trusts the request body's `cardBin` field; future story may switch to client-derived BIN via JS (PCI scope minimization). The filter emits `RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset` headers per RFC 6585 / draft-ietf-httpapi-ratelimit-headers.
5. **Given** the Spring Cloud Gateway custom filter pattern (per `architecture.md:686` "Stripe Elements iframe + log redaction" implies gateway-side filtering elsewhere) + `local-docs/04:5` "A custom `GatewayFilter`", **When** the dev agent writes the filter, **Then** a single `GatewayFilterFactory<RateLimiterConfig>` is registered at `services/gateway/.../infrastructure/filter/RateLimiterGatewayFilterFactory.java` (Spring Cloud Gateway convention: `*GatewayFilterFactory` with a `Config` inner class + `apply(Config)` returning `GatewayFilter`). The filter's `apply(...)`: (a) parses the rate-limit config (capacity, refill, cost, window minutes); (b) extracts IP from `X-Real-IP`; (c) computes fingerprint + ASN keys; (d) calls the Lua script via `redisTemplate.execute(redisScript, keys, args)`; (e) on `allowed=0`, short-circuits with HTTP 429 + the rate-limit headers; (f) on `allowed=1`, forwards to downstream via `chain.filter(exchange)`. The filter is **only** applied to `payment/**` routes via `routes[].filters[]` in `application.yml`. The trust-boundary validation (IP not blank, fingerprint is a valid hex SHA-256) lives in the filter's start, not in downstream services.
6. **Given** the runtime smoke rule (project memory `runtime-smoke-rule.md` — every dev-story must runtime-smoke; F1 review caught a bean-name clash unit tests missed) + the gateway is stateless + the rate-limiter needs a live Redis container, **When** Story 3.4 completes, **Then** the dev agent runs `bash dev/scripts/smoke-gateway-3-4.sh` which: (a) verifies Redis is UP (`docker compose ps | grep redis` — already running per `docker ps` from Story 0.3 setup), (b) clears any stale listener on the gateway port (8080), (c) starts `services/gateway` with `SPRING_PROFILES_ACTIVE=dev` + Spring Cloud Gateway auto-config, (d) waits for `/actuator/health` UP (up to 90s), (e) `curl -s http://localhost:8080/api/payment/test -H "X-Real-IP: 1.2.3.4"` 11 times in a tight loop — asserts the 11th call returns HTTP 429 (BIN velocity threshold exceeded after 10 requests with the same BIN), (f) `redis-cli -h localhost LRANGE rl:bin:123456:window_* 0 -1` (or `docker exec redis redis-cli ...`) to assert the BIN velocity sorted-set entries match the test sequence, (g) checks `RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset` headers are present on the 429 response, (h) kills the process, exits 0.
7. **Given** `local-docs/04:1` says "A custom `GatewayFilter` in Spring Cloud Gateway implementing a Token Bucket algorithm with combined RBAC and IP-Based policies, backed by Redis for distributed state", **When** the dev agent scopes this story, **Then** the **payment-route filter** is the only filter shipped this story. The broader "RBAC + per-endpoint cost + per-route IP" token-bucket (the full local-docs/04 design) is **deferred** to a follow-up gateway story (likely a new epic — gateway observability + cost weighting). This story ships the payment-card-testing defense per ADR-24 specifically. The dev agent MUST add a one-line `// TODO follow-up gateway story: full RBAC + per-endpoint cost + per-route IP per local-docs/04` comment in the filter's javadoc so the gap is visible in review.
8. **Given** the architecture binds `services/gateway/` to port 8080 (default gateway) and the existing services occupy 8081-8086, **When** the dev agent configures routes, **Then** `application.yml` declares route mappings for `payment/**` → `http://localhost:8086` (payment service from Story 3.1) via Spring Cloud Gateway's `routes:` block. The dev-profile overrides can keep the same port. Other service routes (catalog → 8081, etc.) are NOT wired this story (deferred to the broader gateway story). The gateway `application.yml` exposes `/actuator/health` (so the smoke can poll it) and `management.endpoints.web.exposure.include: health,info` (FR-29 deny-list baseline — `/actuator/loggers` and `/actuator/httptrace` stay off). The `application.yml` carries the `gateway.bin-velocity.max-attempts: 10` + `gateway.bin-velocity.window-minutes: 60` config keys (AC #4).
9. **Given** `local-docs/04:23` says "Observability: Micrometer counters tagged by `bucket_type`, `route`, `decision`", **When** the dev agent wires metrics, **Then** the filter emits three Micrometer counters: `gateway.ratelimit.allowed` (tag: route=payment), `gateway.ratelimit.blocked` (tag: route=payment, reason=tokenbucket|bin_velocity), `gateway.bin_velocity.count` (gauge — current BIN attempt count in the window). These counters are exposed via `/actuator/metrics` (the existing expose-list already has `health,info`; add `metrics` per this story). The dev profile uses Micrometer's simple in-memory registry (no Prometheus) — the gateway is a generic Spring Boot service; metrics export to Prometheus is a Story 10.x (observability) concern.
10. **Given** NFR-SEC-1 (`architecture.md:1048`) "All services behind gateway; trust-boundary: gateway owns `X-Real-IP` from trusted hops", **When** the dev agent handles the IP source, **Then** the filter reads `X-Real-IP` (set by the upstream load balancer / ingress). In dev, the smoke sets `X-Real-IP: 1.2.3.4` directly. The filter MUST NOT trust `X-Forwarded-For` (client-spoofable) and MUST NOT derive IP from `request.getRemoteAddress()` (works for direct connections but breaks under mTLS-terminating ingress; the load balancer adds `X-Real-IP` after stripping the proxy chain). One-line javadoc explains the choice (NFR-SEC-1 binding). The test asserts the filter uses `X-Real-IP` exclusively — a request with both `X-Real-IP: 1.2.3.4` and `X-Forwarded-For: 9.9.9.9` keys on the `1.2.3.4` value.
11. **Given** the per-service log redaction from Story 3.3 (`util/.../logging/PanRedactingAppender.java`) is wired via `logback-spring.xml` and util is a dependency, **When** the gateway starts, **Then** `services/gateway/src/main/resources/logback-spring.xml` `<include>`s the shared `util/logback-include.xml` (same pattern as `services/payment/src/main/resources/logback-spring.xml` from Story 3.3). The same PAN-redaction deny-list (R-15 / FR-29) applies to gateway logs (defense in depth — the gateway should never see a PAN, but if it does, the regex redactor strips it). The request-body logger deny-list ArchUnit rules in `util/.../archunit/RequestBodyLoggerDenyListTest.java` already cover `vn.vnpt..` (Story 3.3 HIGH-3 fix); no additional ArchUnit work needed.
12. **Given** the existing `util/.../common/component/RedisConfig.java` provides `RedisTemplate<String, Object>` (per `util/src/main/java/vn/vnpt/util/common/component/RedisConfig.java`), **When** the dev agent wires Redis access for the Lua scripts, **Then** the gateway uses `StringRedisTemplate` (not `RedisTemplate<String, Object>`) because Lua scripts need `String` keys + `String` args + `List<String>` results. The dev agent MUST register a `@Bean StringRedisTemplate` if util doesn't already expose one (verify; if not, add a `GatewayRedisConfig` in the gateway module to provide `StringRedisTemplate` + `DefaultRedisScript<List> rateLimiterScript` + `DefaultRedisScript<Long> binVelocityScript`, each loaded via `ClassPathResource("lua/rate-limiter.lua")` + `ClassPathResource("lua/bin-velocity.lua")`). The existing `util/RedisConfig` is `@Primary` for `RedisTemplate<String, Object>`; the gateway module's `StringRedisTemplate` bean does NOT conflict (different type).

### Out of scope (do NOT build — deferred, stated to prevent scope creep)

- **Full RBAC + per-endpoint cost + per-route IP token bucket per `local-docs/04`** → follow-up gateway story (a new gateway epic). This story ships the payment-specific card-testing defense only.
- **MaxMind GeoLite2 ASN lookup** → v1 returns stub `"AS0_VN"` for every IP. Future story wires the GeoLite2 DB download + lookup. The `// TODO` comment in `RateLimiterGatewayFilterFactory` flags the gap.
- **Vault integration for the gateway** → not needed in v1 (the gateway has no secrets; all upstream services are auth'd via JWT in `Authorization` header, which the gateway validates per NFR-SEC-2). Future gateway stories may need Vault for HMAC verification keys (Story 3.5 wires per-service HMAC; the gateway doesn't need to verify those).
- **mTLS termination at the gateway** → Story 0.x (architecture §8.3). The gateway in dev binds plain HTTP on 8080.
- **Route mappings for non-payment services (catalog, inventory, cart, checkout, etc.)** → follow-up gateway story. This story only ships the payment-route filter.
- **Saga-orchestrator-aware flow control (R-04 mitigation at the gateway level)** → out of scope; Modulith outbox is the saga mechanism per ADR-01.
- **Stripe webhook route mapping through the gateway** → webhooks go direct to `services/payment` (Story 3.2's `StripeWebhookController` is already public; gateway-side webhook routing would break mTLS at ingress per `architecture.md:1048` NFR-SEC-2). The gateway excludes `/webhooks/stripe` from the rate-limit filter via the route predicate.
- **Per-endpoint cost weighting (Story 5.x: `/login` = 10 tokens, `/search` = 5 tokens, etc.)** → follow-up gateway story.
- **`dev/.env` updates for gateway config** → the gateway uses config keys only; no secrets needed in v1.
- **Per-service tests in `services/catalog`, `services/inventory`, etc. for the shared Lua script** → the Lua script lives in `services/gateway/src/main/resources/lua/`. Single gateway test class covers it. Per-service tests are N/A (no other service runs the rate-limiter).

## Tasks / Subtasks

- [ ] **Task 1 — Bootstrap `services/gateway` Maven module** (AC: #1)
  - [ ] Add `<modules><module>services/gateway</module></modules>` to root `pom.xml`.
  - [ ] Create `services/gateway/pom.xml` with parent `vn.vnpt:side-project`, packaging `jar`, dependencies: `util`, `spring-cloud-starter-gateway`, `spring-boot-starter-actuator`, `spring-boot-starter-data-redis-reactive`, Lombok (`@Getter @Setter` etc. per codebase convention), Testcontainers `redis` (test scope), `spring-boot-starter-test`.
  - [ ] Create `services/gateway/src/main/java/vn/vnpt/gateway/GatewayApplication.java` — `@SpringBootApplication @ApplicationModule(displayName = "gateway")`.
  - [ ] Verify `mvn validate` shows 19 modules (was 18 before this story).

- [ ] **Task 2 — Bundle Lua scripts** (AC: #2, #4)
  - [ ] `services/gateway/src/main/resources/lua/rate-limiter.lua` — copy the canonical script from `local-docs/04:25-82` verbatim, with one critical fix per **ADR-13**: use `redis.call('TIME')` for `now_ms` instead of the client-supplied timestamp (the original `local-docs/04` script took `now_ms` as ARGV[3]; the fix moves that into the script body via `local now_arr = redis.call('TIME'); local now_ms = now_arr[1] * 1000 + math.floor(now_arr[2] / 1000)`). One-line header comment cites ADR-13.
  - [ ] `services/gateway/src/main/resources/lua/bin-velocity.lua` — `KEYS[1] = rl:bin:<bin>:<window_start>`; reads count via `ZCARD`, trims old entries with `ZREMRANGEBYSCORE`, returns count. Document the key shape in a header comment.
  - [ ] Both scripts MUST be pure Lua (no Redis modules required); standard Redis 7+ ships with these commands.

- [ ] **Task 3 — `GatewayRedisConfig` + Lua script beans** (AC: #12)
  - [ ] `services/gateway/.../infrastructure/config/GatewayRedisConfig.java` — `@Configuration` with `@Bean StringRedisTemplate stringRedisTemplate(...)` (uses `RedisConnectionFactory` autoconfigured from `spring.data.redis.host/port`).
  - [ ] `@Bean DefaultRedisScript<List> rateLimiterScript()` — loads `lua/rate-limiter.lua` via `ClassPathResource`, sets `setResultType(List.class)`.
  - [ ] `@Bean DefaultRedisScript<Long> binVelocityScript()` — loads `lua/bin-velocity.lua`, sets `setResultType(Long.class)`.
  - [ ] One-sentence javadoc each. NO multi-paragraph prose (F8 policy).

- [ ] **Task 4 — `RateLimiterGatewayFilterFactory`** (AC: #3, #5, #10)
  - [ ] `services/gateway/.../infrastructure/filter/RateLimiterGatewayFilterFactory.java` — extends `AbstractGatewayFilterFactory<RateLimiterGatewayFilterFactory.Config>`. Inner `Config` record `(int capacity, double refillPerSec, int cost, int windowMinutes, int binVelocityMaxAttempts)`.
  - [ ] `apply(Config config)` returns `GatewayFilter`: extracts `X-Real-IP` from `ServerHttpRequest.getHeaders()`; computes `cardFingerprint = sha256(cardBin + cardLast4)` if the request carries `X-Card-Bin` + `X-Card-Last4` headers (the BFF strips PAN and forwards BIN/last4 — verify against BFF design when BFF lands); looks up ASN (stub `"AS0_VN"` for v1); builds KEYS = [`rl:ip:<ip>`, `rl:fp:<fingerprint>`, `rl:asn:<asn>`]; calls `redisTemplate.execute(rateLimiterScript, keys, capacity, refill, ttl, cost, N)`; on `allowed=0`, sets HTTP 429 + `RateLimit-*` headers + `Retry-After`; on `allowed=1`, calls `chain.filter(exchange)`.
  - [ ] Run the BIN velocity Lua script on the BIN extracted from `X-Card-Bin` header. If `count > binVelocityMaxAttempts`, set HTTP 429 + `Retry-After: <window_remaining_seconds>`.
  - [ ] One-line javadoc citing FR-81, R-05, ADR-24. NO multi-paragraph prose. The `// TODO follow-up gateway story: full RBAC + per-endpoint cost + per-route IP per local-docs/04` comment per AC #7.

- [ ] **Task 5 — `application.yml` + route mappings** (AC: #1, #5, #8, #11)
  - [ ] `services/gateway/src/main/resources/application.yml`:
        - `server.port: 8080`
        - `spring.data.redis.host: ${REDIS_HOST:localhost}`
        - `spring.data.redis.port: ${REDIS_PORT:6379}`
        - `spring.cloud.gateway.routes[0].id: payment`
        - `routes[0].uri: http://localhost:8086`
        - `routes[0].predicates[0]: Path=/api/payment/**`
        - `routes[0].filters[0].name: RateLimiter`
        - `routes[0].filters[0].args: { capacity: 100, refillPerSec: 10, cost: 1, windowMinutes: 60, binVelocityMaxAttempts: 10 }`
        - `gateway.bin-velocity.max-attempts: 10`
        - `gateway.bin-velocity.window-minutes: 60`
        - `management.endpoints.web.exposure.include: health,info,metrics` (add `metrics` for AC #9)

- [ ] **Task 6 — `logback-spring.xml` + PanRedactingAppender wiring** (AC: #11)
  - [ ] `services/gateway/src/main/resources/logback-spring.xml` — same shape as `services/payment/src/main/resources/logback-spring.xml`: `<include resource="logback-include.xml"/>` + `<root level="INFO"><appender-ref ref="REDACTING_CONSOLE"/></root>`.

- [ ] **Task 7 — Tests for the filter + Lua scripts** (AC: #2, #3, #5, #10)
  - [ ] `services/gateway/src/test/java/vn/vnpt/gateway/filter/RateLimiterGatewayFilterFactoryTest.java` — `@SpringBootTest(classes = GatewayApplication.class)` + `@Testcontainers` Redis (Spring Cloud Gateway context loads the full reactive chain). 5 tests:
        - `apply_allowsRequestWhenTokenBucketHasTokens` — single request, asserts `exchange.getResponse().getStatusCode() != HttpStatus.TOO_MANY_REQUESTS`.
        - `apply_blocksRequestWhenTokenBucketExhausted` — fire 11 requests, 11th returns 429.
        - `apply_blocksBinVelocityWhenLimitExceeded` — 11 requests with same `X-Card-Bin`, 11th returns 429.
        - `apply_usesXRealIpNotXForwardedFor` — request with `X-Real-IP: 1.2.3.4` + `X-Forwarded-For: 9.9.9.9`; Redis keys assert `rl:ip:1.2.3.4` was incremented, `rl:ip:9.9.9.9` was NOT.
        - `apply_emitsRateLimitHeaders` — assert `RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset` headers present on the 200 response.
  - [ ] `services/gateway/src/test/java/vn/vnpt/gateway/lua/RateLimiterLuaScriptTest.java` — direct `redisTemplate.execute(...)` calls, no Spring context. 3 tests: `script_initiallyBucketHasCapacity`, `script_consumesTokensOnEachCall`, `script_blocksWhenTokensBelowCost`.
  - [ ] `services/gateway/src/test/java/vn/vnpt/gateway/lua/BinVelocityLuaScriptTest.java` — 3 tests: `script_initiallyZeroCount`, `script_incrementsOnEachCall`, `script_returnsCountAboveThreshold`.
  - [ ] All tests use `@Testcontainers RedisContainer` for the Lua scripts.

- [ ] **Task 8 — Runtime smoke script** (AC: #6)
  - [ ] `dev/scripts/smoke-gateway-3-4.sh` — bash. Pattern mirrors `dev/scripts/smoke-payment-3-3.sh`:
        - (a) Free port 8080 (lsof fallback).
        - (b) Start `services/gateway` with `SPRING_PROFILES_ACTIVE=dev`.
        - (c) Wait for `/actuator/health` UP (up to 90s).
        - (d) `curl -i http://localhost:8080/api/payment/test -H "X-Real-IP: 1.2.3.4" -H "X-Card-Bin: 123456" -H "X-Card-Last4: 4242"` 11 times → assert the 11th returns HTTP 429 with `RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset` headers.
        - (e) `docker exec redis redis-cli KEYS "rl:*"` → assert keys exist for the test run.
        - (f) Kill the process, exit 0.
        - The downstream `services/payment` does NOT need to be running for this smoke — the gateway's rate-limiter fires before the route is invoked; a 429 from the rate-limiter short-circuits the chain.

## Dev Notes

### Implementation Notes

- **`services/gateway/` is a NEW Maven module** (per `architecture.md:1086`). The root `pom.xml` already has `<modules>` for the existing services; this story adds one entry. Run `mvn validate` to verify the module count grows from 18 to 19.
- **ADR-13 is the load-bearing fix**: the `local-docs/04` Lua script took `now_ms` as ARGV[3] (client-supplied). Under multi-region deploys the client's clock can drift; Redis Lua can't verify it. The fix is `redis.call('TIME')` inside the script — server-side, single source of truth. The bundled Lua MUST use this pattern; a fallback to ARGV[3] is not acceptable.
- **Card-fingerprint hashing** (per ADR-24 + R-15): `sha256(cardBin + cardLast4)`. The PAN itself is NEVER seen by the gateway (Stripe Elements iframe handles card data client-side; the BFF strips PAN and forwards `X-Card-Bin` + `X-Card-Last4` to the gateway via request headers). The hash binds to a specific BIN+last4 tuple without exposing PAN-shaped fields.
- **The 11-requests-then-429 test threshold** assumes the default `capacity: 100, refillPerSec: 10` config. If the test runs slower than ~100ms between requests, the bucket refills enough to absorb the 11th request. The test must run in a tight loop (no sleeps) so the 11 requests fit inside the refill window. The smoke script's `for i in $(seq 1 11); do curl ...; done` pattern handles this.
- **The gateway uses Spring Cloud Gateway (reactive, WebFlux-based)** — the existing services are Spring MVC. The gateway's reactive chain means `RedisTemplate.execute(...)` must be the reactive variant (`ReactiveRedisTemplate`). Verify the dep `spring-boot-starter-data-redis-reactive` brings the right pieces; if `ReactiveRedisTemplate` is missing, the filter has to bridge to the blocking `RedisTemplate` via `.block()`. Ponytail: minimum code — use whatever the dep provides.
- **`/webhooks/stripe` MUST be excluded from the gateway route filter.** The current `application.yml` only declares the `payment/**` route, so this is naturally handled — but a comment in `application.yml` notes the exclusion for posterity.
- **Test counts target** — `≥ 11 new tests` (filter 5 + rate-limiter Lua 3 + bin-velocity Lua 3). Gateway baseline: 0 (new module); target after Story 3.4: ≥ 11.
- **Module count** — `mvn validate` reports 19 modules after this story (was 18).
- **The `R-15` log redaction from Story 3.3 applies to gateway logs** via the shared `util/.../logging/PanRedactingAppender.java` + `logback-include.xml`. Defense in depth — the gateway never sees PAN, but if a future story leaks, the regex strips it.
- **The ArchUnit rules from Story 3.3 (`util/.../archunit/RequestBodyLoggerDenyListTest.java`) already cover `vn.vnpt..`** — the gateway automatically inherits the deny-list. No additional ArchUnit work needed this story.

### Debug Log References

(none — fresh story; debugs land in Dev Notes / Completion Notes after the dev agent runs)

### Completion Notes List

(empty — populated by dev-story)

### Deviations from literal story text (flagged for review)

(none — fresh story; deviations land here after dev-story)

## Dev Notes (post-dev-story section — populated after implementation)

<!-- The dev agent fills in the Implementation Notes, Debug Log References, Completion Notes List, Deviations from literal story text, and File List sections below. -->

### Project Structure Notes

- **Path placement** (per architecture §6 / `architecture.md:298` + `:924` + `:930`):
  - `pom.xml` (root) — add `<module>services/gateway</module>`
  - `services/gateway/pom.xml` ← new module POM (Task 1)
  - `services/gateway/src/main/java/vn/vnpt/gateway/GatewayApplication.java` ← new (Task 1)
  - `services/gateway/src/main/java/vn/vnpt/gateway/infrastructure/config/GatewayRedisConfig.java` ← new (Task 3)
  - `services/gateway/src/main/java/vn/vnpt/gateway/infrastructure/filter/RateLimiterGatewayFilterFactory.java` ← new (Task 4)
  - `services/gateway/src/main/resources/lua/rate-limiter.lua` ← new (Task 2)
  - `services/gateway/src/main/resources/lua/bin-velocity.lua` ← new (Task 2)
  - `services/gateway/src/main/resources/application.yml` ← new (Task 5)
  - `services/gateway/src/main/resources/logback-spring.xml` ← new (Task 6)
  - `services/gateway/src/test/java/vn/vnpt/gateway/filter/RateLimiterGatewayFilterFactoryTest.java` ← new (Task 7)
  - `services/gateway/src/test/java/vn/vnpt/gateway/lua/RateLimiterLuaScriptTest.java` ← new (Task 7)
  - `services/gateway/src/test/java/vn/vnpt/gateway/lua/BinVelocityLuaScriptTest.java` ← new (Task 7)
  - `dev/scripts/smoke-gateway-3-4.sh` ← new (Task 8)

- **Detected conflicts / variances (with rationale):**
  - **`gateway.bin-velocity.*` config** binds to `application.yml` directly; no `@ConfigurationProperties` record needed for two keys.
  - **Stub ASN lookup** — `// TODO MaxMind GeoLite2` comment in `RateLimiterGatewayFilterFactory.asnFor(...)` returns `"AS0_VN"`. Future story wires the real lookup.
  - **`StringRedisTemplate` in the gateway module** — util already has `RedisTemplate<String, Object>` but not `StringRedisTemplate` (Spring Boot autoconfigures the latter, but it's only registered if no primary `RedisTemplate` exists; util's `@Primary` blocks it). The gateway's `GatewayRedisConfig` registers its own `StringRedisTemplate` bean (no conflict — different type).
  - **`ReactiveRedisTemplate` vs blocking `RedisTemplate`** — verify at implementation time. If the gateway's reactive filter chain needs reactive Redis access, switch to `ReactiveRedisTemplate`. If the existing `RedisTemplate` works (synchronous `.execute(...)` called from a reactive filter chain via `.block()`), use it.

### References

- [Source: `_bmad-output/planning-artifacts/epics.md:676-687` — Story 3.4 AC]
- [Source: `_bmad-output/planning-artifacts/prd.md:208-211` — FR-78 to FR-82 (Compliance; FR-81 = card-testing defense)]
- [Source: `_bmad-output/planning-artifacts/addendum.md:20` — R-05 row verbatim: "Card-testing attack via residential proxies | Critical | High | Gateway + Fraud | Trust-boundary fix at gateway; card-fingerprint hash + BIN velocity (AT-01 root cause)"]
- [Source: `_bmad-output/planning-artifacts/architecture.md:222` — ADR-13: "Rate-limiter: Redis Lua with `redis.call('TIME')` (fixes local-docs/04 bug)"]
- [Source: `_bmad-output/planning-artifacts/architecture.md:233` — ADR-24: "Card-testing defense: rate-limiter keys on `IP + card-fingerprint + ASN`; BIN velocity check"]
- [Source: `_bmad-output/planning-artifacts/architecture.md:262` — Sprint 3: "R-05 (card-testing defense via gateway Lua + card-fingerprint hash + BIN velocity)"]
- [Source: `_bmad-output/planning-artifacts/architecture.md:687` — "Card-testing defense (IP + fingerprint + ASN) | ADR-24"]
- [Source: `_bmad-output/planning-artifacts/architecture.md:1040` — "FR-78 to FR-82 (Compliance) | services/invoice/ + cross-cutting | ✓ (FR-78 implements LC-03; FR-79 implements R-15; FR-80 implements LC-01; FR-81 implements AT-01; FR-82 implements AT-03)"]
- [Source: `_bmad-output/planning-artifacts/architecture.md:1086` — "R-05 | Card-testing attack | ADR-13 (rate-limiter Lua fix) + ADR-24 (multi-key rate limit) | gateway + `services/payment/` FR-81"]
- [Source: `_bmad-output/planning-artifacts/architecture.md:1121` — "Performance considerations addressed (NFR-PERF + ADR-13 Lua fix)"]
- [Source: `_bmad-output/planning-artifacts/architecture.md:1201` — "R-05 → Story 3.4"]
- [Source: `_bmad-output/planning-artifacts/architecture.md:1213` — "Lua rate-limiter with `redis.call('TIME')` (ADR-13) bound in Story 3.4"]
- [Source: `local-docs/04-rate-limiter-design.md:1-82` — Canonical Lua script + algorithm spec (with the ADR-13 fix applied to the bundled `services/gateway/.../lua/rate-limiter.lua`)]
- [Source: `util/src/main/java/vn/vnpt/util/common/component/RedisConfig.java` — util's existing `RedisTemplate<String, Object>` config; gateway's `StringRedisTemplate` is registered alongside (no conflict)]
- [Source: Story 3.3 (this PR's previous story) — `util/.../logging/PanRedactingAppender.java` + `util/.../resources/logback-include.xml` + `services/payment/.../logback-spring.xml` — gateway reuses the same redaction infra]
- [Source: Story 3.3 (this PR's previous story) — `util/.../archunit/RequestBodyLoggerDenyListTest.java` — repository-wide deny-list already covers `vn.vnpt..`; no gateway-specific ArchUnit work needed]
- [Source: `local-docs/00..10.md` — SA-reviewed architecture notes (load before unfamiliar work; per project memory `local-docs-sa-reviewed.md`)
- [Source: project memory `runtime-smoke-rule.md` — every dev-story must runtime-smoke; F1 review caught bean-name clash unit tests missed
- [Source: project memory `deep-review-rules.md` — F1: shared code lives in util/ (extract on second use, not first); 1-sentence javadoc; no single-impl abstractions
- [Source: project memory `dev-agent-personas.md` — adopt both `skills/backend-developer.md` + `skills/spring-boot-engineer.md` for this code work

## Dev Agent Record

### Agent Model Used

{{agent_model_name_version}}

### Debug Log References

### Completion Notes List

### File List