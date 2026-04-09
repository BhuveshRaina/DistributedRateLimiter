package dev.bnacar.distributedratelimiter.ratelimit;

import dev.bnacar.distributedratelimiter.monitoring.MetricsService;
import dev.bnacar.distributedratelimiter.adaptive.AdaptiveRateLimitEngine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

/**
 * Distributed rate limiter service that consults the Adaptive Engine for global adjustments.
 */
@Service
@Primary
@ConditionalOnProperty(name = "ratelimiter.redis.enabled", havingValue = "true", matchIfMissing = true)
public class DistributedRateLimiterService extends RateLimiterService {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(DistributedRateLimiterService.class);    
    private final RateLimiterBackend primaryBackend;
    private final AdaptiveRateLimitEngine adaptiveEngine;
    
    @Autowired
    public DistributedRateLimiterService(
            ConfigurationResolver configurationResolver,
            RateLimiterConfiguration configuration,
            MetricsService metricsService,
            RedisTemplate<String, Object> redisTemplate,
            AdaptiveRateLimitEngine adaptiveEngine) {
        super(configurationResolver, metricsService);
        this.primaryBackend = new RedisRateLimiterBackend(redisTemplate);
        this.adaptiveEngine = adaptiveEngine;
    }
    
    @Override
    public RateLimiter.ConsumptionResult isAllowedWithResult(String key, int tokens, RateLimitAlgorithm algorithmOverride) {
        if (tokens <= 0) return new RateLimiter.ConsumptionResult(false, 0);
        long startTime = System.currentTimeMillis();
        String sharedKey = configurationResolver.resolveBaseKey(key);
        
        RateLimitConfig config = configurationResolver.resolveConfig(sharedKey);
        
        AdaptiveRateLimitEngine.AdaptedLimits adapted = adaptiveEngine.getAdaptedLimits(sharedKey);
        if (adapted != null) {
            config = new RateLimitConfig(adapted.adaptedCapacity, adapted.adaptedRefillRate, 
                                       config.getCleanupIntervalMs(), config.getAlgorithm());
        }
        
        if (algorithmOverride != null) {
            config = new RateLimitConfig(config.getCapacity(), config.getRefillRate(), 
                                       config.getCleanupIntervalMs(), algorithmOverride);
        }
        
        RateLimiter.ConsumptionResult result;
        try {
            RateLimiter rateLimiter = primaryBackend.getRateLimiter(sharedKey, config);
            
            if (rateLimiter.getCapacity() != config.getCapacity() || rateLimiter.getRefillRate() != config.getRefillRate()) {
                rateLimiter = primaryBackend.getRateLimiter(sharedKey, config);
            }
            
            result = rateLimiter.tryConsumeWithResult(tokens);
        } catch (Exception ex) {
            result = new RateLimiter.ConsumptionResult(false, -1);
        }

        long processingTime = System.currentTimeMillis() - startTime;
        if (metricsService != null) {
            if (result.allowed) metricsService.recordAllowedRequest(sharedKey, config.getAlgorithm(), tokens);
            else metricsService.recordDeniedRequest(sharedKey, config.getAlgorithm(), tokens);
            metricsService.recordProcessingTime(sharedKey, config.getAlgorithm(), processingTime);
        }
        
        return result;
    }
    
    @Override
    public boolean isAllowed(String key, int tokens, RateLimitAlgorithm algorithmOverride) {
        return isAllowedWithResult(key, tokens, algorithmOverride).allowed;
    }
    
    @Override
    public void clearBuckets() {
        primaryBackend.clear();
        configurationResolver.clearCache();
    }
}
