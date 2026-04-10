package dev.bnacar.distributedratelimiter.adaptive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PRODUCTION-READY User Metrics Modeler.
 * Uses Atomic Counters and Pipelining to achieve O(1) network overhead 
 * regardless of the number of users.
 */
@Component
public class UserMetricsModeler {
    
    private static final Logger logger = LoggerFactory.getLogger(UserMetricsModeler.class);
    private static final String METRICS_PREFIX = "ratelimiter:metrics:v2:";
    private static final int WINDOW_SECONDS = 60;
    
    private final RedisTemplate<String, Object> redisTemplate;

    @Autowired
    public UserMetricsModeler(@Autowired(required = false) @Qualifier("rateLimiterRedisTemplate") RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }
    
    /**
     * Record a request event using Atomic Counters (O(1) memory).
     */
    public void recordRequest(String key, int tokensRequested, boolean allowed) {
        if (redisTemplate == null) return;

        long currentMinute = Instant.now().getEpochSecond() / 60;
        String baseKey = METRICS_PREFIX + key + ":" + currentMinute;
        String field = allowed ? "allowed" : "denied";

        redisTemplate.opsForHash().increment(baseKey, field, tokensRequested);
        redisTemplate.expire(baseKey, Duration.ofMinutes(5));
    }

    /**
     * ULTIMATE OPTIMIZATION: 
     * Fetches ALL keys via Pipeline, calculates Mean/StdDev in memory, 
     * and returns the fully built UserMetrics for every user in a single method.
     * ZERO N+1 queries.
     */
    public Map<String, UserMetrics> fetchAndCalculateAllMetrics(Set<String> allKeys) {
        if (redisTemplate == null || allKeys == null || allKeys.isEmpty()) {
            return new HashMap<>();
        }

        long currentMinute = Instant.now().getEpochSecond() / 60;
        List<String> keysList = new ArrayList<>(allKeys);

        // 1. ONE NETWORK CALL: Fetch all hashes via Pipeline
        List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (String userKey : keysList) {
                byte[] redisKey = (METRICS_PREFIX + userKey + ":" + currentMinute).getBytes();
                connection.hGetAll(redisKey);
            }
            return null;
        });

        // 2. IN-MEMORY: Parse the pipeline results
        Map<String, double[]> rawRates = new HashMap<>(); // Holds [rps, denialRate]
        List<Double> allRps = new ArrayList<>();

        for (int i = 0; i < results.size(); i++) {
            Object result = results.get(i);
            String userKey = keysList.get(i);
            
            long allowed = 0;
            long denied = 0;

            if (result instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<byte[], byte[]> hash = (Map<byte[], byte[]>) result;
                allowed = parseLong(hash.get("allowed".getBytes()));
                denied = parseLong(hash.get("denied".getBytes()));
            }

            long total = allowed + denied;
            double rps = (double) total / WINDOW_SECONDS;
            double denialRate = total > 0 ? (double) denied / total : 0.0;
            
            rawRates.put(userKey, new double[]{rps, denialRate});
            if (rps > 0) allRps.add(rps);
        }

        // 3. IN-MEMORY: Calculate Global Stats
        double mean = 0.0;
        double stdDev = 1.0;

        if (!allRps.isEmpty()) {
            double sum = 0;
            for (double rps : allRps) sum += rps;
            mean = sum / allRps.size();

            if (allRps.size() > 1) {
                double sumSq = 0;
                for (double rps : allRps) sumSq += Math.pow(rps - mean, 2);
                stdDev = Math.sqrt(sumSq / allRps.size());
                if (stdDev == 0) stdDev = 1.0;
            }
        }

        // 4. IN-MEMORY: Build final UserMetrics with Z-Scores
        Map<String, UserMetrics> finalMetrics = new HashMap<>();
        for (Map.Entry<String, double[]> entry : rawRates.entrySet()) {
            double rps = entry.getValue()[0];
            double denialRate = entry.getValue()[1];
            double zScore = (rps - mean) / stdDev;

            finalMetrics.put(entry.getKey(), UserMetrics.builder()
                .currentRequestRate(rps)
                .denialRate(denialRate)
                .zScore(zScore)
                .build());
        }

        logger.debug("GLOBAL STATS: Mean={}/s, StdDev={}/s, ActiveUsers={}", mean, stdDev, allRps.size());

        return finalMetrics;
    }

    private long parseLong(Object val) {
        if (val == null) return 0L;
        if (val instanceof Long) return (Long) val;
        if (val instanceof byte[]) return Long.parseLong(new String((byte[]) val));
        try { return Long.parseLong(val.toString()); } catch (Exception e) { return 0L; }
    }

    /**
     * Get aggregated user metrics for a single key (Fallback).
     */
    public UserMetrics getUserMetrics(String key) {
        long currentMinute = Instant.now().getEpochSecond() / 60;
        String redisKey = METRICS_PREFIX + key + ":" + currentMinute;
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(redisKey);
        
        long allowed = parseLong(entries.get("allowed"));
        long denied = parseLong(entries.get("denied"));
        long total = allowed + denied;
        
        return UserMetrics.builder()
            .currentRequestRate((double) total / WINDOW_SECONDS)
            .denialRate(total > 0 ? (double) denied / total : 0.0)
            .zScore(1.0)
            .build();
    }
}
