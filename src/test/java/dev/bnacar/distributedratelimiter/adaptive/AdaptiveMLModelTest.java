package dev.bnacar.distributedratelimiter.adaptive;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AdaptiveMLModelTest {

    private AdaptiveMLModel model;

    @BeforeEach
    void setUp() {
        model = new AdaptiveMLModel();
    }

    @Test
    void testPredict_SystemUnderHeavyStress() {
        // Arrange
        SystemHealth health = SystemHealth.builder()
            .cpuUtilization(0.85)  // Above 80% threshold
            .memoryUtilization(0.5)
            .responseTimeP95(500)
            .errorRate(0.01)
            .redisHealthy(true)
            .build();
        UserMetrics userMetrics = UserMetrics.builder()
            .currentRequestRate(10.0)
            .denialRate(0.05)
            .build();

        // Act
        AdaptationDecision decision = model.predict(health, userMetrics, 1000, 100);

        // Assert
        assertTrue(decision.shouldAdapt());
        assertEquals(500, decision.getRecommendedCapacity());  // 50% reduction
        assertEquals(50, decision.getRecommendedRefillRate());
        assertEquals(0.95, decision.getConfidence());
        assertTrue(decision.getReasoning().get("decision").contains("heavy stress"));
    }

    @Test
    void testPredict_HighUserDenialRate() {
        // Arrange
        SystemHealth health = SystemHealth.builder()
            .cpuUtilization(0.3)
            .memoryUtilization(0.3)
            .responseTimeP95(100)
            .errorRate(0.001)
            .redisHealthy(true)
            .build();
        UserMetrics userMetrics = UserMetrics.builder()
            .currentRequestRate(50.0)
            .denialRate(0.35)  // Above 30% threshold
            .build();

        // Act
        AdaptationDecision decision = model.predict(health, userMetrics, 1000, 100);

        // Assert
        assertTrue(decision.shouldAdapt());
        assertEquals(700, decision.getRecommendedCapacity());  // 30% reduction
        assertEquals(0.85, decision.getConfidence());
        assertTrue(decision.getReasoning().get("decision").contains("denial rate"));
    }

    @Test
    void testPredict_ModerateStress() {
        // Arrange
        SystemHealth health = SystemHealth.builder()
            .cpuUtilization(0.65)  // Above 60% threshold
            .memoryUtilization(0.4)
            .responseTimeP95(200)
            .errorRate(0.005)
            .redisHealthy(true)
            .build();
        UserMetrics userMetrics = UserMetrics.builder()
            .currentRequestRate(10.0)
            .denialRate(0.02)
            .build();

        // Act
        AdaptationDecision decision = model.predict(health, userMetrics, 1000, 100);

        // Assert
        assertTrue(decision.shouldAdapt());
        assertEquals(800, decision.getRecommendedCapacity());  // 20% reduction
        assertEquals(0.8, decision.getConfidence());
    }

    @Test
    void testPredict_HighCapacity() {
        // Arrange
        SystemHealth health = SystemHealth.builder()
            .cpuUtilization(0.2)  // Low CPU
            .memoryUtilization(0.2)
            .responseTimeP95(50)
            .errorRate(0.0001)
            .redisHealthy(true)
            .build();
        UserMetrics userMetrics = UserMetrics.builder()
            .currentRequestRate(5.0)
            .denialRate(0.01)  // Low denial
            .build();

        // Act
        AdaptationDecision decision = model.predict(health, userMetrics, 1000, 100);

        // Assert
        assertTrue(decision.shouldAdapt());
        assertEquals(1200, decision.getRecommendedCapacity());  // 20% increase
        assertEquals(0.8, decision.getConfidence());
    }

    @Test
    void testPredict_StableNoAdaptation() {
        // Arrange
        SystemHealth health = SystemHealth.builder()
            .cpuUtilization(0.45)
            .memoryUtilization(0.4)
            .responseTimeP95(150)
            .errorRate(0.005)
            .redisHealthy(true)
            .build();
        UserMetrics userMetrics = UserMetrics.builder()
            .currentRequestRate(10.0)
            .denialRate(0.1)
            .build();

        // Act
        AdaptationDecision decision = model.predict(health, userMetrics, 1000, 100);

        // Assert
        assertFalse(decision.shouldAdapt());
        assertTrue(decision.getReasoning().get("decision").contains("no adaptation needed"));
    }
}