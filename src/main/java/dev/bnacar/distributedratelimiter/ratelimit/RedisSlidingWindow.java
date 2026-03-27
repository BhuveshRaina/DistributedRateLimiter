package dev.bnacar.distributedratelimiter.ratelimit;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.core.io.ClassPathResource;

import java.util.Collections;
import java.util.List;

/**
 * Redis-based distributed sliding window implementation.
 * Uses Redis Sorted Sets (ZSET) and Lua scripts for atomic operations.
 */
public class RedisSlidingWindow implements RateLimiter {
    
    private final String key;
    private final int capacity;
    private final int refillRate;
    private final long windowSizeMs;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisScript<List> slidingWindowScript;
    
    public RedisSlidingWindow(String key, int capacity, int refillRate, RedisTemplate<String, Object> redisTemplate) {
        this.key = "sliding_window:" + key;
        this.capacity = Math.max(capacity, refillRate);
        this.refillRate = refillRate;
        this.windowSizeMs = 1000; // 1 second sliding window
        this.redisTemplate = redisTemplate;
        
        // Load Lua script
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/sliding-window.lua"));
        script.setResultType(List.class);
        this.slidingWindowScript = script;
    }
    
    @Override
    public boolean tryConsume(int tokens) {
        if (tokens <= 0) {
            return false;
        }
        
        try {
            long currentTime = System.currentTimeMillis();
            List<Object> result = redisTemplate.execute(
                slidingWindowScript,
                Collections.singletonList(key),
                capacity, windowSizeMs, tokens, currentTime
            );
            
            if (result != null && !result.isEmpty()) {
                // Result: {success, current_count, capacity, window_size, current_time}
                Number success = (Number) result.get(0);
                return success != null && success.intValue() == 1;
            }
            
            return false;
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    public int getCurrentTokens() {
        try {
            long currentTime = System.currentTimeMillis();
            List<Object> result = redisTemplate.execute(
                slidingWindowScript,
                Collections.singletonList(key),
                capacity, windowSizeMs, 0, currentTime
            );
            
            if (result != null && result.size() >= 2) {
                Number currentCount = (Number) result.get(1);
                return Math.max(0, capacity - (currentCount != null ? currentCount.intValue() : 0));
            }
            
            return capacity;
        } catch (Exception e) {
            return capacity;
        }
    }
    
    @Override
    public int getCapacity() {
        return capacity;
    }
    
    @Override
    public int getRefillRate() {
        return refillRate;
    }
    
    @Override
    public long getLastRefillTime() {
        return System.currentTimeMillis();
    }
}
