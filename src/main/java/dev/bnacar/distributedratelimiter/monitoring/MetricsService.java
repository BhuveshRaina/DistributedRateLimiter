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

    private final AtomicLong totalBucketCreations = new AtomicLong(0);

    private final AtomicLong totalBucketCleanups = new AtomicLong(0);



    // Algorithm metrics are updated frequently, so keep in-memory for speed and then convert for Redis storage/retrieval if needed.

    // For now, let's keep them in memory.
    private final Map<RateLimitAlgorithm, AlgorithmMetricsData> algoMetrics = new ConcurrentHashMap<>();

    // Key metrics for individual keys
    private final Map<String, KeyMetricsData> keyMetrics = new ConcurrentHashMap<>();
    
    // Recent events for activity feed (in-memory cache for dashboard)
    private final ConcurrentLinkedDeque<MetricsResponse.RateLimitEvent> recentEvents = new ConcurrentLinkedDeque<>();

    private final RedisTemplate<String, Object> redisTemplate;
    
    // Cache for aggregated metrics to avoid Redis pressure
    private volatile MetricsResponse cachedMetrics;
    private volatile long lastCacheTime = 0;
    private static final long CACHE_DURATION_MS = 5000; // 5 second cache as per requirement

    @Value("${ratelimiter.metrics.active-key-ttl-seconds:300}") // Default to 5 minutes
    private long activeKeyTtlSeconds;

    // Use this for Redis health check
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
        
        // Initialize algorithm metrics maps
        for (RateLimitAlgorithm algo : RateLimitAlgorithm.values()) {
            algoMetrics.put(algo, new AlgorithmMetricsData());
        }
    }

    @PostConstruct
    public void initialize() {
        // Load initial global metrics from Redis
        loadGlobalMetricsFromRedis();
        
        healthCheckExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Redis-Health-Check");
            t.setDaemon(true);
            return t;
        });
        
        // Check Redis health every 30 seconds
        healthCheckExecutor.scheduleWithFixedDelay(this::checkRedisHealth, 0, 30, TimeUnit.SECONDS);
    }

    private void loadGlobalMetricsFromRedis() {
        Map<Object, Object> globalData = redisTemplate.opsForHash().entries(GLOBAL_METRICS_KEY);
        if (globalData != null && !globalData.isEmpty()) {
            totalAllowedRequests.set(getLongValue(globalData.get("totalAllowedRequests")));
            totalDeniedRequests.set(getLongValue(globalData.get("totalDeniedRequests")));
            totalProcessingTimeMs.set(getLongValue(globalData.get("totalProcessingTimeMs")));
            totalBucketCreations.set(getLongValue(globalData.get("totalBucketCreations")));
            totalBucketCleanups.set(getLongValue(globalData.get("totalBucketCleanups")));
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
        } else {
            logger.debug("Redis connection factory not available");
        }
    }

    @PreDestroy
    public void shutdown() {
        if (healthCheckExecutor != null) {
            healthCheckExecutor.shutdown();
            try {
                if (!healthCheckExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    healthCheckExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                healthCheckExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        // Save final global metrics to Redis on shutdown
        saveGlobalMetricsToRedis();
        logger.info("MetricsService shutdown. Final global metrics saved to Redis.");
    }
    
    private void saveGlobalMetricsToRedis() {
        Map<String, String> globalData = new HashMap<>();
        globalData.put("totalAllowedRequests", String.valueOf(totalAllowedRequests.get()));
        globalData.put("totalDeniedRequests", String.valueOf(totalDeniedRequests.get()));
        globalData.put("totalProcessingTimeMs", String.valueOf(totalProcessingTimeMs.get()));
        globalData.put("totalBucketCreations", String.valueOf(totalBucketCreations.get()));
        globalData.put("totalBucketCleanups", String.valueOf(totalBucketCleanups.get()));
        redisTemplate.opsForHash().putAll(GLOBAL_METRICS_KEY, globalData);
    }

    public void recordAllowedRequest(String key, RateLimitAlgorithm algorithm) {
        recordAllowedRequest(key, algorithm, 1);
    }

    public void recordAllowedRequest(String key, RateLimitAlgorithm algorithm, int tokens) {
        String keyMetricsHash = KEY_METRICS_PREFIX + key;
        
        // Update key-specific metrics in Redis Hash
        redisTemplate.opsForHash().increment(keyMetricsHash, "allowedRequests", 1);
        redisTemplate.opsForHash().put(keyMetricsHash, "lastAccessTime", String.valueOf(System.currentTimeMillis()));

        // Update local key metrics for this instance
        KeyMetricsData data = keyMetrics.computeIfAbsent(key, k -> new KeyMetricsData());
        data.allowedRequests.incrementAndGet();
        data.updateAccessTime();

        // Update global totals (in Redis and in-memory copy)
        redisTemplate.opsForHash().increment(GLOBAL_METRICS_KEY, "totalAllowedRequests", 1);
        totalAllowedRequests.incrementAndGet(); 
        
        // Update algorithm-specific metrics in Redis and local cache
        if (algorithm != null) {
            String algoKey = ALGORITHM_METRICS_PREFIX + algorithm.name();
            redisTemplate.opsForHash().increment(algoKey, "allowedRequests", 1);
            
            AlgorithmMetricsData algoData = algoMetrics.get(algorithm);
            if (algoData != null) algoData.allowedRequests.incrementAndGet();
        }
        
        // Mark key as active and set TTL for active status in Redis
        redisTemplate.opsForValue().set(ACTIVE_KEYS_PREFIX + key, "true", Duration.ofSeconds(activeKeyTtlSeconds));

        recordEvent(key, algorithm, true, tokens);
        
        logger.debug("Recorded allowed request for key={}, algorithm={}, total_allowed={}", 
                key, algorithm, totalAllowedRequests.get());
    }

    public void recordDeniedRequest(String key, RateLimitAlgorithm algorithm) {
        recordDeniedRequest(key, algorithm, 1);
    }

    public void recordDeniedRequest(String key, RateLimitAlgorithm algorithm, int tokens) {
        String keyMetricsHash = KEY_METRICS_PREFIX + key;

        // Update key-specific metrics in Redis Hash
        redisTemplate.opsForHash().increment(keyMetricsHash, "deniedRequests", 1);
        redisTemplate.opsForHash().put(keyMetricsHash, "lastAccessTime", String.valueOf(System.currentTimeMillis()));

        // Update local key metrics
        KeyMetricsData data = keyMetrics.computeIfAbsent(key, k -> new KeyMetricsData());
        data.deniedRequests.incrementAndGet();
        data.updateAccessTime();

        // Update global totals (in Redis and in-memory copy)
        redisTemplate.opsForHash().increment(GLOBAL_METRICS_KEY, "totalDeniedRequests", 1);
        totalDeniedRequests.incrementAndGet(); 

        // Update algorithm-specific metrics in Redis and local cache
        if (algorithm != null) {
            String algoKey = ALGORITHM_METRICS_PREFIX + algorithm.name();
            redisTemplate.opsForHash().increment(algoKey, "deniedRequests", 1);
            
            AlgorithmMetricsData algoData = algoMetrics.get(algorithm);
            if (algoData != null) algoData.deniedRequests.incrementAndGet();
        }
        
        // Mark key as active and set TTL
        redisTemplate.opsForValue().set(ACTIVE_KEYS_PREFIX + key, "true", Duration.ofSeconds(activeKeyTtlSeconds));

        recordEvent(key, algorithm, false, tokens);
        
        logger.info("Recorded denied request for key={}, algorithm={}, total_denied={}, denied_ratio={}%", 
                key, algorithm, totalDeniedRequests.get(), calculateDeniedRatio());
    }

    private void recordEvent(String key, RateLimitAlgorithm algorithm, boolean allowed, int tokens) {
        String id = java.util.UUID.randomUUID().toString();
        MetricsResponse.RateLimitEvent event = new MetricsResponse.RateLimitEvent(
            id, System.currentTimeMillis(), key, algorithm != null ? algorithm.name() : "UNKNOWN", allowed, tokens
        );
        
        // Store in local deque for fast access
        recentEvents.addFirst(event);
        if (recentEvents.size() > MAX_RECENT_EVENTS) {
            recentEvents.removeLast();
        }
        
        // Store recent events in a Redis List (capped)
        try {
            redisTemplate.opsForList().leftPush(RECENT_EVENTS_LIST, objectMapper.writeValueAsString(event));
            redisTemplate.opsForList().trim(RECENT_EVENTS_LIST, 0, MAX_RECENT_EVENTS - 1); // Cap the list
        } catch (JsonProcessingException e) {
            logger.error("Error serializing RateLimitEvent to JSON: {}", e.getMessage());
        }
    }

    public void recordBucketCreation(String key) {
        String keyMetricsHash = KEY_METRICS_PREFIX + key;
        redisTemplate.opsForHash().increment(keyMetricsHash, "bucketCreations", 1);
        redisTemplate.opsForHash().put(keyMetricsHash, "lastAccessTime", String.valueOf(System.currentTimeMillis()));

        redisTemplate.opsForHash().increment(GLOBAL_METRICS_KEY, "totalBucketCreations", 1);
        totalBucketCreations.incrementAndGet(); 

        redisTemplate.opsForValue().set(ACTIVE_KEYS_PREFIX + key, "true", Duration.ofSeconds(activeKeyTtlSeconds));

        logger.info("New bucket created for key={}, total_buckets_created={}", 
                key, totalBucketCreations.get());
    }

    public void recordBucketCleanup(int cleanedCount) {
        redisTemplate.opsForHash().increment(GLOBAL_METRICS_KEY, "totalBucketCleanups", cleanedCount);
        totalBucketCleanups.addAndGet(cleanedCount); 
        
        logger.info("Bucket cleanup completed: removed={}, total_cleanups={}", 
                cleanedCount, totalBucketCleanups.get());
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
        
        if (processingTimeMs > 10) { // Log slow processing
            logger.warn("Slow rate limit processing detected: key={}, algorithm={}, processing_time_ms={}", 
                    key, algorithm, processingTimeMs);
        }
    }

    private double calculateDeniedRatio() {
        long total = totalAllowedRequests.get() + totalDeniedRequests.get();
        return total > 0 ? (double) totalDeniedRequests.get() / total * 100 : 0.0;
    }

    public void setRedisConnected(boolean connected) {
        this.redisConnected = connected;
    }

    public boolean isRedisConnected() {
        return redisConnected;
    }

    public MetricsResponse getMetrics() {
        long now = System.currentTimeMillis();
        // Return cached metrics if they are still fresh
        if (cachedMetrics != null && (now - lastCacheTime) < CACHE_DURATION_MS) {
            return cachedMetrics;
        }

        Map<String, MetricsResponse.KeyMetrics> metrics = new HashMap<>();
        Map<String, MetricsResponse.AlgorithmMetrics> algoResults = new HashMap<>();

        try {
            // Pull Global metrics from Redis to ensure we have cluster-wide totals
            loadGlobalMetricsFromRedis();

            // Aggregated Algorithm metrics from Redis
            for (RateLimitAlgorithm algo : RateLimitAlgorithm.values()) {
                String algoKey = ALGORITHM_METRICS_PREFIX + algo.name();
                Map<Object, Object> algoData = redisTemplate.opsForHash().entries(algoKey);
                
                if (algoData != null && !algoData.isEmpty()) {
                    long allowed = getLongValue(algoData.get("allowedRequests"));
                    long denied = getLongValue(algoData.get("deniedRequests"));
                    long time = getLongValue(algoData.get("totalProcessingTime"));
                    
                    algoResults.put(algo.name(), new MetricsResponse.AlgorithmMetrics(allowed, denied, time));
                } else {
                    // Fallback to local if Redis has no data yet
                    AlgorithmMetricsData local = algoMetrics.get(algo);
                    algoResults.put(algo.name(), new MetricsResponse.AlgorithmMetrics(
                        local.allowedRequests.get(), local.deniedRequests.get(), local.totalProcessingTime.get()));
                }
            }

            // Pull active keys from Redis using SCAN to find active sessions
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

            // Get recent events from Redis list
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
                metrics,
                algoResults,
                events,
                redisConnected,
                totalAllowedRequests.get(),
                totalDeniedRequests.get(),
                totalProcessingTimeMs.get()
            );
            lastCacheTime = now;
            
            return cachedMetrics;
        } catch (Exception e) {
            logger.error("Error aggregating metrics from Redis: {}", e.getMessage());
            // Fallback to local data on error
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
            metrics,
            algoResults,
            new java.util.ArrayList<>(recentEvents),
            redisConnected,
            totalAllowedRequests.get(),
            totalDeniedRequests.get(),
            totalProcessingTimeMs.get()
        );
    }

        public void clearMetrics() {

            keyMetrics.clear();

            totalAllowedRequests.set(0);

            totalDeniedRequests.set(0);

        }

    

        /**

         * Remove metrics for a specific key.

         */

        public void removeKeyMetrics(String key) {

            keyMetrics.remove(key);

        }

    }

    