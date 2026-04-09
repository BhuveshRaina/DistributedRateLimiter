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
            10,     // minCapacity
            100000  // maxCapacity
        );
    }

    @Test
    void testEvaluateAdaptations_AppliesDecision() {
        // Setup
        String key = "test-key";
        when(configurationResolver.getValidConfigKeys()).thenReturn(Set.of(key));
        RateLimitConfig baseConfig = new RateLimitConfig(100, 20, 60000, null, true);
        when(configurationResolver.getBaseConfig(key)).thenReturn(baseConfig);
        
        // Return null for existing adapted limits to trigger new decision
        when(hashOperations.get(anyString(), eq(key))).thenReturn(null);

        SystemHealth health = SystemHealth.builder().cpuUtilization(0.2).build();
        when(metricsCollector.getCurrentHealth()).thenReturn(health);
        
        UserMetrics userMetrics = UserMetrics.builder().build();
        when(userMetricsModeler.getUserMetrics(key)).thenReturn(userMetrics);

        AdaptationDecision decision = AdaptationDecision.builder()
            .shouldAdapt(true)
            .recommendedCapacity(110)
            .recommendedRefillRate(22)
            .confidence(0.9)
            .reasoning(Map.of("decision", "HEALTHY"))
            .build();
        when(policyEngine.determineAdaptation(any(), any(), anyInt(), anyInt())).thenReturn(decision);

        // Execute
        adaptiveEngine.evaluateAdaptations();

        // Verify
        verify(hashOperations).put(eq("ratelimiter:adaptive:limits"), eq(key), any(AdaptiveRateLimitEngine.AdaptedLimits.class));
    }

    @Test
    void testEvaluateAdaptations_RespectsConfidenceThreshold() {
        // Setup
        String key = "test-key";
        when(configurationResolver.getValidConfigKeys()).thenReturn(Set.of(key));
        RateLimitConfig baseConfig = new RateLimitConfig(100, 20, 60000, null, true);
        when(configurationResolver.getBaseConfig(key)).thenReturn(baseConfig);
        
        when(hashOperations.get(anyString(), eq(key))).thenReturn(null);

        AdaptationDecision lowConfidenceDecision = AdaptationDecision.builder()
            .shouldAdapt(true)
            .recommendedCapacity(150)
            .confidence(0.5) // Below 0.7 threshold
            .build();
        when(policyEngine.determineAdaptation(any(), any(), anyInt(), anyInt())).thenReturn(lowConfidenceDecision);

        // Execute
        adaptiveEngine.evaluateAdaptations();

        // Verify NO adaptation was applied
        verify(hashOperations, never()).put(eq("ratelimiter:adaptive:limits"), anyString(), any());
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
