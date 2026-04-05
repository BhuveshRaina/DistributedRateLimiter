-- Redis Lua script for atomic leaky bucket operations
-- KEYS[1]: leaky bucket key
-- ARGV[1]: queue capacity (bucket size)
-- ARGV[2]: leak rate per second
-- ARGV[3]: tokens to consume
-- ARGV[4]: current timestamp (ms)

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local leak_rate = tonumber(ARGV[2])
local requested = tonumber(ARGV[3])
local now = tonumber(ARGV[4])

-- Get current state
local state = redis.call('HMGET', key, 'level', 'last_leak')
local current_level = tonumber(state[1]) or 0
local last_leak = tonumber(state[2]) or now

-- Calculate leaked tokens since last operation
local elapsed = math.max(0, now - last_leak)
local leaked = elapsed * (leak_rate / 1000.0)

-- Update current level after leaking
current_level = math.max(0, current_level - leaked)

local allowed = 0
if current_level + requested <= capacity then
    current_level = current_level + requested
    allowed = 1
end

-- Save state
redis.call('HMSET', key, 'level', current_level, 'last_leak', now)
-- Set TTL to 1 hour to clean up unused buckets
redis.call('EXPIRE', key, 3600)

return {allowed, math.floor(capacity - current_level), capacity, leak_rate, now}
