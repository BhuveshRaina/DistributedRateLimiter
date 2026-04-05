package dev.bnacar.distributedratelimiter.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConfigurationResolverTest {

    private RateLimiterConfiguration configuration;
    private ConfigurationResolver resolver;

    @BeforeEach
    void setUp() {
        configuration = new RateLimiterConfiguration();
        // Set Global Defaults
        configuration.setCapacity(100);
        configuration.setRefillRate(10);
        configuration.setAlgorithm(RateLimitAlgorithm.TOKEN_BUCKET);
        
        resolver = new ConfigurationResolver(configuration);
    }

    @Test
    void testResolveConfig_ExactKeyMatch_HighestPriority() {
        String key = "special-user";
        RateLimiterConfiguration.KeyConfig keyConfig = new RateLimiterConfiguration.KeyConfig();
        keyConfig.setCapacity(500);
        keyConfig.setRefillRate(50);
        configuration.putKey(key, keyConfig);

        RateLimitConfig resolved = resolver.resolveConfig(key);

        assertEquals(500, resolved.getCapacity());
        assertEquals(50, resolved.getRefillRate());
    }

    @Test
    void testResolveConfig_PatternMatch_MediumPriority() {
        String pattern = "api:v1:*";
        String key = "api:v1:users";
        
        RateLimiterConfiguration.KeyConfig patternConfig = new RateLimiterConfiguration.KeyConfig();
        patternConfig.setCapacity(200);
        patternConfig.setRefillRate(20);
        configuration.putPattern(pattern, patternConfig);

        RateLimitConfig resolved = resolver.resolveConfig(key);

        assertEquals(200, resolved.getCapacity());
        assertEquals(20, resolved.getRefillRate());
    }

    @Test
    void testResolveConfig_FallbackToGlobalDefault() {
        String key = "unknown-user";

        RateLimitConfig resolved = resolver.resolveConfig(key);

        assertEquals(100, resolved.getCapacity()); // From global default
        assertEquals(10, resolved.getRefillRate());
    }

    @Test
    void testResolveConfig_Precedence_ExactOverPattern() {
        String key = "api:v1:admin";
        String pattern = "api:v1:*";
        
        // Pattern Config
        RateLimiterConfiguration.KeyConfig patternConfig = new RateLimiterConfiguration.KeyConfig();
        patternConfig.setCapacity(200);
        configuration.putPattern(pattern, patternConfig);
        
        // Exact Config (should win)
        RateLimiterConfiguration.KeyConfig exactConfig = new RateLimiterConfiguration.KeyConfig();
        exactConfig.setCapacity(999);
        configuration.putKey(key, exactConfig);

        RateLimitConfig resolved = resolver.resolveConfig(key);

        assertEquals(999, resolved.getCapacity());
    }
}
