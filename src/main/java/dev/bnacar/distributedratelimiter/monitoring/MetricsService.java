package dev.bnacar.distributedratelimiter.monitoring;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bnacar.distributedratelimiter.models.MetricsResponse;
import dev.bnacar.distributedratelimiter.ratelimit.RateLimitAlgorithm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.time.Duration;
import java.util.HashMap;

@Service
public class MetricsService {
    
    private static final Logger logger = LoggerFactory.getLogger(MetricsService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper(); // For JSON serialization/deserialization

    // Redis Key Constants

    private static final String GLOBAL_METRICS_KEY = "ratelimiter:metrics:global";

    private static final String KEY_METRICS_PREFIX = "ratelimiter:metrics:key:";

    private static final String ACTIVE_KEYS_PREFIX = "ratelimiter:active:"; // Used for TTL tracking

    private static final String ALGORITHM_METRICS_PREFIX = "ratelimiter:metrics:algo:"; // Algo metrics will be kept in memory for quick updates

    public static final String RECENT_EVENTS_LIST = "ratelimiter:metrics:events";

    public static final long MAX_RECENT_EVENTS = 50; // Max number of events to keep in Redis list



    // These global totals will be primarily managed in Redis, but kept in-memory copies for faster dashboard reads.
    private final AtomicLong totalAllowedRequests = new AtomicLong(0);
    private final AtomicLong totalDeniedRequests = new AtomicLong(0);
    private final AtomicLong totalProcessingTimeMs = new AtomicLong(0);

    // Algorithm metrics are updated frequently, so keep in-memory for speed
    private final Map<RateLimitAlgorithm, AlgorithmMetricsData> algoMetrics = new ConcurrentHashMap<>();

    // Key metrics for individual keys
    private final Map<String, KeyMetricsData> keyMetrics = new ConcurrentHashMap<>();
    
    // Recent events for activity feed
    private final ConcurrentLinkedDeque<MetricsResponse.RateLimitEvent> recentEvents = new ConcurrentLinkedDeque<>();

    private final RedisTemplate<String, Object> redisTemplate;
    
    // Cache for aggregated metrics to avoid Redis pressure
    private volatile MetricsResponse cachedMetrics;
    private volatile long lastCacheTime = 0;
    private static final long CACHE_DURATION_MS = 100;

    @Value("${ratelimiter.metrics.active-key-ttl-seconds:300}")
    private long activeKeyTtlSeconds;

    private final RedisConnectionFactory redisConnectionFactory;
    private volatile boolean redisConnected = false;
    private ScheduledExecutorService healthCheckExecutor;

    private static class AlgorithmMetricsData {
        final AtomicLong allowedRequests = new AtomicLong(0);
        final AtomicLong deniedRequests = new AtomicLong(0);
        final AtomicLong totalProcessingTime = new AtomicLong(0);
    }
    
    private static class KeyMetricsData {
        final AtomicLong allowedRequests = new AtomicLong(0);
        final AtomicLong deniedRequests = new AtomicLong(0);
        final AtomicLong totalProcessingTime = new AtomicLong(0);
        volatile long lastAccessTime = System.currentTimeMillis();
        
        void updateAccessTime() {
            this.lastAccessTime = System.currentTimeMillis();
        }
    }

    @Autowired
    public MetricsService(@Qualifier("rateLimiterRedisTemplate") RedisTemplate<String, Object> redisTemplate,
                          RedisConnectionFactory redisConnectionFactory) {
        this.redisTemplate = redisTemplate;
        this.redisConnectionFactory = redisConnectionFactory;
        
        for (RateLimitAlgorithm algo : RateLimitAlgorithm.values()) {
            algoMetrics.put(algo, new AlgorithmMetricsData());
        }
    }

    @PostConstruct
    public void initialize() {
        loadGlobalMetricsFromRedis();
        
        healthCheckExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Redis-Health-Check");
            t.setDaemon(true);
            return t;
        });
        
        healthCheckExecutor.scheduleWithFixedDelay(this::checkRedisHealth, 0, 30, TimeUnit.SECONDS);
    }

    private void loadGlobalMetricsFromRedis() {
        Map<Object, Object> globalData = redisTemplate.opsForHash().entries(GLOBAL_METRICS_KEY);
        if (globalData != null && !globalData.isEmpty()) {
            totalAllowedRequests.set(getLongValue(globalData.get("totalAllowedRequests")));
            totalDeniedRequests.set(getLongValue(globalData.get("totalDeniedRequests")));
            totalProcessingTimeMs.set(getLongValue(globalData.get("totalProcessingTimeMs")));
        }
        logger.info("Loaded global metrics from Redis. Allowed: {}, Denied: {}", totalAllowedRequests.get(), totalDeniedRequests.get());
    }
    
    private long getLongValue(Object value) {
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                return 0L;
            }
        } else if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return 0L;
    }

    private void checkRedisHealth() {
        if (redisConnectionFactory != null) {
            try {
                redisConnectionFactory.getConnection().ping();
                if (!redisConnected) {
                    logger.info("Redis connection restored");
                }
                setRedisConnected(true);
            } catch (Exception e) {
                if (redisConnected) {
                    logger.error("Redis connection lost: {}", e.getMessage());
                }
                setRedisConnected(false);
            }
        }
    }

    public void setRedisConnected(boolean connected) {
        this.redisConnected = connected;
    }

    public boolean isRedisConnected() {
        return redisConnected;
    }

    @PreDestroy
    public void shutdown() {
        if (healthCheckExecutor != null) {
            healthCheckExecutor.shutdown();
        }
        saveGlobalMetricsToRedis();
        logger.info("MetricsService shutdown. Final global metrics saved to Redis.");
    }
    
    private void saveGlobalMetricsToRedis() {
        Map<String, String> globalData = new HashMap<>();
        globalData.put("totalAllowedRequests", String.valueOf(totalAllowedRequests.get()));
        globalData.put("totalDeniedRequests", String.valueOf(totalDeniedRequests.get()));
        globalData.put("totalProcessingTimeMs", String.valueOf(totalProcessingTimeMs.get()));
        redisTemplate.opsForHash().putAll(GLOBAL_METRICS_KEY, globalData);
    }

    public void recordAllowedRequest(String key, RateLimitAlgorithm algorithm) {
        recordAllowedRequest(key, algorithm, 1);
    }

    public void recordAllowedRequest(String key, RateLimitAlgorithm algorithm, int tokens) {
        String keyMetricsHash = KEY_METRICS_PREFIX + key;
        
        redisTemplate.opsForHash().increment(keyMetricsHash, "allowedRequests", 1);
        redisTemplate.opsForHash().put(keyMetricsHash, "lastAccessTime", String.valueOf(System.currentTimeMillis()));

        KeyMetricsData data = keyMetrics.computeIfAbsent(key, k -> new KeyMetricsData());
        data.allowedRequests.incrementAndGet();
        data.updateAccessTime();

        redisTemplate.opsForHash().increment(GLOBAL_METRICS_KEY, "totalAllowedRequests", 1);
        totalAllowedRequests.incrementAndGet(); 
        
        if (algorithm != null) {
            String algoKey = ALGORITHM_METRICS_PREFIX + algorithm.name();
            redisTemplate.opsForHash().increment(algoKey, "allowedRequests", 1);
            
            AlgorithmMetricsData algoData = algoMetrics.get(algorithm);
            if (algoData != null) algoData.allowedRequests.incrementAndGet();
        }
        
        redisTemplate.opsForValue().set(ACTIVE_KEYS_PREFIX + key, "true", Duration.ofSeconds(activeKeyTtlSeconds));
        recordEvent(key, algorithm, true, tokens);
    }

    public void recordDeniedRequest(String key, RateLimitAlgorithm algorithm) {
        recordDeniedRequest(key, algorithm, 1);
    }

    public void recordDeniedRequest(String key, RateLimitAlgorithm algorithm, int tokens) {
        String keyMetricsHash = KEY_METRICS_PREFIX + key;

        redisTemplate.opsForHash().increment(keyMetricsHash, "deniedRequests", 1);
        redisTemplate.opsForHash().put(keyMetricsHash, "lastAccessTime", String.valueOf(System.currentTimeMillis()));

        KeyMetricsData data = keyMetrics.computeIfAbsent(key, k -> new KeyMetricsData());
        data.deniedRequests.incrementAndGet();
        data.updateAccessTime();

        redisTemplate.opsForHash().increment(GLOBAL_METRICS_KEY, "totalDeniedRequests", 1);
        totalDeniedRequests.incrementAndGet(); 

        if (algorithm != null) {
            String algoKey = ALGORITHM_METRICS_PREFIX + algorithm.name();
            redisTemplate.opsForHash().increment(algoKey, "deniedRequests", 1);
            
            AlgorithmMetricsData algoData = algoMetrics.get(algorithm);
            if (algoData != null) algoData.deniedRequests.incrementAndGet();
        }
        
        redisTemplate.opsForValue().set(ACTIVE_KEYS_PREFIX + key, "true", Duration.ofSeconds(activeKeyTtlSeconds));
        recordEvent(key, algorithm, false, tokens);
    }

    private void recordEvent(String key, RateLimitAlgorithm algorithm, boolean allowed, int tokens) {
        String id = java.util.UUID.randomUUID().toString();
        MetricsResponse.RateLimitEvent event = new MetricsResponse.RateLimitEvent(
            id, System.currentTimeMillis(), key, algorithm != null ? algorithm.name() : "UNKNOWN", allowed, tokens
        );
        
        recentEvents.addFirst(event);
        if (recentEvents.size() > MAX_RECENT_EVENTS) {
            recentEvents.removeLast();
        }
        
        try {
            redisTemplate.opsForList().leftPush(RECENT_EVENTS_LIST, objectMapper.writeValueAsString(event));
            redisTemplate.opsForList().trim(RECENT_EVENTS_LIST, 0, MAX_RECENT_EVENTS - 1);
        } catch (JsonProcessingException e) {
            logger.error("Error serializing RateLimitEvent to JSON: {}", e.getMessage());
        }
    }

    public void recordBucketCreation(String key) {
        String keyMetricsHash = KEY_METRICS_PREFIX + key;
        redisTemplate.opsForHash().increment(keyMetricsHash, "bucketCreations", 1);
        redisTemplate.opsForHash().put(keyMetricsHash, "lastAccessTime", String.valueOf(System.currentTimeMillis()));
        redisTemplate.opsForValue().set(ACTIVE_KEYS_PREFIX + key, "true", Duration.ofSeconds(activeKeyTtlSeconds));
    }

    public void recordBucketCleanup(int cleanedCount) {
        logger.debug("Bucket cleanup completed: removed={}", cleanedCount);
    }

    public void recordProcessingTime(String key, RateLimitAlgorithm algorithm, long processingTimeMs) {
        String keyMetricsHash = KEY_METRICS_PREFIX + key;
        redisTemplate.opsForHash().increment(keyMetricsHash, "totalProcessingTime", processingTimeMs);

        KeyMetricsData data = keyMetrics.computeIfAbsent(key, k -> new KeyMetricsData());
        data.totalProcessingTime.addAndGet(processingTimeMs);
        data.updateAccessTime();

        redisTemplate.opsForHash().increment(GLOBAL_METRICS_KEY, "totalProcessingTimeMs", processingTimeMs);
        totalProcessingTimeMs.addAndGet(processingTimeMs);
        
        if (algorithm != null) {
            String algoKey = ALGORITHM_METRICS_PREFIX + algorithm.name();
            redisTemplate.opsForHash().increment(algoKey, "totalProcessingTime", processingTimeMs);
            
            AlgorithmMetricsData algoData = algoMetrics.get(algorithm);
            if (algoData != null) algoData.totalProcessingTime.addAndGet(processingTimeMs);
        }
    }

    public MetricsResponse getMetrics() {
        long now = System.currentTimeMillis();
        if (cachedMetrics != null && (now - lastCacheTime) < CACHE_DURATION_MS) {
            return cachedMetrics;
        }

        Map<String, MetricsResponse.KeyMetrics> metrics = new HashMap<>();
        Map<String, MetricsResponse.AlgorithmMetrics> algoResults = new HashMap<>();

        try {
            loadGlobalMetricsFromRedis();

            for (RateLimitAlgorithm algo : RateLimitAlgorithm.values()) {
                String algoKey = ALGORITHM_METRICS_PREFIX + algo.name();
                Map<Object, Object> algoData = redisTemplate.opsForHash().entries(algoKey);
                
                if (algoData != null && !algoData.isEmpty()) {
                    long allowed = getLongValue(algoData.get("allowedRequests"));
                    long denied = getLongValue(algoData.get("deniedRequests"));
                    long time = getLongValue(algoData.get("totalProcessingTime"));
                    algoResults.put(algo.name(), new MetricsResponse.AlgorithmMetrics(allowed, denied, time));
                }
            }

            Set<String> activeKeyNames = redisTemplate.keys(ACTIVE_KEYS_PREFIX + "*");
            if (activeKeyNames != null) {
                for (String fullKey : activeKeyNames) {
                    String key = fullKey.substring(ACTIVE_KEYS_PREFIX.length());
                    String keyMetricsHash = KEY_METRICS_PREFIX + key;
                    Map<Object, Object> keyData = redisTemplate.opsForHash().entries(keyMetricsHash);
                    
                    if (keyData != null && !keyData.isEmpty()) {
                        metrics.put(key, new MetricsResponse.KeyMetrics(
                            getLongValue(keyData.get("allowedRequests")),
                            getLongValue(keyData.get("deniedRequests")),
                            getLongValue(keyData.get("lastAccessTime")),
                            getLongValue(keyData.get("totalProcessingTime"))
                        ));
                    }
                }
            }

            java.util.List<Object> eventJsons = redisTemplate.opsForList().range(RECENT_EVENTS_LIST, 0, MAX_RECENT_EVENTS - 1);
            java.util.List<MetricsResponse.RateLimitEvent> events = new java.util.ArrayList<>();
            if (eventJsons != null) {
                for (Object json : eventJsons) {
                    try {
                        events.add(objectMapper.readValue(json.toString(), MetricsResponse.RateLimitEvent.class));
                    } catch (Exception e) {
                        logger.warn("Failed to parse event JSON: {}", e.getMessage());
                    }
                }
            }

            cachedMetrics = new MetricsResponse(
                metrics, algoResults, events, redisConnected,
                totalAllowedRequests.get(), totalDeniedRequests.get(), totalProcessingTimeMs.get()
            );
            lastCacheTime = now;
            return cachedMetrics;
        } catch (Exception e) {
            logger.error("Error aggregating metrics from Redis: {}", e.getMessage());
            return getLocalMetricsFallback();
        }
    }

    private MetricsResponse getLocalMetricsFallback() {
        Map<String, MetricsResponse.KeyMetrics> metrics = new HashMap<>();
        keyMetrics.forEach((key, data) -> {
            metrics.put(key, new MetricsResponse.KeyMetrics(
                data.allowedRequests.get(), data.deniedRequests.get(), data.lastAccessTime, data.totalProcessingTime.get()));
        });

        Map<String, MetricsResponse.AlgorithmMetrics> algoResults = new HashMap<>();
        algoMetrics.forEach((algo, data) -> {
            algoResults.put(algo.name(), new MetricsResponse.AlgorithmMetrics(
                data.allowedRequests.get(), data.deniedRequests.get(), data.totalProcessingTime.get()));
        });

        return new MetricsResponse(
            metrics, algoResults, new java.util.ArrayList<>(recentEvents),
            redisConnected, totalAllowedRequests.get(), totalDeniedRequests.get(), totalProcessingTimeMs.get()
        );
    }

    public void removeKeyMetrics(String key) {
        keyMetrics.remove(key);
        redisTemplate.delete(KEY_METRICS_PREFIX + key);
        redisTemplate.delete(ACTIVE_KEYS_PREFIX + key);
    }

    /**
     * Clear all tracked metrics.
     */
    public void clearMetrics() {
        keyMetrics.clear();
        totalAllowedRequests.set(0);
        totalDeniedRequests.set(0);
        totalProcessingTimeMs.set(0);
        
        algoMetrics.values().forEach(data -> {
            data.allowedRequests.set(0);
            data.deniedRequests.set(0);
            data.totalProcessingTime.set(0);
        });
        
        recentEvents.clear();
        saveGlobalMetricsToRedis();
        cachedMetrics = null;
    }

    /**
     * Clear all metrics for keys starting with a specific prefix.
     */
    public void clearMetricsByPrefix(String prefix) {
        try {
            // Remove from local cache
            keyMetrics.keySet().removeIf(k -> k.startsWith(prefix));
            
            // Remove from Redis (both metrics and active status)
            Set<String> metricKeys = redisTemplate.keys(KEY_METRICS_PREFIX + prefix + "*");
            if (metricKeys != null && !metricKeys.isEmpty()) {
                redisTemplate.delete(metricKeys);
            }
            
            Set<String> activeKeys = redisTemplate.keys(ACTIVE_KEYS_PREFIX + prefix + "*");
            if (activeKeys != null && !activeKeys.isEmpty()) {
                redisTemplate.delete(activeKeys);
            }
            
            logger.info("Cleared Redis metrics for prefix: {}", prefix);
        } catch (Exception e) {
            logger.error("Failed to clear metrics by prefix {}: {}", prefix, e.getMessage());
        }
    }

}

    