package dev.bnacar.distributedratelimiter.ratelimit;

import dev.bnacar.distributedratelimiter.monitoring.MetricsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

/**
 * Distributed rate limiter service that uses Redis as primary backend
 * with automatic fallback to in-memory when Redis is unavailable.
 */
@Service
@Primary
@ConditionalOnProperty(name = "ratelimiter.redis.enabled", havingValue = "true", matchIfMissing = true)
public class DistributedRateLimiterService extends RateLimiterService {
    
    private final RateLimiterBackend primaryBackend;
    private final RateLimiterBackend fallbackBackend;
    private volatile boolean usingFallback = false;
    
    @Autowired
    public DistributedRateLimiterService(
            ConfigurationResolver configurationResolver,
            RateLimiterConfiguration configuration,
            MetricsService metricsService,
            RedisTemplate<String, Object> redisTemplate) {
        super(configurationResolver, configuration, metricsService);
        this.primaryBackend = new RedisRateLimiterBackend(redisTemplate);
        this.fallbackBackend = new InMemoryRateLimiterBackend();
    }
    
    // Convenient constructor for testing and manual instantiation
    public DistributedRateLimiterService(
            ConfigurationResolver configurationResolver,
            RedisTemplate<String, Object> redisTemplate) {
        this(configurationResolver, new RateLimiterConfiguration(), null, redisTemplate);
    }
    
    // Constructor for testing with mocked backends
    public DistributedRateLimiterService(
            ConfigurationResolver configurationResolver,
            RateLimiterBackend primaryBackend,
            RateLimiterBackend fallbackBackend) {
        super(configurationResolver, new RateLimiterConfiguration(), null);
        this.primaryBackend = primaryBackend;
        this.fallbackBackend = fallbackBackend;
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
        RateLimiterBackend backend = getAvailableBackend();
        try {
            RateLimiter rateLimiter = backend.getRateLimiter(sharedKey, config);
            result = rateLimiter.tryConsumeWithResult(tokens);
        } catch (Exception ex) {
            // Fallback to in-memory only if the primary backend (Redis) failed
            if (backend != fallbackBackend) {
                usingFallback = true;
                try {
                    RateLimiter fallbackLimiter = fallbackBackend.getRateLimiter(sharedKey, config);
                    result = fallbackLimiter.tryConsumeWithResult(tokens);
                } catch (Exception fallbackEx) {
                    result = new RateLimiter.ConsumptionResult(false, 0);
                }
            } else {
                result = new RateLimiter.ConsumptionResult(false, 0);
            }
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
     * Get the currently available backend, with fallback logic.
     */
    private RateLimiterBackend getAvailableBackend() {
        // Check if primary backend (Redis) is available
        if (primaryBackend.isAvailable()) {
            if (usingFallback) {
                // We were using fallback but Redis is back - log the recovery
                usingFallback = false;
            }
            return primaryBackend;
        } else {
            if (!usingFallback) {
                // We just switched to fallback - log the failure
                usingFallback = true;
            }
            return fallbackBackend;
        }
    }
    
    /**
     * Check if currently using Redis backend.
     */
    public boolean isUsingRedis() {
        return !usingFallback && primaryBackend.isAvailable();
    }
    
    /**
     * Check if currently using fallback backend.
     */
    public boolean isUsingFallback() {
        return usingFallback || !primaryBackend.isAvailable();
    }
    
    /**
     * Clear all rate limiters from all backends.
     */
    @Override
    public void clearBuckets() {
        primaryBackend.clear();
        fallbackBackend.clear();
        configurationResolver.clearCache();
    }
    
    @PreDestroy
    public void shutdown() {
        if (fallbackBackend instanceof InMemoryRateLimiterBackend) {
            ((InMemoryRateLimiterBackend) fallbackBackend).shutdown();
        }
    }
}
