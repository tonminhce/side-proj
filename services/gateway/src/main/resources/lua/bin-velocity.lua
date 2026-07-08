-- bin-velocity.lua — Story 3.4 / FR-81 / R-05 / ADR-24
--
-- BIN velocity check: counts attempts against a BIN within a sliding window. Backed by a Redis
-- sorted set per BIN+window-start; members are attempt timestamps (ms), scores are the same.
-- ZREMRANGEBYSCORE trims entries outside the window, ZCARD returns the count.
--
-- KEYS[1] = rl:bin:<bin>:<window_start_ms>
-- ARGV[1] = now_ms
-- ARGV[2] = window_ms
-- Returns: count (integer)

local now_ms      = tonumber(ARGV[1])
local window_ms   = tonumber(ARGV[2])
local cutoff_ms   = now_ms - window_ms

redis.call('ZADD', KEYS[1], now_ms, now_ms)
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', cutoff_ms)
local count = redis.call('ZCARD', KEYS[1])

-- Set TTL slightly larger than the window so abandoned keys expire automatically.
redis.call('PEXPIRE', KEYS[1], window_ms + 60000)

return count