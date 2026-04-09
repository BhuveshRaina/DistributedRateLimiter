package dev.bnacar.distributedratelimiter.config;

import dev.bnacar.distributedratelimiter.ratelimit.RateLimitAlgorithm;
import dev.bnacar.distributedratelimiter.ratelimit.RateLimiterConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.HashMap;
import java.util.stream.Collectors;

/**
 * Repository for persisting rate limiter configuration in Redis.
 * Supports optional Redis for lightweight nodes.
 */
@Repository
public class ConfigRepository {
    private static final Logger logger = LoggerFactory.getLogger(ConfigRepository.class);
    
    private static final String CONFIG_PREFIX = "ratelimiter:config:";
    private static final String DEFAULT_CONFIG_KEY = CONFIG_PREFIX + "default";
    private static final String KEYS_CONFIG_KEY = CONFIG_PREFIX + "keys";
    private static final String PATTERNS_CONFIG_KEY = CONFIG_PREFIX + "patterns";

    private final RedisTemplate<String, Object> redisTemplate;

    @Autowired
    public ConfigRepository(@Autowired(required = false) @Qualifier("rateLimiterRedisTemplate") RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void saveDefaultConfig(int capacity, int refillRate, long cleanupIntervalMs, RateLimitAlgorithm algorithm) {
        if (redisTemplate == null) return;
        Map<String, Object> config = new HashMap<>();
        config.put("capacity", capacity);
        config.put("refillRate", refillRate);
        config.put("cleanupIntervalMs", cleanupIntervalMs);
        config.put("algorithm", algorithm.name());
        redisTemplate.opsForHash().putAll(DEFAULT_CONFIG_KEY, config);
    }

    public Map<Object, Object> loadDefaultConfig() {
        if (redisTemplate == null) return new HashMap<>();
        return redisTemplate.opsForHash().entries(DEFAULT_CONFIG_KEY);
    }

    public void saveKeyConfig(String key, RateLimiterConfiguration.KeyConfig config) {
        if (redisTemplate == null) return;
        redisTemplate.opsForHash().put(KEYS_CONFIG_KEY, key, serializeKeyConfig(config));
    }

    public void deleteKeyConfig(String key) {
        if (redisTemplate == null) return;
        redisTemplate.opsForHash().delete(KEYS_CONFIG_KEY, key);
    }

    public Map<String, RateLimiterConfiguration.KeyConfig> loadKeyConfigs() {
        if (redisTemplate == null) return new HashMap<>();
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(KEYS_CONFIG_KEY);
        return entries.entrySet().stream()
            .collect(Collectors.toMap(
                e -> (String) e.getKey(),
                e -> deserializeKeyConfig((Map<String, Object>) e.getValue())
            ));
    }

    public void savePatternConfig(String pattern, RateLimiterConfiguration.KeyConfig config) {
        if (redisTemplate == null) return;
        redisTemplate.opsForHash().put(PATTERNS_CONFIG_KEY, pattern, serializeKeyConfig(config));
    }

    public void deletePatternConfig(String pattern) {
        if (redisTemplate == null) return;
        redisTemplate.opsForHash().delete(PATTERNS_CONFIG_KEY, pattern);
    }

    public Map<String, RateLimiterConfiguration.KeyConfig> loadPatternConfigs() {
        if (redisTemplate == null) return new HashMap<>();
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(PATTERNS_CONFIG_KEY);
        return entries.entrySet().stream()
            .collect(Collectors.toMap(
                e -> (String) e.getKey(),
                e -> deserializeKeyConfig((Map<String, Object>) e.getValue())
            ));
    }

    private Map<String, Object> serializeKeyConfig(RateLimiterConfiguration.KeyConfig config) {
        Map<String, Object> map = new HashMap<>();
        map.put("capacity", config.getCapacity());
        map.put("refillRate", config.getRefillRate());
        map.put("adaptiveEnabled", config.isAdaptiveEnabled());
        if (config.getCleanupIntervalMs() != null) map.put("cleanupIntervalMs", config.getCleanupIntervalMs());
        if (config.getAlgorithm() != null) map.put("algorithm", config.getAlgorithm().name());
        return map;
    }

    private RateLimiterConfiguration.KeyConfig deserializeKeyConfig(Map<String, Object> map) {
        RateLimiterConfiguration.KeyConfig config = new RateLimiterConfiguration.KeyConfig();
        config.setCapacity((Integer) map.get("capacity"));
        config.setRefillRate((Integer) map.get("refillRate"));
        if (map.containsKey("adaptiveEnabled")) {
            config.setAdaptiveEnabled((Boolean) map.get("adaptiveEnabled"));
        } else {
            config.setAdaptiveEnabled(true);
        }
        if (map.containsKey("cleanupIntervalMs")) config.setCleanupIntervalMs(((Number) map.get("cleanupIntervalMs")).longValue());
        if (map.containsKey("algorithm")) config.setAlgorithm(RateLimitAlgorithm.valueOf((String) map.get("algorithm")));
        return config;
    }
}
