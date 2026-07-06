---
audience: dev, SRE
project: side-project
date: 2026-07-06
how-to-use: Redis usage patterns. Pair with DEVOPS-RUNBOOK.md (Redis operations) and ALERTING-RUNBOOK.md.
---

# Caching Strategy — side-project

> **Scope:** Redis 7 (single-instance in dev; cluster in prod). Used for: rate-limiter (per ADR-13 + ADR-24) + cache (per architecture §"Project Structure").
> **Convention:** Every Redis key has explicit TTL. No unbounded growth. Use namespaces (key prefix) to organize.

---

## 1. Key naming convention

```
<service>:<entity>:<id>[:<sub>]:<attribute>
```

Examples:
- `catalog:product:{uuid}` — product JSON (TTL: 5 min)
- `catalog:product:{uuid}:price` — just the price (TTL: 1 min)
- `inventory:on-hand:{variant_uuid}:{warehouse_id}` — on-hand count (TTL: 30 sec, fed by CDC)
- `inventory:reservation:{reservation_id}` — reservation token (TTL: 15 min, per FR-9)
- `cart:cart:{cart_id}` — cart JSON (TTL: 30 days)
- `rate-limit:ip:{ip}` — IP-based rate limit (TTL: 1 min, sliding window)
- `rate-limit:card-fingerprint:{fingerprint}` — card rate limit (TTL: 1 hour, sliding window)
- `rate-limit:bin:{bin}` — BIN velocity check (TTL: 1 hour, sliding window)
- `session:user:{user_id}` — session data (TTL: 1 hour, refresh on access)
- `tax:credential:{merchant_tax_code}` — cached tax authority credential (TTL: 1 hour)

---

## 2. Cache patterns

### Pattern 1: Cache-aside (lazy load) — most common

```java
@Service
public class CatalogService {

    private final RedisTemplate<String, Product> redis;
    private final ProductRepository db;

    public Product getProduct(UUID uuid) {
        // 1. Try cache
        var cached = redis.opsForValue().get("catalog:product:" + uuid);
        if (cached != null) return cached;

        // 2. Cache miss → fetch from DB
        var product = db.findByUuid(uuid).orElseThrow();
        redis.opsForValue().set(
            "catalog:product:" + uuid,
            product,
            Duration.ofMinutes(5)
        );
        return product;
    }
}
```

**Pros:** simple, always correct
**Cons:** cache miss latency penalty; risk of stale data

---

### Pattern 2: Write-through (write to cache + DB atomically)

```java
@Service
public class InventoryReservationService {

    public void reserve(Reservation r) {
        // 1. Update DB (FOR UPDATE)
        reservationRepo.save(r);
        invRepo.appendLedger(r.variantId, r.warehouseId, -r.qty, "reservation", r.id);

        // 2. Invalidate (or update) cache
        String key = "inventory:on-hand:" + r.variantId + ":" + r.warehouseId;
        redis.delete(key);  // next read will re-cache
    }
}
```

**Pros:** no stale data
**Cons:** write latency penalty

---

### Pattern 3: Cache-aside + write-through (best of both)

```java
@Service
public class CatalogService {

    public Product getProduct(UUID uuid) {
        var cached = redis.opsForValue().get("catalog:product:" + uuid);
        if (cached != null) return cached;
        return refreshCache(uuid);
    }

    public void updateProduct(Product p) {
        db.save(p);
        // Invalidate cache (next read will re-cache)
        redis.delete("catalog:product:" + p.uuid);
    }

    private Product refreshCache(UUID uuid) {
        var product = db.findByUuid(uuid).orElseThrow();
        redis.opsForValue().set("catalog:product:" + uuid, product, Duration.ofMinutes(5));
        return product;
    }
}
```

**Pros:** correct + fast
**Cons:** cache stampede on invalidation (mitigated by Pattern 5)

---

### Pattern 4: Read-through (cache populates itself)

```java
@Component
public class CachingProductRepository implements ProductRepository {

    private final RedisTemplate<String, Product> redis;
    private final ProductRepository db;

    @Cacheable(value = "products", key = "#uuid")
    public Optional<Product> findByUuid(UUID uuid) {
        return db.findByUuid(uuid);
    }

    @CacheEvict(value = "products", key = "#p.uuid")
    public Product save(Product p) {
        return db.save(p);
    }
}
```

**Pros:** clean annotation-based
**Cons:** Spring Cache abstraction; not as flexible

---

### Pattern 5: Cache stampede protection (jitter + single-flight)

```java
public Product getProduct(UUID uuid) {
    var cached = redis.opsForValue().get("catalog:product:" + uuid);
    if (cached != null) return cached;

    // Cache miss: only one thread per key fetches from DB
    var lockKey = "catalog:product:" + uuid + ":lock";
    var gotLock = redis.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(5));
    if (Boolean.TRUE.equals(gotLock)) {
        try {
            return refreshCache(uuid);
        } finally {
            redis.delete(lockKey);
        }
    } else {
        // Another thread is fetching; wait briefly + retry cache
        try { Thread.sleep(50); } catch (InterruptedException e) { ... }
        var retryCached = redis.opsForValue().get("catalog:product:" + uuid);
        return retryCached != null ? retryCached : refreshCache(uuid);
    }
}
```

**Pros:** protects DB from cache stampede
**Cons:** more complex; small latency variance

---

## 3. TTL strategy (per NFR-AVAIL / cache-staleness tradeoff)

| Data type | TTL | Rationale |
|---|---|---|
| Product JSON | 5 min | Catalog changes infrequently |
| Product price only | 1 min | Prices change more often |
| Search results (ES query) | 30 sec | ES is fast; cache mostly for hot queries |
| Inventory on-hand | 30 sec | Reads from CDC-fed projection; staleness tolerable |
| Inventory reservation | 15 min | Matches FR-9 reservation TTL |
| Cart | 30 days | Matches cart auto-expire (FR-18) |
| Rate limit (IP) | 1 min | Sliding window |
| Rate limit (card fingerprint) | 1 hour | Sliding window |
| Rate limit (BIN) | 1 hour | Sliding window |
| Session | 1 hour | Refresh on access |
| Tax credential | 1 hour | Refresh on access; safe default |

---

## 4. Memory budget

### Per-instance limits

For a single Redis 7 instance in prod (per addendum A3: 1GB memory):

| Key prefix | Approx % of memory | Rationale |
|---|---|---|
| `catalog:product:` | 30% | 300MB for ~5k products |
| `inventory:*` | 15% | Smaller, mostly reservation data |
| `cart:*` | 20% | Many small carts |
| `rate-limit:*` | 15% | Sliding windows, regenerate |
| `session:*` | 15% | Larger entries |
| Headroom | 5% | Buffer for spikes |

If memory pressure hits 80% (per NFR-AVAIL), trigger Redis flush on rate-limit keys (lowest value).

---

## 5. Rate-limiter (per ADR-13 + ADR-24)

### Per-IP rate limit (sliding window)

```lua
-- Rate-limit Lua (per ADR-13: use redis.call('TIME'))
local key = KEYS[1]  -- "rate-limit:ip:1.2.3.4"
local now_arr = redis.call('TIME')  -- Redis-server time
local now = tonumber(now_arr[1]) * 1000 + math.floor(tonumber(now_arr[2]) / 1000)
local window_ms = tonumber(ARGV[1])  -- 60000 = 1 min
local max_count = tonumber(ARGV[2])  -- 100
local current_ts = now - window_ms

-- Remove old entries
redis.call('ZREMRANGEBYSCORE', key, 0, current_ts)

-- Count current entries
local count = redis.call('ZCARD', key)
if count >= max_count then
    return 0  -- denied
end

-- Add new entry
redis.call('ZADD', key, now, now .. ':' .. math.random(1, 100000))
redis.call('PEXPIRE', key, window_ms)
return 1  -- allowed
```

### Combined rate-limit (per ADR-24: IP + card-fingerprint + BIN)

```java
public boolean isAllowed(ChargeRequest req) {
    // Check IP key
    if (!rateLimiter.allow("rate-limit:ip:" + req.clientIp, 100, Duration.ofMinutes(1))) {
        return false;
    }
    // Check card-fingerprint key
    if (!rateLimiter.allow("rate-limit:card:" + req.cardFingerprint, 10, Duration.ofHours(1))) {
        return false;
    }
    // Check BIN key (cross-user)
    if (!rateLimiter.allow("rate-limit:bin:" + req.bin, 1000, Duration.ofHours(1))) {
        return false;
    }
    return true;
}
```

---

## 6. Fail-open vs fail-closed (per NFR-AVAIL-4)

| Layer | Policy | Why |
|---|---|---|
| Per-IP rate limit | **Fail-open** (allow) | Per architecture explicit policy |
| Per-card-fingerprint | **Fail-closed** (deny) | Card-testing prevention critical |
| Per-BIN velocity | **Fail-closed** (deny) | Card-testing prevention critical |
| Cart cache | **Fail-open** (DB fetch) | Cart must always be readable |
| Session cache | **Fail-open** (DB fetch) | Sessions must always be validatable |
| Tax credential | **Fail-closed** (deny startup) | Q5 closure: critical compliance |

Implementation:

```java
public boolean allow(String key, long max, Duration window) {
    try {
        return redis.eval(LUA_SCRIPT, ScriptOutputType.INTEGER, ...);
    } catch (RedisException e) {
        if (key.startsWith("rate-limit:bin:") || key.startsWith("rate-limit:card:")) {
            log.error("Redis unavailable; failing closed for security-critical rate limit", e);
            return false;  // fail-closed for security
        }
        log.warn("Redis unavailable; failing open for availability", e);
        return true;  // fail-open for availability
    }
}
```

---

## 7. Connection management

```yaml
# application.yml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      timeout: 100ms
      lettuce:
        pool:
          max-active: 16
          max-idle: 8
          min-idle: 2
          max-wait: 100ms
```

Tight timeouts prevent Redis from becoming a bottleneck. Connection pool sized for 2-3x concurrent request volume.

---

## 8. Lua scripts (atomicity)

Critical operations (token bucket, dedup, reservation) MUST be atomic. Use Lua scripts.

### Per ADR-13: Time-source fix

```lua
-- CRITICAL: Use redis.call('TIME') (Redis-server time), NOT gateway wall-clock
-- This avoids drift if gateway and Redis clocks desync
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
```

### Why Lua?

Redis is single-threaded. Lua scripts run atomically — no other commands execute in between. This is critical for:
- Token bucket rate limit (read + increment + check)
- Reservation (read inventory + decrement + insert reservation, all-or-nothing)
- Idempotency key check + insert (avoid race condition)

---

## 9. Monitoring

### Metrics to expose

| Metric | Type | What |
|---|---|---|
| `redis_memory_used_bytes` | Gauge | Memory pressure |
| `redis_connected_clients` | Gauge | Connection count |
| `redis_cache_hits_total` | Counter | Cache effectiveness |
| `redis_cache_misses_total` | Counter | Cache effectiveness (inverse) |
| `redis_command_duration_seconds` | Histogram | Redis command latency |
| `cache_key_evictions_total` | Counter | Memory pressure events |

### Alerts

| Alert | Condition | Action |
|---|---|---|
| `RedisMemoryHigh` | > 80% used | Increase memory or flush rate-limit keys |
| `RedisHitRateLow` | < 50% hit rate over 5 min | Investigate cache key TTL / pattern |
| `RedisCommandsSlow` | p99 > 50ms | Network or Redis overload |
| `RedisDisconnected` | connections < 1 for 1 min | Page on-call (critical) |

---

## 10. Backup + recovery

```bash
# Daily snapshot (automated)
redis-cli BGSAVE
# Writes to /var/lib/redis/dump.rdb

# Restore
# 1. Stop Redis
# 2. Replace dump.rdb
# 3. Start Redis

# Replicate to read-only (for disaster recovery)
# In production, use Redis Sentinel or Cluster with replicas
```

Per `DEVOPS-RUNBOOK.md` §15: Redis backups retained 7 days.

---

## 11. Cross-cutting concerns

### Multi-region

In production, use Redis cluster with replicas in multiple regions. For read-mostly workloads (catalog cache), use replicas in each region for low-latency reads.

### Eviction policy

`maxmemory-policy allkeys-lru` — evict least-recently-used keys when memory pressure. Alternative: `volatile-lru` (only evict TTL'd keys). For our case, `allkeys-lru` is appropriate (we have TTLs anyway).

```yaml
# redis.conf
maxmemory 1gb
maxmemory-policy allkeys-lru
```

### Serialization

For complex objects (Product JSON), use Jackson or Spring's `GenericJackson2JsonRedisSerializer`. For simple types (count, id), use StringRedisTemplate.

---

## 12. Cross-references

- **Operational runbook:** `DEVOPS-RUNBOOK.md` §6 (Redis operations)
- **Alerting:** `ALERTING-RUNBOOK.md` §3 (RedisOOMM)
- **Architecture (rate-limit):** `architecture-detail.md` §"Detail: ADR-13" (Lua with redis.call('TIME'))
- **Architecture (card-testing):** `architecture-detail.md` §"Detail: ADR-24"
- **Risk R-11:** `RISK-REGISTER.md` (Redis OOM)
- **Data model (Redis):** `DATA-MODEL.md` §3 (per-service)
- **Sprint work:** `SPRINT-1-DEV-HANDBOOK.md` + `EPIC-1-STORIES-QUICKREF.md`
- **Onboarding:** `AGENT-ONBOARDING.md`
- **Glossary:** `GLOSSARY.md`
