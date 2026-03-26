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
     * This handles stripping thread suffixes to find the shared bucket key
     * while preserving numeric user IDs that are part of the intended key.
     */
    public String resolveBaseKey(String key) {
        // 1. If the key as-is is an explicit configuration key, use it
        if (configuration.getKeys().containsKey(key)) {
            return key;
        }

        // 2. Only strip numeric suffix if the prefix is a registered key
        // This allows user:123:1 (where 1 is a thread) to map to user:123
        // while allowing user:123 to remain user:123 if it's the registered key
        int lastColon = key.lastIndexOf(':');
        if (lastColon > 0) {
            String suffix = key.substring(lastColon + 1);
            if (suffix.matches("\\d+")) {
                String prefix = key.substring(0, lastColon);
                if (configuration.getKeys().containsKey(prefix)) {
                    return prefix;
                }
            }
        }

        // 3. For pattern matches or unknown keys, we keep the key as-is
        // to ensure per-ID bucketing (e.g. user:123 and user:456 get separate buckets)
        return key;
    }

    private String stripNumericSuffix(String key) {
        // This method is now effectively integrated into resolveBaseKey for better context
        int lastColon = key.lastIndexOf(':');
        if (lastColon > 0) {
            String suffix = key.substring(lastColon + 1);
            if (suffix.matches("\\d+")) {
                return key.substring(0, lastColon);
            }
        }
        return key;
    }

    private RateLimitConfig doResolveConfig(String key) {
        String canonicalKey = resolveBaseKey(key);
        
        // 1. Try to resolve the key as-is (using canonical version)
        RateLimitConfig config = resolveFromExactOrSuffix(canonicalKey);
        if (config != null) return config;

        // 2. Check pattern matches (original key)
        for (Map.Entry<String, RateLimiterConfiguration.KeyConfig> entry : configuration.getPatterns().entrySet()) {
            if (matchesPattern(canonicalKey, entry.getKey())) {
                return createConfig(entry.getValue());
            }
        }

        // 3. Return default configuration
        return configuration.getDefaultConfig();
    }

    /**
     * Helper to resolve a key by checking exact match or prefix (for suffixes like :1, :2).
     */
    private RateLimitConfig resolveFromExactOrSuffix(String key) {
        // Exact match
        RateLimiterConfiguration.KeyConfig exact = configuration.getKeys().get(key);
        if (exact != null) return createConfig(exact);

        // Suffix match (strip the last colon part)
        int lastColon = key.lastIndexOf(':');
        if (lastColon > 0) {
            String prefix = key.substring(0, lastColon);
            RateLimiterConfiguration.KeyConfig prefixConfig = configuration.getKeys().get(prefix);
            if (prefixConfig != null) return createConfig(prefixConfig);
        }
        
        return null;
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