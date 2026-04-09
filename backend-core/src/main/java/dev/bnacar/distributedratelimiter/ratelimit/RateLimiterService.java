package dev.bnacar.distributedratelimiter.ratelimit;

import dev.bnacar.distributedratelimiter.monitoring.MetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;

@Service
public class RateLimiterService {

    private static final Logger logger = LoggerFactory.getLogger(RateLimiterService.class);

    protected final ConfigurationResolver configurationResolver;
    protected final MetricsService metricsService;

    @Autowired
    public RateLimiterService(ConfigurationResolver configurationResolver, 
                             MetricsService metricsService) {
        this.configurationResolver = configurationResolver;
        this.metricsService = metricsService;
    }

    public boolean isAllowed(String key, int tokens) {
        return isAllowed(key, tokens, null);
    }

    public RateLimiter.ConsumptionResult isAllowedWithResult(String key, int tokens, RateLimitAlgorithm algorithmOverride) {
        throw new UnsupportedOperationException("Base service doesn't support consumption. Use DistributedRateLimiterService.");
    }

    public boolean isAllowed(String key, int tokens, RateLimitAlgorithm algorithmOverride) {
        return isAllowedWithResult(key, tokens, algorithmOverride).allowed;
    }

    public void clearBuckets() {
        configurationResolver.clearCache();
    }

    public RateLimitConfig getKeyConfiguration(String key) {
        return configurationResolver.resolveConfig(key);
    }

    public boolean removeKey(String key) {
        configurationResolver.removeKeyConfig(key);
        return true;
    }

    public List<String> getActiveKeys() {
        return new ArrayList<>();
    }

    @PreDestroy
    public void shutdown() {
    }
}
