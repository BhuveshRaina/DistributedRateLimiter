package dev.bnacar.distributedratelimiter.ratelimit;

import dev.bnacar.distributedratelimiter.monitoring.MetricsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

/**
 * Distributed rate limiter service that uses Redis as the primary backend.
 * If Redis is unavailable, the service will return denied results to maintain consistency.
 */
@Service
@Primary
@ConditionalOnProperty(name = "ratelimiter.redis.enabled", havingValue = "true", matchIfMissing = true)
public class DistributedRateLimiterService extends RateLimiterService {
    
    private final RateLimiterBackend primaryBackend;
    
    @Autowired
    public DistributedRateLimiterService(
            ConfigurationResolver configurationResolver,
            RateLimiterConfiguration configuration,
            MetricsService metricsService,
            RedisTemplate<String, Object> redisTemplate) {
        super(configurationResolver, configuration, metricsService);
        this.primaryBackend = new RedisRateLimiterBackend(redisTemplate);
    }
    
    // Convenient constructor for testing and manual instantiation
    public DistributedRateLimiterService(
            ConfigurationResolver configurationResolver,
            RedisTemplate<String, Object> redisTemplate) {
        this(configurationResolver, new RateLimiterConfiguration(), null, redisTemplate);
    }
    
    // Constructor for testing with mocked backend
    public DistributedRateLimiterService(
            ConfigurationResolver configurationResolver,
            RateLimiterBackend primaryBackend) {
        super(configurationResolver, new RateLimiterConfiguration(), null);
        this.primaryBackend = primaryBackend;
    }
    
    @Override
    public boolean isAllowed(String key, int tokens) {
        return isAllowed(key, tokens, null);
    }

    public RateLimiter.ConsumptionResult isAllowedWithResult(String key, int tokens, RateLimitAlgorithm algorithmOverride) {
        if (tokens <= 0) {
            return new RateLimiter.ConsumptionResult(false, 0);
        }
        
        long startTime = System.currentTimeMillis();
        
        // Resolve the shared bucket key (strips suffixes like :1, :2)
        String sharedKey = configurationResolver.resolveBaseKey(key);
        
        RateLimitConfig config = configurationResolver.resolveConfig(sharedKey);
        
        // Apply algorithm override if provided
        if (algorithmOverride != null) {
            config = new RateLimitConfig(config.getCapacity(), config.getRefillRate(), 
                                       config.getCleanupIntervalMs(), algorithmOverride);
        }
        
        RateLimiter.ConsumptionResult result;
        try {
            if (!primaryBackend.isAvailable()) {
                throw new RuntimeException("Redis backend is unavailable");
            }

            RateLimiter rateLimiter = primaryBackend.getRateLimiter(sharedKey, config);
            
            // Check if existing limiter's configuration is out of sync with current resolved config
            if (rateLimiter.getCapacity() != config.getCapacity() || 
                rateLimiter.getRefillRate() != config.getRefillRate()) {
                
                org.slf4j.LoggerFactory.getLogger(DistributedRateLimiterService.class)
                    .info("Config change detected for key {}. Refreshing limiter: {}/{} -> {}/{}", 
                          sharedKey, rateLimiter.getCapacity(), rateLimiter.getRefillRate(), 
                          config.getCapacity(), config.getRefillRate());
                
                // Clear the cached limiter so a new one is created with the updated config
                primaryBackend.remove(sharedKey);
                rateLimiter = primaryBackend.getRateLimiter(sharedKey, config);
            }
            
            result = rateLimiter.tryConsumeWithResult(tokens);
        } catch (Exception ex) {
            org.slf4j.LoggerFactory.getLogger(DistributedRateLimiterService.class)
                .error("Backend error for key {}: {}", sharedKey, ex.getMessage());
            result = new RateLimiter.ConsumptionResult(false, -1);
        }

        long processingTime = System.currentTimeMillis() - startTime;
        
        // Record metrics using the sharedKey for accurate aggregation
        try {
            if (metricsService != null) {
                if (result.allowed) {
                    metricsService.recordAllowedRequest(sharedKey, config.getAlgorithm(), tokens);
                } else {
                    metricsService.recordDeniedRequest(sharedKey, config.getAlgorithm(), tokens);
                }
                metricsService.recordProcessingTime(sharedKey, config.getAlgorithm(), processingTime);
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(DistributedRateLimiterService.class)
                .warn("Failed to record metrics for key {}: {}", sharedKey, e.getMessage());
        }
        
        return result;
    }
    
    @Override
    public boolean isAllowed(String key, int tokens, RateLimitAlgorithm algorithmOverride) {
        return isAllowedWithResult(key, tokens, algorithmOverride).allowed;
    }
    
    /**
     * Check if currently using Redis backend.
     */
    public boolean isUsingRedis() {
        return primaryBackend.isAvailable();
    }
    
    /**
     * Clear all rate limiters from the backend.
     */
    @Override
    public void clearBuckets() {
        primaryBackend.clear();
        configurationResolver.clearCache();
    }
    
    @PreDestroy
    public void shutdown() {
        // No local resources to shut down in Redis-only mode
    }
}
