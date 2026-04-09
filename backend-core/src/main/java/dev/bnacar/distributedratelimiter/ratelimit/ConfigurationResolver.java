package dev.bnacar.distributedratelimiter.ratelimit;

import dev.bnacar.distributedratelimiter.adaptive.AdaptiveRateLimitEngine;
import dev.bnacar.distributedratelimiter.schedule.ScheduleManagerService;
import dev.bnacar.distributedratelimiter.config.ConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for resolving rate limit configuration.
 * Fetches REAL-TIME configuration from Redis to ensure cluster-wide synchronization.
 */
@Service
public class ConfigurationResolver {
    
    private static final Logger logger = LoggerFactory.getLogger(ConfigurationResolver.class);
    
    private final RateLimiterConfiguration localConfiguration;
    private final ConfigRepository configRepository;
    private ScheduleManagerService scheduleManager;
    private AdaptiveRateLimitEngine adaptiveEngine;
    
    // Minimal cache to avoid hitting Redis too hard
    private final ConcurrentHashMap<String, CachedConfig> configCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 500; // 0.5 second cache

    private static class CachedConfig {
        final RateLimitConfig config;
        final long timestamp;
        CachedConfig(RateLimitConfig c) { this.config = c; this.timestamp = System.currentTimeMillis(); }
    }

    @Autowired
    public ConfigurationResolver(RateLimiterConfiguration configuration, @Autowired(required = false) ConfigRepository configRepository) {
        this.localConfiguration = configuration;
        this.configRepository = configRepository;
    }
    
    @Autowired(required = false)
    public void setScheduleManager(ScheduleManagerService scheduleManager) { this.scheduleManager = scheduleManager; }

    @Autowired(required = false)
    public void setAdaptiveEngine(@org.springframework.context.annotation.Lazy AdaptiveRateLimitEngine adaptiveEngine) { this.adaptiveEngine = adaptiveEngine; }

    public RateLimitConfig resolveConfig(String key) {
        // 1. Get Base Config First to check toggle
        RateLimitConfig base = getBaseConfig(key);

        // 2. Adaptive Override (only if enabled)
        if (adaptiveEngine != null && base.isAdaptiveEnabled()) {
            AdaptiveRateLimitEngine.AdaptedLimits adapted = adaptiveEngine.getAdaptedLimits(key);
            if (adapted != null) {
                return new RateLimitConfig(adapted.adaptedCapacity, adapted.adaptedRefillRate, base.getCleanupIntervalMs(), base.getAlgorithm(), true);
            }
        }

        // 3. Time-based Schedules
        if (scheduleManager != null) {
            RateLimitConfig scheduledConfig = scheduleManager.getActiveConfig(key);
            if (scheduledConfig != null) {
                return scheduledConfig;
            }
        }

        // 4. Cache Check
        CachedConfig cached = configCache.get(key);
        if (cached != null && (System.currentTimeMillis() - cached.timestamp) < CACHE_TTL_MS) {
            return cached.config;
        }

        // 5. Real-time Resolve from Redis
        configCache.put(key, new CachedConfig(base));
        return base;
    }

    public RateLimitConfig getBaseConfig(String key) {
        return doResolveFromRedis(key);
    }

    private RateLimitConfig doResolveFromRedis(String key) {
        if (configRepository == null) return localConfiguration.getDefaultConfig();

        Map<String, RateLimiterConfiguration.KeyConfig> keyConfigs = configRepository.loadKeyConfigs();
        if (keyConfigs.containsKey(key)) {
            return convert(keyConfigs.get(key));
        }

        Map<String, RateLimiterConfiguration.KeyConfig> patterns = configRepository.loadPatternConfigs();
        for (Map.Entry<String, RateLimiterConfiguration.KeyConfig> entry : patterns.entrySet()) {
            if (matchesPattern(key, entry.getKey())) return convert(entry.getValue());
        }

        Map<Object, Object> redisDefault = configRepository.loadDefaultConfig();
        if (redisDefault != null && !redisDefault.isEmpty()) {
            return new RateLimitConfig(
                (Integer) redisDefault.get("capacity"),
                (Integer) redisDefault.get("refillRate"),
                ((Number) redisDefault.get("cleanupIntervalMs")).longValue(),
                RateLimitAlgorithm.valueOf((String) redisDefault.get("algorithm")),
                redisDefault.containsKey("adaptiveEnabled") ? (Boolean) redisDefault.get("adaptiveEnabled") : true
            );
        }

        return localConfiguration.getDefaultConfig();
    }

    private RateLimitConfig convert(RateLimiterConfiguration.KeyConfig kc) {
        return new RateLimitConfig(
            kc.getCapacity() > 0 ? kc.getCapacity() : localConfiguration.getCapacity(),
            kc.getRefillRate() > 0 ? kc.getRefillRate() : localConfiguration.getRefillRate(),
            kc.getCleanupIntervalMs() != null ? kc.getCleanupIntervalMs() : localConfiguration.getCleanupIntervalMs(),
            kc.getAlgorithm() != null ? kc.getAlgorithm() : localConfiguration.getAlgorithm(),
            kc.isAdaptiveEnabled()
        );
    }

    // --- RESTORED METHODS FOR COMPATIBILITY ---

    public void updateKeyConfig(String key, RateLimitConfig config) {
        RateLimiterConfiguration.KeyConfig kc = new RateLimiterConfiguration.KeyConfig();
        kc.setCapacity(config.getCapacity());
        kc.setRefillRate(config.getRefillRate());
        kc.setAlgorithm(config.getAlgorithm());
        kc.setAdaptiveEnabled(config.isAdaptiveEnabled());
        localConfiguration.putKey(key, kc);
        
        // If we just turned adaptive OFF, clear any stored adaptive limits immediately
        if (adaptiveEngine != null && !config.isAdaptiveEnabled()) {
            adaptiveEngine.removeOverride(key);
        }
        
        clearCache();
    }

    public void updatePatternConfig(String pattern, RateLimitConfig config) {
        RateLimiterConfiguration.KeyConfig kc = new RateLimiterConfiguration.KeyConfig();
        kc.setCapacity(config.getCapacity());
        kc.setRefillRate(config.getRefillRate());
        kc.setAlgorithm(config.getAlgorithm());
        kc.setAdaptiveEnabled(config.isAdaptiveEnabled());
        localConfiguration.putPattern(pattern, kc);
        
        // If we just turned adaptive OFF, clear any stored adaptive limits immediately
        if (adaptiveEngine != null && !config.isAdaptiveEnabled()) {
            adaptiveEngine.removeOverride(pattern);
        }
        
        clearCache();
    }

    public void removeKeyConfig(String key) {
        localConfiguration.removeKey(key);
        
        // Ensure consistency with adaptive engine - complete removal
        if (adaptiveEngine != null) {
            adaptiveEngine.removeAdaptiveStatus(key);
        }
        
        clearCache();
    }

    public void removePatternConfig(String pattern) {
        localConfiguration.removePattern(pattern);
        
        // Ensure consistency with adaptive engine - complete removal
        if (adaptiveEngine != null) {
            adaptiveEngine.removeAdaptiveStatus(pattern);
        }
        
        clearCache();
    }

    public int getCacheSize() { return configCache.size(); }

    private boolean matchesPattern(String key, String pattern) {
        if (pattern.equals("*")) return true;
        String regex = pattern.replace("*", ".*");
        return key.matches("^" + regex + "$");
    }

    public void clearCache() { configCache.clear(); }
    public java.util.Set<String> getValidConfigKeys() {
        java.util.Set<String> keys = new java.util.HashSet<>();
        keys.addAll(localConfiguration.getKeys().keySet());
        keys.addAll(localConfiguration.getPatterns().keySet());
        if (configRepository != null) {
            keys.addAll(configRepository.loadKeyConfigs().keySet());
            keys.addAll(configRepository.loadPatternConfigs().keySet());
        }
        return keys;
    }    public String resolveBaseKey(String key) { return key; }
}
