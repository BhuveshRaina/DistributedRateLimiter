package dev.bnacar.distributedratelimiter.ratelimit;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.core.io.ClassPathResource;

import java.util.Collections;
import java.util.List;

/**
 * Redis-based distributed leaky bucket implementation.
 * Uses Lua scripts for atomic operations.
 * For leaky bucket, capacity is the bucket/queue size, and refillRate is the leak rate.
 */
public class RedisLeakyBucket implements RateLimiter {
    
    private final String key;
    private final int capacity;
    private final int leakRate;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisScript<List> leakyBucketScript;
    
    public RedisLeakyBucket(String key, int capacity, int leakRate, RedisTemplate<String, Object> redisTemplate) {
        this.key = key;
        this.capacity = capacity;
        this.leakRate = leakRate;
        this.redisTemplate = redisTemplate;
        
        // Load Lua script
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/leaky-bucket.lua"));
        script.setResultType(List.class);
        this.leakyBucketScript = script;
    }
    
    @Override
    public ConsumptionResult tryConsumeWithResult(int tokens) {
        if (tokens <= 0) {
            return new ConsumptionResult(false, getCurrentTokens());
        }
        
        try {
            long currentTime = System.currentTimeMillis();
            List<Object> result = redisTemplate.execute(
                leakyBucketScript,
                Collections.singletonList(key),
                capacity, leakRate, tokens, currentTime
            );
            
            if (result != null && result.size() >= 2) {
                // Result format: {allowed, remaining_capacity, capacity, leak_rate, last_leak}
                Object allowedValue = result.get(0);
                Object remainingValue = result.get(1);
                
                boolean allowed = false;
                if (allowedValue instanceof Number) {
                    allowed = ((Number) allowedValue).intValue() == 1;
                }
                
                int remaining = 0;
                if (remainingValue instanceof Number) {
                    remaining = ((Number) remainingValue).intValue();
                }
                
                return new ConsumptionResult(allowed, remaining, capacity);
            }
            
            return new ConsumptionResult(false, 0, capacity);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(RedisLeakyBucket.class)
                .error("Redis operation failed for key {}: {}", key, e.getMessage());
            return new ConsumptionResult(false, 0, capacity);
        }
    }
    
    @Override
    public boolean tryConsume(int tokens) {
        return tryConsumeWithResult(tokens).allowed;
    }
    
    @Override
    public int getCurrentTokens() {
        try {
            long currentTime = System.currentTimeMillis();
            // Use a dummy consume of 0 tokens to get current state
            List<Object> result = redisTemplate.execute(
                leakyBucketScript,
                Collections.singletonList(key),
                capacity, leakRate, 0, currentTime
            );
            
            if (result != null && result.size() >= 2) {
                Object remainingValue = result.get(1);
                if (remainingValue instanceof Number) {
                    return ((Number) remainingValue).intValue();
                }
            }
            
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public void setCurrentTokens(int tokens) {
        try {
            // For leaky bucket, we set the 'level' which is capacity - tokens
            int level = Math.max(0, capacity - tokens);
            redisTemplate.opsForHash().put(key, "level", level);
            redisTemplate.opsForHash().put(key, "last_leak", System.currentTimeMillis());
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(RedisLeakyBucket.class)
                .error("Failed to manually set tokens for key {}: {}", key, e.getMessage());
        }
    }
    
    @Override
    public int getCapacity() {
        return capacity;
    }
    
    @Override
    public int getRefillRate() {
        return leakRate;
    }
    
    @Override
    public long getLastRefillTime() {
        try {
            long currentTime = System.currentTimeMillis();
            List<Object> result = redisTemplate.execute(
                leakyBucketScript,
                Collections.singletonList(key),
                capacity, leakRate, 0, currentTime
            );
            
            if (result != null && result.size() >= 5) {
                Object timeValue = result.get(4);
                if (timeValue instanceof Number) {
                    return ((Number) timeValue).longValue();
                }
            }
            
            return System.currentTimeMillis();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }
}
