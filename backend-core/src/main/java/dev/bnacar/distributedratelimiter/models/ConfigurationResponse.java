package dev.bnacar.distributedratelimiter.models;

import dev.bnacar.distributedratelimiter.ratelimit.RateLimiterConfiguration;

import java.util.Map;
import java.util.HashMap;

public class ConfigurationResponse {
    private final int capacity;
    private final int refillRate;
    private final long cleanupIntervalMs;
    private final Map<String, RateLimiterConfiguration.KeyConfig> keyConfigs;
    private final Map<String, RateLimiterConfiguration.KeyConfig> patternConfigs;
    private final Map<String, RateLimiterConfiguration.KeyConfig> effectiveKeyConfigs;

    public ConfigurationResponse(int capacity, int refillRate, long cleanupIntervalMs,
                                Map<String, RateLimiterConfiguration.KeyConfig> keyConfigs,
                                Map<String, RateLimiterConfiguration.KeyConfig> patternConfigs,
                                Map<String, RateLimiterConfiguration.KeyConfig> effectiveKeyConfigs) {
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.cleanupIntervalMs = cleanupIntervalMs;
        this.keyConfigs = keyConfigs != null ? new HashMap<>(keyConfigs) : new HashMap<>();
        this.patternConfigs = patternConfigs != null ? new HashMap<>(patternConfigs) : new HashMap<>();
        this.effectiveKeyConfigs = effectiveKeyConfigs != null ? new HashMap<>(effectiveKeyConfigs) : new HashMap<>();
    }

    public int getCapacity() {
        return capacity;
    }

    public int getRefillRate() {
        return refillRate;
    }

    public long getCleanupIntervalMs() {
        return cleanupIntervalMs;
    }

    public Map<String, RateLimiterConfiguration.KeyConfig> getKeyConfigs() {
        return new HashMap<>(keyConfigs);
    }

    public Map<String, RateLimiterConfiguration.KeyConfig> getPatternConfigs() {
        return new HashMap<>(patternConfigs);
    }

    public Map<String, RateLimiterConfiguration.KeyConfig> getEffectiveKeyConfigs() {
        return new HashMap<>(effectiveKeyConfigs);
    }
}