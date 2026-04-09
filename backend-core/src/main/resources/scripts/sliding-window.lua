-- Redis Lua script for atomic sliding window operations using Sorted Sets (ZSET)
-- KEYS[1]: window zset key
-- ARGV[1]: capacity (max requests per window)
-- ARGV[2]: window_size in milliseconds
-- ARGV[3]: tokens to consume
-- ARGV[4]: current time in milliseconds

local zset_key = KEYS[1]
local capacity = tonumber(ARGV[1])
local window_size = tonumber(ARGV[2])
local tokens_to_consume = tonumber(ARGV[3])
local current_time = tonumber(ARGV[4])

-- Ensure we have valid inputs
if capacity <= 0 or window_size <= 0 or tokens_to_consume < 0 then
    return {-1, -1, capacity, window_size, current_time}
end

-- 1. Remove expired requests from the zset
local window_start = current_time - window_size
redis.call('ZREMRANGEBYSCORE', zset_key, 0, window_start)

-- 2. Get current number of requests in the window
local current_count = redis.call('ZCARD', zset_key)

-- 3. Check if we can consume the requested tokens
local success = 0
if tokens_to_consume == 0 then
    -- This is a query for current state, no consumption
    success = 0
elseif tokens_to_consume > 0 and current_count + tokens_to_consume <= capacity then
    -- Add the new request entries (one per token)
    for i = 1, tokens_to_consume do
        -- Use unique scores (time + tiny offset) to ensure each token is recorded
        -- Redis ZSET elements must be unique, so we add the offset to the member name
        local unique_member = tostring(current_time) .. '-' .. tostring(i) .. '-' .. tostring(math.random())
        redis.call('ZADD', zset_key, current_time, unique_member)
    end
    current_count = current_count + tokens_to_consume
    success = 1
else
    -- Cannot consume, not enough capacity
    success = 0
end

-- 4. Set expiration to cleanup the entire zset if it becomes inactive
redis.call('EXPIRE', zset_key, math.ceil(window_size / 1000) + 3600)

-- 5. Return result: {success, current_count, capacity, window_size, current_time}
return {success, current_count, capacity, window_size, current_time}
