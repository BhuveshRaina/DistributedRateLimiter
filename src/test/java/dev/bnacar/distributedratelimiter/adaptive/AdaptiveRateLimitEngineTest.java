package dev.bnacar.distributedratelimiter.adaptive;

import dev.bnacar.distributedratelimiter.ratelimit.ConfigurationResolver;
import dev.bnacar.distributedratelimiter.ratelimit.RateLimitConfig;
import dev.bnacar.distributedratelimiter.ratelimit.RateLimiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdaptiveRateLimitEngineTest {

    @Mock
    private SystemMetricsCollector metricsCollector;

    @Mock
    private UserMetricsModeler userMetricsModeler;

    @Mock
    private AdaptiveMLModel adaptiveModel;

    @Mock
    private ConfigurationResolver configurationResolver;

    @Mock
    private RateLimiterService rateLimiterService;

    private AdaptiveRateLimitEngine engine;

    @BeforeEach
    void setUp() {
        engine = new AdaptiveRateLimitEngine(
            metricsCollector,
            userMetricsModeler,
            adaptiveModel,
            configurationResolver,
            rateLimiterService,
            true,        // enabled
            300000L,     // evaluation interval
            0.7,         // min confidence
            2.0,         // max adjustment factor
            10,          // min capacity
            100000       // max capacity
        );
    }

    @Test
    void testRecordTrafficEvent_WhenEnabled() {
        // Act
        engine.recordTrafficEvent("test:key", 1, true);

        // Assert
        verify(userMetricsModeler).recordRequest("test:key", 1, true);
    }

    @Test
    void testRecordTrafficEvent_WhenDisabled() {
        // Create a disabled engine
        AdaptiveRateLimitEngine disabledEngine = new AdaptiveRateLimitEngine(
            metricsCollector,
            userMetricsModeler,
            adaptiveModel,
            configurationResolver,
            rateLimiterService,
            false,       // disabled
            300000L,
            0.7,
            2.0,
            10,
            100000
        );

        // Act
        disabledEngine.recordTrafficEvent("test:key", 1, true);

        // Assert
        verifyNoInteractions(userMetricsModeler);
    }

    @Test
    void testGetStatus_NoAdaptedLimits() {
        // Arrange
        RateLimitConfig config = new RateLimitConfig(100, 10);
        when(configurationResolver.getBaseConfig("test:key")).thenReturn(config);

        // Act
        AdaptiveRateLimitEngine.AdaptiveStatusInfo status = engine.getStatus("test:key");

        // Assert
        assertEquals("STATIC", status.mode);
        assertEquals(0.0, status.confidence);
        assertEquals(100, status.originalCapacity);
        assertEquals(10, status.originalRefillRate);
    }

    @Test
    void testEvaluateAdaptations_AppliesDecision() {
        // Arrange
        String key = "test:key";
        engine.recordTrafficEvent(key, 1, true); // Track the key
        
        RateLimitConfig config = new RateLimitConfig(100, 10);
        when(configurationResolver.getBaseConfig(key)).thenReturn(config);
        
        SystemHealth health = SystemHealth.builder().build();
        when(metricsCollector.getCurrentHealth()).thenReturn(health);
        
        UserMetrics metrics = UserMetrics.builder().build();
        when(userMetricsModeler.getUserMetrics(key)).thenReturn(metrics);
        
        AdaptationDecision decision = AdaptationDecision.builder()
            .shouldAdapt(true)
            .recommendedCapacity(150)
            .recommendedRefillRate(15)
            .confidence(0.85)
            .reasoning(Map.of("decision", "System stable"))
            .build();
        when(adaptiveModel.predict(any(), any(), eq(100), eq(10))).thenReturn(decision);

        // Act
        engine.evaluateAdaptations();

        // Assert
        AdaptiveRateLimitEngine.AdaptiveStatusInfo status = engine.getStatus(key);
        assertEquals("ADAPTIVE", status.mode);
        assertEquals(150, status.currentCapacity);
        assertEquals(15, status.currentRefillRate);
        assertEquals(0.85, status.confidence);
    }

    @Test
    void testSetOverride() {
        // Arrange
        RateLimitConfig config = new RateLimitConfig(100, 10);
        when(configurationResolver.getBaseConfig("test:key")).thenReturn(config);

        // Act
        AdaptiveRateLimitEngine.AdaptationOverride override = 
            new AdaptiveRateLimitEngine.AdaptationOverride(500, 50, "Test override");
        engine.setOverride("test:key", override);

        // Assert
        AdaptiveRateLimitEngine.AdaptiveStatusInfo status = engine.getStatus("test:key");
        assertEquals("OVERRIDE", status.mode);
        assertEquals(500, status.currentCapacity);
    }

    @Test
    void testRemoveOverride() {
        // Arrange
        AdaptiveRateLimitEngine.AdaptationOverride override = 
            new AdaptiveRateLimitEngine.AdaptationOverride(500, 50, "Test override");
        engine.setOverride("test:key", override);

        // Act
        engine.removeOverride("test:key");

        // Assert
        RateLimitConfig config = new RateLimitConfig(100, 10);
        when(configurationResolver.getBaseConfig("test:key")).thenReturn(config);
        AdaptiveRateLimitEngine.AdaptiveStatusInfo status = engine.getStatus("test:key");
        assertEquals("STATIC", status.mode);
    }

    @Test
    void testEvaluateAdaptations_WhenDisabled() {
        // Create a disabled engine
        AdaptiveRateLimitEngine disabledEngine = new AdaptiveRateLimitEngine(
            metricsCollector,
            userMetricsModeler,
            adaptiveModel,
            configurationResolver,
            rateLimiterService,
            false,       // disabled
            300000L,
            0.7,
            2.0,
            10,
            100000
        );

        // Act
        disabledEngine.evaluateAdaptations();

        // Assert
        verifyNoInteractions(metricsCollector, userMetricsModeler, adaptiveModel);
    }

    @Test
    void testAddAdaptiveTarget() {
        // Arrange
        String target = "user:123";
        when(configurationResolver.getBaseConfig(target)).thenReturn(null);

        // Act
        engine.addAdaptiveTarget(target, false);

        // Assert
        assertTrue(engine.getAdaptiveTargets().containsKey(target));
        verify(configurationResolver).updateKeyConfig(eq(target), any(RateLimitConfig.class));
        verify(rateLimiterService).clearBuckets();
    }

    @Test
    void testRemoveAdaptiveTarget() {
        // Arrange
        String target = "user:123";
        // Reset mock to clear interactions from setup or previous calls
        reset(rateLimiterService);
        
        engine.addAdaptiveTarget(target, false);

        // Act
        engine.removeAdaptiveTarget(target);

        // Assert
        assertFalse(engine.getAdaptiveTargets().containsKey(target));
        verify(rateLimiterService, times(2)).clearBuckets(); // once for add, once for remove
    }

    @Test
    void testAdaptedLimitsClass() {
        java.time.Instant now = java.time.Instant.now();
        java.util.Map<String, String> reasoning = new java.util.HashMap<>();
        reasoning.put("test", "value");

        AdaptiveRateLimitEngine.AdaptedLimits limits = new AdaptiveRateLimitEngine.AdaptedLimits(
            100, 10, 150, 15, now, reasoning, 0.85
        );

        assertEquals(100, limits.originalCapacity);
        assertEquals(10, limits.originalRefillRate);
        assertEquals(150, limits.adaptedCapacity);
        assertEquals(15, limits.adaptedRefillRate);
        assertEquals(now, limits.timestamp);
        assertEquals(reasoning, limits.reasoning);
        assertEquals(0.85, limits.confidence);
    }
}