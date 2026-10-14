-- Atomic sliding-window-log rate limiter.
--
-- KEYS[1] = bucket key (e.g. "login:ip:<ip>")
-- ARGV[1] = now, in milliseconds
-- ARGV[2] = window size, in milliseconds
-- ARGV[3] = limit (max requests allowed per window)
-- ARGV[4] = unique member id for this request (e.g. "<now>-<uuid>")
--
-- Algorithm: a Redis sorted set holds one member per request in the current window, scored by
-- its request timestamp. Every call first evicts members older than (now - window), then checks
-- whether the remaining count is still under the limit. Only an ALLOWED request adds its own
-- member — a rejected request never counts against its own future quota — which keeps the set's
-- size bounded by `limit` rather than growing without bound. PEXPIRE on the key bounds its
-- lifetime to the window size so an idle bucket is reclaimed automatically.
--
-- Returns {allowed (1/0), remaining, retry_after_ms}. Running all of this as one EVAL makes the
-- whole read-trim-count-decide-write sequence atomic — no other client's command can interleave.

local key = KEYS[1]
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])
local member = ARGV[4]

redis.call('ZREMRANGEBYSCORE', key, '-inf', now - window)
local count = redis.call('ZCARD', key)

if count < limit then
    redis.call('ZADD', key, now, member)
    redis.call('PEXPIRE', key, window)
    return {1, limit - count - 1, 0}
end

local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
local retryAfterMs = window
if oldest[2] ~= nil then
    retryAfterMs = (tonumber(oldest[2]) + window) - now
    if retryAfterMs < 0 then
        retryAfterMs = 0
    end
end

return {0, 0, retryAfterMs}
