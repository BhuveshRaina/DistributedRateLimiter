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
    
    @Override
    public boolean isAllowed(String key, int tokens, RateLimitAlgorithm algorithmOverride) {
        if (tokens <= 0) {
            return false;
        }
        
        long startTime = System.currentTimeMillis();
        RateLimitConfig config = configurationResolver.resolveConfig(key);
        
        // Apply algorithm override if provided
        if (algorithmOverride != null) {
            config = new RateLimitConfig(config.getCapacity(), config.getRefillRate(), 
                                       config.getCleanupIntervalMs(), algorithmOverride);
        }
        
        boolean allowed = false;
        RateLimiterBackend backend = getAvailableBackend();
        try {
            RateLimiter rateLimiter = backend.getRateLimiter(key, config);
            allowed = rateLimiter.tryConsume(tokens);
        } catch (RuntimeException ex) {
            if (backend != fallbackBackend) {
                usingFallback = true;
                try {
                    RateLimiter fallbackLimiter = fallbackBackend.getRateLimiter(key, config);
                    allowed = fallbackLimiter.tryConsume(tokens);
                } catch (RuntimeException fallbackEx) {
                    allowed = false;
                }
            } else {
                allowed = false;
            }
        }

        long processingTime = System.currentTimeMillis() - startTime;
        
        // Record metrics safely
        try {
            if (metricsService != null) {
                if (allowed) {
                    metricsService.recordAllowedRequest(key, config.getAlgorithm(), tokens);
                } else {
                    metricsService.recordDeniedRequest(key, config.getAlgorithm(), tokens);
                }
                metricsService.recordProcessingTime(key, config.getAlgorithm(), processingTime);
            }
        } catch (Exception e) {
            // Log metrics error but don't fail the request
            org.slf4j.LoggerFactory.getLogger(DistributedRateLimiterService.class)
                .warn("Failed to record metrics for key {}: {}", key, e.getMessage());
        }
        
        return allowed;
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
