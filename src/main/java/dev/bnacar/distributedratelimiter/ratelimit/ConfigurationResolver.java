package dev.bnacar.distributedratelimiter.ratelimit;

import dev.bnacar.distributedratelimiter.adaptive.AdaptiveRateLimitEngine;
import dev.bnacar.distributedratelimiter.schedule.ScheduleManagerService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service responsible for resolving rate limit configuration for specific keys.
 * Supports exact key matches, pattern matching, and fallback to default configuration.
 * Also checks for active scheduled overrides and adaptive limits.
 */
@Service
public class ConfigurationResolver {
    
    private final RateLimiterConfiguration configuration;
    private ScheduleManagerService scheduleManager;
    private AdaptiveRateLimitEngine adaptiveEngine;
    
    // Cache for resolved configurations to avoid repeated pattern matching
    private final ConcurrentHashMap<String, RateLimitConfig> configCache = new ConcurrentHashMap<>();
    
    @Autowired
    public ConfigurationResolver(RateLimiterConfiguration configuration) {
        this.configuration = configuration;
    }
    
    /**
     * Set the schedule manager for schedule-based configuration resolution.
     */
    @Autowired(required = false)
    public void setScheduleManager(ScheduleManagerService scheduleManager) {
        this.scheduleManager = scheduleManager;
    }

    /**
     * Set the adaptive engine for adaptive-based configuration resolution.
     */
    @Autowired(required = false)
    public void setAdaptiveEngine(@org.springframework.context.annotation.Lazy AdaptiveRateLimitEngine adaptiveEngine) {
        this.adaptiveEngine = adaptiveEngine;
    }
    
    /**
     * Check if a key is explicitly registered in the configuration (exact or pattern).
     */
    public boolean isKeyRegistered(String key) {
        // 1. Check exact key match
        if (configuration.getKeys().containsKey(key)) {
            return true;
        }
        
        // 2. Check pattern matches
        for (String pattern : configuration.getPatterns().keySet()) {
            if (matchesPattern(key, pattern)) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * Resolve the appropriate rate limit configuration for the given key.
     * Order of precedence:
     * 1. Active scheduled overrides (highest priority)
     * 2. Adaptive limits (if enabled and present)
     * 3. Exact key match in per-key overrides
     * 4. Pattern match in pattern configurations (first match wins)
     * 5. Default configuration
     */
    public RateLimitConfig resolveConfig(String key) {
        String canonicalKey = resolveBaseKey(key);
        
        // 1. Check for active scheduled overrides first
        if (scheduleManager != null) {
            RateLimitConfig scheduledConfig = scheduleManager.getActiveConfig(canonicalKey);
            if (scheduledConfig != null) {
                return scheduledConfig;
            }
        }

        // 2. Check for adaptive limits (includes manual overrides from adaptive engine)
        if (adaptiveEngine != null) {
            AdaptiveRateLimitEngine.AdaptedLimits adapted = adaptiveEngine.getAdaptedLimits(canonicalKey);
            if (adapted != null) {
                // IMPORTANT: We bypass cache here to ensure overrides are applied instantly
                // Create a config using adapted values
                RateLimitConfig baseConfig = getBaseConfig(canonicalKey);
                return new RateLimitConfig(
                    adapted.adaptedCapacity,
                    adapted.adaptedRefillRate,
                    baseConfig.getCleanupIntervalMs(),
                    baseConfig.getAlgorithm()
                );
            }
        }
        
        // 3. Check cache for standard configurations
        RateLimitConfig cached = configCache.get(key);
        if (cached != null) {
            return cached;
        }
        
        RateLimitConfig resolved = doResolveConfig(key);
        
        // Cache the resolved configuration
        configCache.put(key, resolved);
        
        return resolved;
    }

    /**
     * Helper to get the non-adaptive base configuration for a key.
     */
    public RateLimitConfig getBaseConfig(String key) {
        String canonicalKey = resolveBaseKey(key);
        
        // Check cache for base config
        RateLimitConfig cached = configCache.get(canonicalKey);
        if (cached != null) return cached;

        RateLimitConfig resolved = doResolveConfig(canonicalKey);
        configCache.put(canonicalKey, resolved);
        return resolved;
    }
    
    /**
     * Resolve the canonical base key for a given input key.
     * In this system, we use exact matches, so the base key is the key itself.
     * This ensures that multi-threaded requests using the same key string
     * will share the same bucket.
     */
    public String resolveBaseKey(String key) {
        return key;
    }

    private RateLimitConfig doResolveConfig(String key) {
        // 1. Try to resolve the key as an exact match
        RateLimiterConfiguration.KeyConfig exact = configuration.getKeys().get(key);
        if (exact != null) {
            return createConfig(exact);
        }

        // 2. Check pattern matches
        for (Map.Entry<String, RateLimiterConfiguration.KeyConfig> entry : configuration.getPatterns().entrySet()) {
            if (matchesPattern(key, entry.getKey())) {
                return createConfig(entry.getValue());
            }
        }

        // 3. Return default configuration
        return configuration.getDefaultConfig();
    }
    
    /**
     * Create RateLimitConfig from KeyConfig, using defaults for unspecified values.
     */
    private RateLimitConfig createConfig(RateLimiterConfiguration.KeyConfig keyConfig) {
        int capacity = keyConfig.getCapacity() > 0 ? keyConfig.getCapacity() : configuration.getCapacity();
        int refillRate = keyConfig.getRefillRate() > 0 ? keyConfig.getRefillRate() : configuration.getRefillRate();
        long cleanupInterval = keyConfig.getCleanupIntervalMs() != null && keyConfig.getCleanupIntervalMs() > 0 
            ? keyConfig.getCleanupIntervalMs() 
            : configuration.getCleanupIntervalMs();
        RateLimitAlgorithm algorithm = keyConfig.getAlgorithm() != null 
            ? keyConfig.getAlgorithm() 
            : configuration.getAlgorithm();
            
        return new RateLimitConfig(capacity, refillRate, cleanupInterval, algorithm);
    }
    
    /**
     * Simple pattern matching supporting '*' wildcard.
     * Examples:
     * - "user:*" matches "user:123", "user:abc", etc.
     * - "*:admin" matches "user:admin", "system:admin", etc.
     * - "api:v1:*" matches "api:v1:users", "api:v1:orders", etc.
     */
    private boolean matchesPattern(String key, String pattern) {
        if (pattern.equals("*")) {
            return true;
        }
        
        if (!pattern.contains("*")) {
            return key.equals(pattern);
        }
        
        // Convert pattern to regex: escape special chars except *, then replace * with .*
        String regex = pattern
            .replace("\\", "\\\\")
            .replace(".", "\\.")
            .replace("+", "\\+")
            .replace("?", "\\?")
            .replace("^", "\\^")
            .replace("$", "\\$")
            .replace("|", "\\|")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace("[", "\\[")
            .replace("]", "\\]")
            .replace("{", "\\{")
            .replace("}", "\\}")
            .replace("*", ".*");
            
        return key.matches("^" + regex + "$");
    }
    
    /**
     * Clear the configuration cache. Useful when configuration is updated.
     */
    public void clearCache() {
        configCache.clear();
    }
    
    /**
     * Get cache size for testing/monitoring purposes.
     */
    public int getCacheSize() {
        return configCache.size();
    }
}