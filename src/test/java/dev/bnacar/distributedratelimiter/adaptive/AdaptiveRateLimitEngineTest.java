package dev.bnacar.distributedratelimiter.adaptive;

import dev.bnacar.distributedratelimiter.ratelimit.ConfigurationResolver;
import dev.bnacar.distributedratelimiter.ratelimit.RateLimitConfig;
import dev.bnacar.distributedratelimiter.ratelimit.RateLimiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdaptiveRateLimitEngineTest {

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

    private AdaptiveRateLimitEngine engine;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        engine = new AdaptiveRateLimitEngine(
            metricsCollector, userMetricsModeler, policyEngine, 
            configurationResolver, rateLimiterService,
            true, 300000, 0.7, 10.0, 2, 100000
        );
    }

    @Test
    void testRecordTrafficEvent_TracksKey() {
        String key = "user1";
        engine.recordTrafficEvent(key, 1, true);
        
        // Internal state check via status
        RateLimitConfig config = new RateLimitConfig(100, 10);
        when(configurationResolver.getBaseConfig(key)).thenReturn(config);
        
        AdaptiveRateLimitEngine.AdaptiveStatusInfo status = engine.getStatus(key);
        assertEquals("ADAPTIVE", status.mode);
        verify(userMetricsModeler).recordRequest(key, 1, true);
    }

    @Test
    void testEvaluateAdaptations_AppliesRecommendation() {
        String key = "busy-user";
        engine.recordTrafficEvent(key, 1, true);
        
        RateLimitConfig baseConfig = new RateLimitConfig(100, 10);
        when(configurationResolver.getBaseConfig(key)).thenReturn(baseConfig);
        
        SystemHealth health = SystemHealth.builder().cpuUtilization(0.9).build();
        when(metricsCollector.getCurrentHealth()).thenReturn(health);
        
        UserMetrics userMetrics = UserMetrics.builder().build();
        when(userMetricsModeler.getUserMetrics(key)).thenReturn(userMetrics);
        
        // Policy recommends 50% cut due to high CPU
        AdaptationDecision decision = AdaptationDecision.builder()
            .shouldAdapt(true)
            .recommendedCapacity(50)
            .recommendedRefillRate(5)
            .confidence(1.0)
            .reasoning(Map.of("reason", "high cpu"))
            .build();
            
        when(policyEngine.determineAdaptation(any(), any(), anyInt(), anyInt())).thenReturn(decision);
        
        engine.evaluateAdaptations();
        
        AdaptiveRateLimitEngine.AdaptiveStatusInfo status = engine.getStatus(key);
        assertEquals(50, status.currentCapacity);
        assertEquals(5, status.currentRefillRate);
    }

    @Test
    void testManualOverride_TakesPrecedence() {
        String key = "override-me";
        RateLimitConfig baseConfig = new RateLimitConfig(100, 10);
        when(configurationResolver.getBaseConfig(key)).thenReturn(baseConfig);
        
        engine.setOverride(key, new AdaptiveRateLimitEngine.AdaptationOverride(500, 50, "Promo event"));
        
        AdaptiveRateLimitEngine.AdaptiveStatusInfo status = engine.getStatus(key);
        assertEquals("OVERRIDE", status.mode);
        assertEquals(500, status.currentCapacity);
        assertEquals(50, status.currentRefillRate);
    }
}
