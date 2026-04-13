package dev.bnacar.distributedratelimiter.adaptive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ultra-Responsive User Metrics Modeler.
 * Uses 5-second micro-buckets to provide high-precision RPS calculation.
 */
@Component
public class UserMetricsModeler {
    
    private static final Logger logger = LoggerFactory.getLogger(UserMetricsModeler.class);
    private static final String METRICS_PREFIX = "ratelimiter:metrics:v10:";
    private static final int BUCKET_SIZE_SEC = 5;
    private static final int WINDOW_SIZE_SEC = 60;
    private static final int BUCKET_COUNT = WINDOW_SIZE_SEC / BUCKET_SIZE_SEC; // 12 buckets
    
    private final RedisTemplate<String, Object> redisTemplate;

    @Autowired
    public UserMetricsModeler(@Autowired(required = false) @Qualifier("rateLimiterRedisTemplate") RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }
    
    public void recordRequest(String key, int tokensRequested, boolean allowed) {
        if (redisTemplate == null) return;

        long currentBucket = Instant.now().getEpochSecond() / BUCKET_SIZE_SEC;
        String baseKey = METRICS_PREFIX + key + ":" + currentBucket;
        String field = allowed ? "allowed" : "denied";

        try {
            redisTemplate.opsForHash().increment(baseKey, field, tokensRequested);
            redisTemplate.expire(baseKey, java.time.Duration.ofMinutes(2));
        } catch (Exception e) {
            logger.debug("Failed to record request metrics: {}", e.getMessage());
        }
    }

    
    public Map<String, UserMetrics> fetchAndCalculateAllMetrics(Set<String> allKeys) {
        if (redisTemplate == null || allKeys == null || allKeys.isEmpty()) {
            return new HashMap<>();
        }

        long currentBucket = Instant.now().getEpochSecond() / BUCKET_SIZE_SEC;
        Map<String, double[]> rawRates = new HashMap<>();
        List<Double> allRps = new ArrayList<>();

        for (String userKey : allKeys) {
            long totalAllowed = 0;
            long totalDenied = 0;

            // Fetch the last 12 buckets for this user
            for (int i = 0; i < BUCKET_COUNT; i++) {
                String bucketKey = METRICS_PREFIX + userKey + ":" + (currentBucket - i);
                Map<Object, Object> bucketMap = redisTemplate.opsForHash().entries(bucketKey);
                
                totalAllowed += parseLong(bucketMap.get("allowed"));
                totalDenied += parseLong(bucketMap.get("denied"));
            }

            double total = totalAllowed + totalDenied;
            double rps = total / (double) WINDOW_SIZE_SEC;
            double denialRate = total > 0 ? (double) totalDenied / total : 0.0;
            
            rawRates.put(userKey, new double[]{rps, denialRate});
            if (rps > 0.01) { 
                allRps.add(rps);
            }
        }

        // Calculate Population Stats
        double mean = 0.0;
        double stdDev = 1.0;
        if (!allRps.isEmpty()) {
            double sum = 0;
            for (double rps : allRps) sum += rps;
            mean = sum / allRps.size();
            if (allRps.size() > 1) {
                double sumSq = 0;
                for (double rps : allRps) sumSq += Math.pow(rps - mean, 2);
                stdDev = Math.max(1.0, Math.sqrt(sumSq / allRps.size()));
            }
            logger.info("[STATS] Active Users: {} | Avg RPS: {} | StdDev: {}", allRps.size(), String.format("%.2f", mean), String.format("%.2f", stdDev));
        }

        Map<String, UserMetrics> finalMetrics = new HashMap<>();
        for (Map.Entry<String, double[]> entry : rawRates.entrySet()) {
            double rps = entry.getValue()[0];
            double zScore = (rps - mean) / stdDev;
            finalMetrics.put(entry.getKey(), UserMetrics.builder()
                .currentRequestRate(rps)
                .denialRate(entry.getValue()[1])
                .zScore(zScore)
                .build());
        }

        return finalMetrics;
    }

    private long parseLong(Object val) {
        if (val == null) return 0L;
        if (val instanceof Long) return (Long) val;
        if (val instanceof Integer) return ((Integer) val).longValue();
        try { return Long.parseLong(val.toString()); } catch (Exception e) { return 0L; }
    }

    public UserMetrics getUserMetrics(String key) {
        if (redisTemplate == null) return UserMetrics.builder().build();
        
        long currentBucket = Instant.now().getEpochSecond() / BUCKET_SIZE_SEC;
        long total = 0;
        long denied = 0;

        for (int i = 0; i < BUCKET_COUNT; i++) {
            String bucketKey = METRICS_PREFIX + key + ":" + (currentBucket - i);
            Map<Object, Object> map = redisTemplate.opsForHash().entries(bucketKey);
            total += (parseLong(map.get("allowed")) + parseLong(map.get("denied")));
            denied += parseLong(map.get("denied"));
        }

        return UserMetrics.builder()
            .currentRequestRate(total / (double) WINDOW_SIZE_SEC)
            .denialRate(total > 0 ? (double) denied / total : 0.0)
            .zScore(0.0)
            .build();
    }
}