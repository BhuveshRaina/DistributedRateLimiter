local bucket_key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local tokens_to_consume = tonumber(ARGV[3])
local current_time = tonumber(ARGV[4])

local bucket_data = redis.call('HMGET', bucket_key, 'tokens', 'last_refill')
local current_tokens = tonumber(bucket_data[1])
local last_refill = tonumber(bucket_data[2])

if current_tokens == nil then
    current_tokens = capacity
    last_refill = current_time
end

-- Refill logic: Calculate time since last refill
local time_elapsed = math.max(0, current_time - last_refill)
local tokens_to_add = (time_elapsed / 1000.0) * refill_rate

-- High precision refill: Add fractional tokens and always update time
if tokens_to_add > 0 then
    current_tokens = math.min(capacity, current_tokens + tokens_to_add)
    last_refill = current_time
end

local success = 0
if tokens_to_consume > 0 and current_tokens >= tokens_to_consume then
    current_tokens = current_tokens - tokens_to_consume
    success = 1
elseif tokens_to_consume == 0 then
    success = 1
end

redis.call('HMSET', bucket_key, 'tokens', current_tokens, 'last_refill', last_refill)
redis.call('EXPIRE', bucket_key, 86400)

return {success, math.floor(current_tokens), capacity, refill_rate, last_refill}
