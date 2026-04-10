package dev.bnacar.distributedratelimiter.adaptive;

import dev.bnacar.distributedratelimiter.ratelimit.ConfigurationResolver;
import dev.bnacar.distributedratelimiter.ratelimit.RateLimitConfig;
import dev.bnacar.distributedratelimiter.ratelimit.RateLimiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdaptiveRateLimitEngineTest {

    private AdaptiveRateLimitEngine adaptiveEngine;

    @Mock
    private SystemMetricsCollector metricsCollector;
    @Mock
    private UserMetricsModeler userMetricsModeler;
    @Mock
    private AimdPolicyEngine policyEngine;
    @Mock
    private ConfigurationResolver configurationResolver;
    @Mock
    private RateLimiterService rateLimiterService;
    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);

        adaptiveEngine = new AdaptiveRateLimitEngine(
            metricsCollector,
            userMetricsModeler,
            policyEngine,
            configurationResolver,
            rateLimiterService,
            redisTemplate,
            true,   // enabled
            0.7,    // minConfidenceThreshold
            2.0,    // maxAdjustmentFactor
            5,     // minCapacity
            100000  // maxCapacity
        );
    }

    @Test
    void testEvaluateAdaptations_UsesBulkMetricsAndAppliesDecision() {
        // Setup
        String key = "test-key";
        Set<String> allKeys = Set.of(key);
        when(configurationResolver.getValidConfigKeys()).thenReturn(allKeys);
        // Important: Mock specific keys so the engine actually processes the key
        when(configurationResolver.getSpecificKeysOnly()).thenReturn(allKeys);
        
        RateLimitConfig baseConfig = new RateLimitConfig(100, 20, 60000, null, true);
        when(configurationResolver.getBaseConfig(key)).thenReturn(baseConfig);
        
        // Mock Bulk Metrics calculation (The new Zero N+1 approach)
        UserMetrics userMetrics = UserMetrics.builder().zScore(1.0).build();
        Map<String, UserMetrics> metricsMap = Map.of(key, userMetrics);
        when(userMetricsModeler.fetchAndCalculateAllMetrics(allKeys)).thenReturn(metricsMap);

        SystemHealth health = SystemHealth.builder().cpuUtilization(0.55).build();
        when(metricsCollector.getCurrentHealth()).thenReturn(health);

        AdaptationDecision decision = AdaptationDecision.builder()
            .shouldAdapt(true)
            .recommendedCapacity(102)
            .recommendedRefillRate(20)
            .confidence(1.0)
            .reasoning(Map.of("decision", "HEALTHY"))
            .build();
        
        when(policyEngine.determineAdaptation(eq(health), eq(userMetrics), anyInt(), anyInt()))
            .thenReturn(decision);

        // Execute
        adaptiveEngine.evaluateAdaptations();

        // Verify
        verify(userMetricsModeler).fetchAndCalculateAllMetrics(allKeys);
        verify(hashOperations).put(eq("ratelimiter:adaptive:limits"), eq(key), any(AdaptiveRateLimitEngine.AdaptedLimits.class));
    }

    @Test
    void testGetStatus_Disabled() {
        String key = "test-key";
        RateLimitConfig baseConfig = new RateLimitConfig(100, 20, 60000, null, false); // Adaptive DISABLED
        when(configurationResolver.getBaseConfig(key)).thenReturn(baseConfig);

        AdaptiveRateLimitEngine.AdaptiveStatusInfo status = adaptiveEngine.getStatus(key);

        assertEquals("DISABLED", status.mode);
        assertFalse(status.isAdaptiveEnabled());
    }
}
