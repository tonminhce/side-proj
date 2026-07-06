// 04-rate-limiter-design.md
# 4. Rate Limiter Design

A custom `GatewayFilter` in Spring Cloud Gateway implementing a Token Bucket algorithm with combined RBAC and IP-Based policies, backed by Redis for distributed state.

## Algorithm & Combined Policies
Each bucket has `capacity`, `refillRate`, `tokens`, and `lastRefillTimestamp`.

Policies applied:
1. **RBAC-based:** Buckets keyed by user role from JWT (ANONYMOUS, USER, ADMIN, SELLER).
2. **IP-based:** Buckets keyed by client IP (from `X-Forwarded-For` or direct connection). Protects against abuse regardless of auth.
3. **Per-endpoint overrides:** Specific paths/methods can override default limits (e.g., stricter limits on `/login`).
4. **Strict evaluation:** A request is allowed *only if* all applicable buckets have tokens. If any bucket denies, no tokens are consumed from any bucket (prevents attackers burning budgets on a blocking bottleneck).
5. **Cost-weighted tokens:** Endpoints have a cost multiplier (e.g., `/search` costs 5 tokens, `/products/{id}` costs 1).

## Evaluation Order
1. **Resolve route** (path + method).
2. **Extract identity** (JWT role, IP, signed anonymous device cookie `rl_did`).
3. **Build bucket key list** (IP, Route-IP, Role, User, Route-Role).
4. **Compute cost** based on route weight.
5. **Atomic multi-bucket check** via single Redis Lua `EVALSHA` call.
6. **Headers:** Emit standard `RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset`. On 429, emit `Retry-After`.
7. **Observability:** Micrometer counters tagged by `bucket_type`, `route`, `decision`.

## Lua Script (Atomic Multi-Bucket Check)
Two-phase peek-then-commit runs single-threaded in Redis, preventing race conditions and partial consumption.

```lua
-- KEYS[1..N]   = bucket keys
-- ARGV[1]      = capacity
-- ARGV[2]      = refill_rate (tokens/sec)
-- ARGV[3]      = now_ms
-- ARGV[4]      = ttl_sec
-- ARGV[5]      = cost (tokens requested)
-- ARGV[6]      = N (number of buckets)
-- Returns: {allowed(0/1), min_remaining, max_retry_after, bucket_idx_that_blocked}

local capacity = tonumber(ARGV[1])
local refill   = tonumber(ARGV[2])
local now_ms   = tonumber(ARGV[3])
local ttl      = tonumber(ARGV[4])
local cost     = tonumber(ARGV[5])
local n        = tonumber(ARGV[6])

-- Phase 1: peek every bucket, find the limiting one
local min_remaining = capacity
local blocked_idx = 0
local states = {}

for i = 1, n do
  local b = redis.call('HMGET', KEYS[i], 'tokens', 'last')
  local tokens = tonumber(b[1])
  local last   = tonumber(b[2])
  if tokens == nil then tokens = capacity; last = now_ms end
  local elapsed = now_ms - last
  if elapsed < 0 then elapsed = 0 end
  tokens = math.min(capacity, tokens + (elapsed / 1000.0) * refill)
  states[i] = {tokens = tokens, last = now_ms}
  if tokens < cost then
    if blocked_idx == 0 then blocked_idx = i end
  end
  if tokens < min_remaining then min_remaining = tokens end
end

local allowed = 1
local retry_after = 0

if blocked_idx > 0 then
  allowed = 0
  local bt = states[blocked_idx].tokens
  retry_after = math.ceil((cost - bt) / refill)
else
  -- Phase 2: commit consumption on every bucket
  for i = 1, n do
    states[i].tokens = states[i].tokens - cost
    redis.call('HMSET', KEYS[i], 'tokens', states[i].tokens, 'last', now_ms)
    redis.call('EXPIRE', KEYS[i], ttl)
  end
end

return {allowed, math.floor(min_remaining - (allowed * cost)), retry_after, blocked_idx}