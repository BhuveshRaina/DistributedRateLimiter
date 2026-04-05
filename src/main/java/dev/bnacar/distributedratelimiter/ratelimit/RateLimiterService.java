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
    protected final MetricsService metricsService;

    @Autowired
    public RateLimiterService(ConfigurationResolver configurationResolver, 
                             RateLimiterConfiguration config, 
                             MetricsService metricsService) {
        this.configurationResolver = configurationResolver;
        this.metricsService = metricsService;
    }

    // Constructors for backward compatibility and testing
    public RateLimiterService() {
        this(DefaultConfiguration.RESOLVER, DefaultConfiguration.INSTANCE, null);
    }

    public RateLimiterService(ConfigurationResolver configurationResolver, RateLimiterConfiguration config) {
        this.configurationResolver = configurationResolver;
        this.metricsService = null; // No metrics for testing constructors
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
        this.metricsService = null; // No metrics for testing constructors
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
        throw new UnsupportedOperationException("RateLimiterService base class does not support direct consumption. Use DistributedRateLimiterService.");
    }

    public boolean isAllowed(String key, int tokens, RateLimitAlgorithm algorithmOverride) {
        return isAllowedWithResult(key, tokens, algorithmOverride).allowed;
    }

    /**
     * Clear configuration cache.
     */
    public void clearBuckets() {
        configurationResolver.clearCache();
    }

    /**
     * Get configuration for a specific key.
     * @param key the key to get configuration for
     * @return the configuration or null if not found
     */
    public RateLimitConfig getKeyConfiguration(String key) {
        return configurationResolver.resolveConfig(key);
    }

    /**
     * Remove a specific key's configuration.
     * @param key the key to remove
     * @return true if successful
     */
    public boolean removeKey(String key) {
        configurationResolver.removeKeyConfig(key);
        return true;
    }

    /**
     * Get all active keys (not supported in base class).
     */
    public java.util.List<String> getActiveKeys() {
        return new java.util.ArrayList<>();
    }

    // Shutdown method
    @PreDestroy
    public void shutdown() {
        // No local resources to shut down
    }
}