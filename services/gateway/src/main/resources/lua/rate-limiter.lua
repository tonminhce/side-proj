-- rate-limiter.lua — Story 3.4 / FR-81 / R-05 / ADR-13
--
-- Atomic multi-bucket Token Bucket rate-limiter. Two-phase peek-then-commit prevents partial
-- consumption when one bucket denies (so an attacker can't burn budgets on a blocking bottleneck).
-- Per ADR-13, the canonical fix: now_ms is server-side via redis.call('TIME') — the original
-- local-docs/04 spec took it as ARGV[3] (client-supplied) which breaks under multi-region deploys.
--
-- KEYS[1..N]   = bucket keys (e.g. rl:ip:1.2.3.4, rl:fp:abc..., rl:asn:AS0_VN)
-- ARGV[1]      = capacity
-- ARGV[2]      = refill_rate (tokens/sec)
-- ARGV[3]      = ttl_sec
-- ARGV[4]      = cost (tokens requested)
-- ARGV[5]      = N (bucket count)
-- Returns: {allowed(0/1), min_remaining_after, retry_after_seconds, blocked_idx (1-based, 0 if none)}

local capacity   = tonumber(ARGV[1])
local refill     = tonumber(ARGV[2])
local ttl        = tonumber(ARGV[3])
local cost       = tonumber(ARGV[4])
local n          = tonumber(ARGV[5])

-- ADR-13: server-side time, single source of truth across regions.
local now_arr = redis.call('TIME')
local now_ms  = tonumber(now_arr[1]) * 1000 + math.floor(tonumber(now_arr[2]) / 1000)

-- Phase 1: peek every bucket, find the limiting one
local min_remaining = capacity
local blocked_idx   = 0
local states        = {}

for i = 1, n do
  local b = redis.call('HMGET', KEYS[i], 'tokens', 'last')
  local tokens = tonumber(b[1])
  local last   = tonumber(b[2])
  if tokens == nil then tokens = capacity; last = now_ms end
  local elapsed = now_ms - last
  if elapsed < 0 then elapsed = 0 end
  tokens = math.min(capacity, tokens + (elapsed / 1000.0) * refill)
  states[i] = { tokens = tokens, last = now_ms }
  if tokens < cost and blocked_idx == 0 then
    blocked_idx = i
  end
end

local allowed     = 1
local retry_after = 0

if blocked_idx > 0 then
  allowed = 0
  local bt = states[blocked_idx].tokens
  if refill > 0 then
    retry_after = math.ceil((cost - bt) / refill)
  else
    retry_after = 60
  end
  -- For a blocked bucket, the post-call state is "still 0 remaining" (the request was rejected
  -- and no tokens were consumed). For the other (non-blocking) buckets, the post-call state
  -- is tokens - cost. The honest min_remaining therefore comes from the blocking bucket's
  -- current tokens (the user-visible "how long until I can retry" signal) when the request
  -- is blocked, and from the post-commit (tokens - cost) when allowed. Story 3.4 LOW-2
  -- honesty fix: report the un-rounded current tokens for the blocking bucket and the
  -- post-commit (tokens - cost) for the rest, so the user-facing RateLimit-Remaining header
  -- (which the filter caps at Math.max(0, remaining)) reflects the actual reservation.
  for i = 1, n do
    local post = states[i].tokens - (allowed * cost)
    if i == blocked_idx then post = states[i].tokens end
    if post < min_remaining then min_remaining = post end
  end
else
  -- Phase 2: commit consumption on every bucket
  for i = 1, n do
    states[i].tokens = states[i].tokens - cost
    redis.call('HMSET', KEYS[i], 'tokens', states[i].tokens, 'last', now_ms)
    redis.call('EXPIRE', KEYS[i], ttl)
    local post = states[i].tokens
    if post < min_remaining then min_remaining = post end
  end
end

return { allowed, math.floor(min_remaining), retry_after, blocked_idx }