package dev.bnacar.distributedratelimiter.adaptive;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AimdPolicyEngineTest {

    private AimdPolicyEngine policyEngine;

    @BeforeEach
    void setUp() {
        policyEngine = new AimdPolicyEngine();
    }

    @Test
    void testCriticalStress_TriggersMultiplicativeDecrease_50Percent() {
        // CPU > 80%
        SystemHealth health = SystemHealth.builder()
            .cpuUtilization(0.85)
            .build();
        UserMetrics metrics = UserMetrics.builder().build();

        AdaptationDecision decision = policyEngine.determineAdaptation(health, metrics, 100, 20);

        assertTrue(decision.shouldAdapt());
        assertEquals(50, decision.getRecommendedCapacity());
        assertEquals(10, decision.getRecommendedRefillRate());
        assertTrue(decision.getReasoning().get("decision").contains("CRITICAL"));
    }

    @Test
    void testModerateStress_TriggersMultiplicativeDecrease_20Percent() {
        // CPU > 60% but < 80%
        SystemHealth health = SystemHealth.builder()
            .cpuUtilization(0.65)
            .build();
        UserMetrics metrics = UserMetrics.builder().build();

        AdaptationDecision decision = policyEngine.determineAdaptation(health, metrics, 100, 20);

        assertTrue(decision.shouldAdapt());
        assertEquals(80, decision.getRecommendedCapacity());
        assertEquals(16, decision.getRecommendedRefillRate());
        assertTrue(decision.getReasoning().get("decision").contains("Moderate"));
    }

    @Test
    void testHealthySystem_TriggersAdditiveIncrease() {
        // CPU < 40% and low error rate
        SystemHealth health = SystemHealth.builder()
            .cpuUtilization(0.20)
            .errorRate(0.0)
            .build();
        UserMetrics metrics = UserMetrics.builder()
            .denialRate(0.10) // User is hitting limits
            .build();

        AdaptationDecision decision = policyEngine.determineAdaptation(health, metrics, 100, 20);

        assertTrue(decision.shouldAdapt());
        assertEquals(110, decision.getRecommendedCapacity()); // 100 + 10
        assertEquals(22, decision.getRecommendedRefillRate()); // 20 + 2
        assertTrue(decision.getReasoning().get("decision").contains("HEALTHY"));
    }

    @Test
    void testStableSystem_NoChange() {
        // CPU in middle range (50%)
        SystemHealth health = SystemHealth.builder()
            .cpuUtilization(0.50)
            .build();
        UserMetrics metrics = UserMetrics.builder().build();

        AdaptationDecision decision = policyEngine.determineAdaptation(health, metrics, 100, 20);

        assertFalse(decision.shouldAdapt());
        assertEquals(100, decision.getRecommendedCapacity());
        assertTrue(decision.getReasoning().get("decision").contains("STABLE"));
    }
}
