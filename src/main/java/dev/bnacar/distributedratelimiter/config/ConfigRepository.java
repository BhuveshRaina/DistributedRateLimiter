package dev.bnacar.distributedratelimiter.config;

import dev.bnacar.distributedratelimiter.ratelimit.RateLimitAlgorithm;
import dev.bnacar.distributedratelimiter.ratelimit.RateLimiterConfiguration;
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
 */
@Repository
public class ConfigRepository {
    private static final Logger logger = LoggerFactory.getLogger(ConfigRepository.class);
    
    private static final String CONFIG_PREFIX = "ratelimiter:config:";
    private static final String DEFAULT_CONFIG_KEY = CONFIG_PREFIX + "default";
    private static final String KEYS_CONFIG_KEY = CONFIG_PREFIX + "keys";
    private static final String PATTERNS_CONFIG_KEY = CONFIG_PREFIX + "patterns";

    private final RedisTemplate<String, Object> redisTemplate;

    public ConfigRepository(@Qualifier("rateLimiterRedisTemplate") RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Save global default configuration to Redis.
     */
    public void saveDefaultConfig(int capacity, int refillRate, long cleanupIntervalMs, RateLimitAlgorithm algorithm) {
        Map<String, Object> config = new HashMap<>();
        config.put("capacity", capacity);
        config.put("refillRate", refillRate);
        config.put("cleanupIntervalMs", cleanupIntervalMs);
        config.put("algorithm", algorithm.name());
        
        redisTemplate.opsForHash().putAll(DEFAULT_CONFIG_KEY, config);
        logger.info("Saved default configuration to Redis");
    }

    /**
     * Load global default configuration from Redis.
     */
    public Map<Object, Object> loadDefaultConfig() {
        return redisTemplate.opsForHash().entries(DEFAULT_CONFIG_KEY);
    }

    /**
     * Save a specific key configuration.
     */
    public void saveKeyConfig(String key, RateLimiterConfiguration.KeyConfig config) {
        redisTemplate.opsForHash().put(KEYS_CONFIG_KEY, key, serializeKeyConfig(config));
    }

    /**
     * Remove a specific key configuration.
     */
    public void deleteKeyConfig(String key) {
        redisTemplate.opsForHash().delete(KEYS_CONFIG_KEY, key);
    }

    /**
     * Load all key configurations.
     */
    public Map<String, RateLimiterConfiguration.KeyConfig> loadKeyConfigs() {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(KEYS_CONFIG_KEY);
        return entries.entrySet().stream()
            .collect(Collectors.toMap(
                e -> (String) e.getKey(),
                e -> deserializeKeyConfig((Map<String, Object>) e.getValue())
            ));
    }

    /**
     * Save a specific pattern configuration.
     */
    public void savePatternConfig(String pattern, RateLimiterConfiguration.KeyConfig config) {
        redisTemplate.opsForHash().put(PATTERNS_CONFIG_KEY, pattern, serializeKeyConfig(config));
    }

    /**
     * Remove a specific pattern configuration.
     */
    public void deletePatternConfig(String pattern) {
        redisTemplate.opsForHash().delete(PATTERNS_CONFIG_KEY, pattern);
    }

    /**
     * Load all pattern configurations.
     */
    public Map<String, RateLimiterConfiguration.KeyConfig> loadPatternConfigs() {
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
        if (config.getCleanupIntervalMs() != null) {
            map.put("cleanupIntervalMs", config.getCleanupIntervalMs());
        }
        if (config.getAlgorithm() != null) {
            map.put("algorithm", config.getAlgorithm().name());
        }
        return map;
    }

    private RateLimiterConfiguration.KeyConfig deserializeKeyConfig(Map<String, Object> map) {
        RateLimiterConfiguration.KeyConfig config = new RateLimiterConfiguration.KeyConfig();
        config.setCapacity((Integer) map.get("capacity"));
        config.setRefillRate((Integer) map.get("refillRate"));
        if (map.containsKey("cleanupIntervalMs")) {
            config.setCleanupIntervalMs(((Number) map.get("cleanupIntervalMs")).longValue());
        }
        if (map.containsKey("algorithm")) {
            config.setAlgorithm(RateLimitAlgorithm.valueOf((String) map.get("algorithm")));
        }
        return config;
    }
}
