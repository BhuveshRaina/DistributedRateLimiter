package dev.bnacar.distributedratelimiter.ratelimit;

import dev.bnacar.distributedratelimiter.monitoring.MetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class RateLimiterService {

    private static final Logger logger = LoggerFactory.getLogger(RateLimiterService.class);

    protected final ConfigurationResolver configurationResolver;
    protected final long cleanupIntervalMs;
    protected final ConcurrentHashMap<String, BucketHolder> buckets;
    protected final ScheduledExecutorService cleanupExecutor;
    protected final MetricsService metricsService;

    @Autowired
    public RateLimiterService(ConfigurationResolver configurationResolver, 
                             RateLimiterConfiguration config, 
                             MetricsService metricsService) {
        this.configurationResolver = configurationResolver;
        this.cleanupIntervalMs = config.getCleanupIntervalMs();
        this.buckets = new ConcurrentHashMap<>();
        this.metricsService = metricsService;
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "RateLimiter-Cleanup");
            t.setDaemon(true);
            return t;
        });
        
        // Start cleanup task
        startCleanupTask();
    }

    // Constructors for backward compatibility and testing
    public RateLimiterService() {
        this(DefaultConfiguration.RESOLVER, DefaultConfiguration.INSTANCE, null);
    }

    public RateLimiterService(ConfigurationResolver configurationResolver, RateLimiterConfiguration config) {
        this.configurationResolver = configurationResolver;
        this.cleanupIntervalMs = config.getCleanupIntervalMs();
        this.buckets = new ConcurrentHashMap<>();
        this.metricsService = null; // No metrics for testing constructors
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "RateLimiter-Cleanup");
            t.setDaemon(true);
            return t;
        });
        
        // Start cleanup task
        startCleanupTask();
    }

    public RateLimiterService(int capacity, int refillRate) {
        this(capacity, refillRate, 60000); // Default 60s cleanup interval
    }

    public RateLimiterService(int capacity, int refillRate, long cleanupIntervalMs) {
        RateLimiterConfiguration config = createDefaultConfiguration();
        config.setCapacity(capacity);
        config.setRefillRate(refillRate);
        config.setCleanupIntervalMs(cleanupIntervalMs);
        
        this.configurationResolver = new ConfigurationResolver(config);
        this.cleanupIntervalMs = cleanupIntervalMs;
        this.buckets = new ConcurrentHashMap<>();
        this.metricsService = null; // No metrics for testing constructors
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "RateLimiter-Cleanup");
            t.setDaemon(true);
            return t;
        });
        
        // Start cleanup task
        startCleanupTask();
    }

    private static RateLimiterConfiguration createDefaultConfiguration() {
        return new RateLimiterConfiguration();
    }

    // Static holder for default configuration to avoid duplicate creation
    private static final class DefaultConfiguration {
        static final RateLimiterConfiguration INSTANCE = createDefaultConfiguration();
        static final ConfigurationResolver RESOLVER = new ConfigurationResolver(INSTANCE);
    }

    public boolean isAllowed(String key, int tokens) {
        return isAllowed(key, tokens, null);
    }

    public RateLimiter.ConsumptionResult isAllowedWithResult(String key, int tokens, RateLimitAlgorithm algorithmOverride) {
        if (tokens <= 0) {
            logger.warn("Invalid token request: key={}, tokens={}", key, tokens);
            return new RateLimiter.ConsumptionResult(false, 0);
        }

        long startTime = System.currentTimeMillis();
        
        // Resolve canonical key (the key string as-is)
        String sharedKey = configurationResolver.resolveBaseKey(key);
        
        BucketHolder holder = buckets.computeIfAbsent(sharedKey, k -> {
            RateLimitConfig config = configurationResolver.resolveConfig(k);
            
            // Apply algorithm override if provided
            if (algorithmOverride != null) {
                config = new RateLimitConfig(config.getCapacity(), config.getRefillRate(), 
                                           config.getCleanupIntervalMs(), algorithmOverride);
            }
            
            RateLimiter rateLimiter = createRateLimiter(config);
            logger.debug("Created new bucket for key={}, capacity={}, refillRate={}, algorithm={}", 
                    k, config.getCapacity(), config.getRefillRate(), config.getAlgorithm());
            
            // Record bucket creation metric
            if (metricsService != null) {
                metricsService.recordBucketCreation(k);
            }
            
            return new BucketHolder(rateLimiter, config);
        });
        
        holder.updateAccessTime();
        
        // Check if configuration has changed (e.g. adaptive limit applied or schedule active)
        RateLimitConfig currentConfig = configurationResolver.resolveConfig(sharedKey);
        if (currentConfig.getCapacity() != holder.config.getCapacity() || 
            currentConfig.getRefillRate() != holder.config.getRefillRate() ||
            currentConfig.getAlgorithm() != holder.config.getAlgorithm()) {
            
            logger.info("Configuration changed for key: {}, updating bucket. Old: {}/{}, New: {}/{}", 
                       sharedKey, holder.config.getCapacity(), holder.config.getRefillRate(),
                       currentConfig.getCapacity(), currentConfig.getRefillRate());
            
            // Create new rate limiter with updated config
            RateLimiter newRateLimiter = createRateLimiter(currentConfig);
            
            // Carry over some tokens to avoid sudden starvation/burst
            int currentTokens = holder.rateLimiter.getCurrentTokens();
            newRateLimiter.setCurrentTokens(Math.min(currentTokens, currentConfig.getCapacity()));
            
            // Update the holder with new limiter and config
            holder.rateLimiter = newRateLimiter;
            holder.config = currentConfig;
        }

        RateLimiter.ConsumptionResult result = holder.rateLimiter.tryConsumeWithResult(tokens);
        long processingTime = System.currentTimeMillis() - startTime;
        RateLimitAlgorithm algorithm = holder.config.getAlgorithm();
        
        // Structured logging using sharedKey
        if (result.allowed) {
            logger.debug("Rate limit ALLOWED: key={}, tokens_requested={}, remaining_tokens={}, algorithm={}, processing_time_ms={}", 
                    sharedKey, tokens, result.remainingTokens, algorithm, processingTime);
        } else {
            logger.warn("Rate limit VIOLATED: key={}, tokens_requested={}, available_tokens={}, capacity={}, refill_rate={}, algorithm={}, processing_time_ms={}", 
                    sharedKey, tokens, result.remainingTokens, holder.config.getCapacity(), 
                    holder.config.getRefillRate(), algorithm, processingTime);
        }
        
        // Record metrics using sharedKey
        if (metricsService != null) {
            if (result.allowed) {
                metricsService.recordAllowedRequest(sharedKey, algorithm, tokens);
            } else {
                metricsService.recordDeniedRequest(sharedKey, algorithm, tokens);
            }
            metricsService.recordProcessingTime(sharedKey, algorithm, processingTime);
        }
        
        return result;
    }

    public boolean isAllowed(String key, int tokens, RateLimitAlgorithm algorithmOverride) {
        return isAllowedWithResult(key, tokens, algorithmOverride).allowed;
    }
    
    /**
     * Factory method to create the appropriate rate limiter based on configuration.
     */
    private RateLimiter createRateLimiter(RateLimitConfig config) {
        switch (config.getAlgorithm()) {
            case TOKEN_BUCKET:
                return new TokenBucket(config.getCapacity(), config.getRefillRate());
            case SLIDING_WINDOW:
                return new SlidingWindow(config.getCapacity(), config.getRefillRate());
            case FIXED_WINDOW:
                return new FixedWindow(config.getCapacity(), config.getRefillRate());
            case LEAKY_BUCKET:
                // For leaky bucket, capacity is queue capacity and refillRate is leak rate
                return new LeakyBucket(config.getCapacity(), config.getRefillRate());
            case COMPOSITE:
                return createCompositeRateLimiter(config);
            default:
                throw new IllegalArgumentException("Unknown algorithm: " + config.getAlgorithm());
        }
    }
    
    /**
     * Create a composite rate limiter from configuration.
     * For now, creates a simple composite with default components.
     */
    private RateLimiter createCompositeRateLimiter(RateLimitConfig config) {
        // For basic composite support, create a simple multi-algorithm composite
        // This will be enhanced when we add full composite configuration support
        java.util.List<LimitComponent> components = new java.util.ArrayList<>();
        
        // Add a token bucket component
        components.add(new LimitComponent(
            "token_bucket", 
            new TokenBucket(config.getCapacity(), config.getRefillRate()),
            1.0, 1, "PRIMARY"
        ));
        
        // Add a sliding window component for stricter enforcement
        components.add(new LimitComponent(
            "sliding_window",
            new SlidingWindow(config.getCapacity() / 2, config.getRefillRate()),
            1.0, 2, "SECONDARY"
        ));
        
        return new CompositeRateLimiter(components, CombinationLogic.ALL_MUST_PASS);
    }

    /**
     * Get current token count for logging purposes.
     */
    private int getCurrentTokens(BucketHolder holder) {
        if (holder.rateLimiter instanceof TokenBucket) {
            return ((TokenBucket) holder.rateLimiter).getCurrentTokens();
        } else if (holder.rateLimiter instanceof FixedWindow) {
            return ((FixedWindow) holder.rateLimiter).getCurrentTokens();
        } else if (holder.rateLimiter instanceof LeakyBucket) {
            return ((LeakyBucket) holder.rateLimiter).getCurrentTokens();
        }
        // For other algorithms, return -1 to indicate unavailable
        return -1;
    }

    private void startCleanupTask() {
        cleanupExecutor.scheduleWithFixedDelay(
            this::cleanupExpiredBuckets,
            cleanupIntervalMs,
            cleanupIntervalMs,
            TimeUnit.MILLISECONDS
        );
    }

    private void cleanupExpiredBuckets() {
        long currentTime = System.currentTimeMillis();
        int initialCount = buckets.size();
        
        buckets.entrySet().removeIf(entry -> {
            BucketHolder holder = entry.getValue();
            // Use the cleanup interval from the bucket's configuration
            long bucketCleanupInterval = holder.config.getCleanupIntervalMs();
            boolean shouldRemove = (currentTime - holder.lastAccessTime) > bucketCleanupInterval;
            
            if (shouldRemove) {
                logger.debug("Cleaning up expired bucket: key={}, last_access={}ms_ago", 
                        entry.getKey(), currentTime - holder.lastAccessTime);
            }
            
            return shouldRemove;
        });
        
        int finalCount = buckets.size();
        int cleanedCount = initialCount - finalCount;
        
        if (cleanedCount > 0) {
            logger.info("Bucket cleanup completed: removed={}, remaining={}", 
                    cleanedCount, finalCount);
            
            // Record cleanup metrics
            if (metricsService != null) {
                metricsService.recordBucketCleanup(cleanedCount);
            }
        }
    }

    // Public method for monitoring bucket count
    public int getBucketCount() {
        return buckets.size();
    }

    /**
     * Clear all buckets and configuration cache. Useful for configuration reloading.
     */
    public void clearBuckets() {
        buckets.clear();
        configurationResolver.clearCache();
    }

    /**
     * Get configuration for a specific key.
     * @param key the key to get configuration for
     * @return the configuration or null if not found
     */
    public RateLimitConfig getKeyConfiguration(String key) {
        BucketHolder holder = buckets.get(key);
        if (holder != null) {
            return holder.config;
        }
        // If no active bucket, resolve configuration without creating bucket
        return configurationResolver.resolveConfig(key);
    }

    /**
     * Remove a specific key's bucket.
     * @param key the key to remove
     * @return true if the key was removed, false if not found
     */
    public boolean removeKey(String key) {
        return buckets.remove(key) != null;
    }

    /**
     * Get all active keys with their statistics.
     * @return a list of key names with their stats
     */
    public java.util.List<String> getActiveKeys() {
        return new java.util.ArrayList<>(buckets.keySet());
    }

    /**
     * Get statistics for all active keys.
     * @return a map of key to bucket holder for admin purposes
     */
    public java.util.Map<String, BucketHolder> getBucketHolders() {
        return new java.util.HashMap<>(buckets);
    }

    /**
     * Get statistics for a specific active key.
     * @param key the key to get stats for
     * @return bucket holder or null if not found
     */
    public BucketHolder getBucketHolder(String key) {
        return buckets.get(key);
    }

    /**
     * Make BucketHolder accessible for admin operations.
     */
    public static class BucketHolder {
        RateLimiter rateLimiter;
        RateLimitConfig config;
        volatile long lastAccessTime;

        public BucketHolder(RateLimiter rateLimiter, RateLimitConfig config) {
            this.rateLimiter = rateLimiter;
            this.config = config;
            this.lastAccessTime = System.currentTimeMillis();
        }

        void updateAccessTime() {
            this.lastAccessTime = System.currentTimeMillis();
        }
        
        boolean tryConsume(int tokens) {
            return rateLimiter.tryConsume(tokens);
        }

        public RateLimitConfig getConfig() {
            return config;
        }

        public long getLastAccessTime() {
            return lastAccessTime;
        }
    }

    // Shutdown method for cleanup
    @PreDestroy
    public void shutdown() {
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}