package dev.bnacar.distributedratelimiter.adaptive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Models DISTRIBUTED user metrics using Redis for cross-node visibility.
 */
@Component
public class UserMetricsModeler {
    
    private static final Logger logger = LoggerFactory.getLogger(UserMetricsModeler.class);
    private static final String ADAPTIVE_METRICS_PREFIX = "ratelimiter:adaptive:metrics:";
    private static final Duration CALCULATION_WINDOW = Duration.ofMinutes(1);
    
    private final RedisTemplate<String, Object> redisTemplate;

    @Autowired
    public UserMetricsModeler(@Autowired(required = false) @Qualifier("rateLimiterRedisTemplate") RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }
    
    /**
     * Record a request event in Redis (Shared across all nodes).
     */
    public void recordRequest(String key, int tokensRequested, boolean allowed) {
        if (redisTemplate == null) return;

        String redisKey = ADAPTIVE_METRICS_PREFIX + key;
        long timestamp = Instant.now().toEpochMilli();
        String value = (allowed ? "A" : "D") + ":" + tokensRequested + ":" + timestamp;
        
        // Store in a Sorted Set indexed by timestamp
        redisTemplate.opsForZSet().add(redisKey, value, timestamp);
        
        // Set TTL and cleanup old entries
        redisTemplate.expire(redisKey, Duration.ofMinutes(10));
        long cutoff = Instant.now().minus(CALCULATION_WINDOW.plusMinutes(1)).toEpochMilli();
        redisTemplate.opsForZSet().removeRangeByScore(redisKey, 0, cutoff);
    }
    
    /**
     * Get aggregated user metrics for a key from Redis.
     */
    public UserMetrics getUserMetrics(String key) {
        if (redisTemplate == null) {
            return UserMetrics.builder().currentRequestRate(0.0).denialRate(0.0).build();
        }

        String redisKey = ADAPTIVE_METRICS_PREFIX + key;
        long cutoff = Instant.now().minus(CALCULATION_WINDOW).toEpochMilli();
        
        Set<Object> recentEvents = redisTemplate.opsForZSet().rangeByScore(redisKey, cutoff, Double.MAX_VALUE);
        
        if (recentEvents == null || recentEvents.isEmpty()) {
            return UserMetrics.builder().currentRequestRate(0.0).denialRate(0.0).build();
        }
        
        long allowedCount = 0;
        long deniedCount = 0;
        
        for (Object eventObj : recentEvents) {
            String event = eventObj.toString();
            if (event.startsWith("A:")) allowedCount++;
            else if (event.startsWith("D:")) deniedCount++;
        }
        
        long total = allowedCount + deniedCount;
        double currentRequestRate = (double) total / CALCULATION_WINDOW.toSeconds();
        double denialRate = total > 0 ? (double) deniedCount / total : 0.0;
        
        return UserMetrics.builder()
            .currentRequestRate(currentRequestRate)
            .denialRate(denialRate)
            .build();
    }
}
