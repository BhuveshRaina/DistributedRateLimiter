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
    void testPanicMode_HardwareStress() {
        // CPU at 99%
        SystemHealth health = SystemHealth.builder()
            .cpuUtilization(0.99)
            .build();
        UserMetrics metrics = UserMetrics.builder().zScore(1.0).build();

        // N=5 users
        AdaptationDecision decision = policyEngine.determineAdaptation(health, metrics, 100, 20, 100, 20, 5);

        assertTrue(decision.shouldAdapt());
        // Panic mode applies 0.5 geometric cut
        assertEquals(50, decision.getRecommendedCapacity());
        assertTrue(decision.getReasoning().get("decision").contains("PANIC"));
    }

    @Test
    void testMultiplicativeDecrease_Outlier() {
        // System is stressed: CPU = 80% (10% above target 0.70)
        SystemHealth health = SystemHealth.builder()
            .cpuUtilization(0.80)
            .build();
        
        // User is an outlier: Z=2.0 (Intensity = 2.0 > 0.5)
        UserMetrics metrics = UserMetrics.builder()
            .zScore(2.0)
            .build();

        // E = 0.8 - 0.7 = 0.1
        // Tax = 1 + 2.0 = 3.0
        // Mult = 1 - (0.3 * 0.1 * 3.0) = 1 - 0.09 = 0.91
        // 100 * 0.91 = 91
        AdaptationDecision decision = policyEngine.determineAdaptation(health, metrics, 100, 20, 100, 20, 5);

        assertTrue(decision.shouldAdapt());
        assertEquals(91, decision.getRecommendedCapacity());
        assertTrue(decision.getReasoning().get("decision").contains("MD applied"));
    }

    @Test
    void testMultiplicativeDecrease_SharedSacrifice() {
        // System is stressed due to Traffic: Latency = 1600ms (80% stress)
        SystemHealth health = SystemHealth.builder()
            .responseTimeP95(1600.0)
            .build();
        
        // User is normal: Z=0.0
        UserMetrics metrics = UserMetrics.builder()
            .zScore(0.0)
            .build();

        // E = 0.8 - 0.7 = 0.1
        // sTraffic = 0.8 > 0.7. Tax = 0.5.
        // Mult = 1 - (0.3 * 0.1 * 0.5) = 1 - 0.015 = 0.985
        // 100 * 0.985 = 98.5 -> 99
        AdaptationDecision decision = policyEngine.determineAdaptation(health, metrics, 100, 20, 100, 20, 5);

        assertTrue(decision.shouldAdapt());
        assertEquals(99, decision.getRecommendedCapacity());
    }

    @Test
    void testAdditiveIncrease_HealthySystem() {
        // System is fast (100ms P95 = 0.05 stress)
        // CPU 20% (0.2 stress)
        SystemHealth health = SystemHealth.builder()
            .cpuUtilization(0.20)
            .responseTimeP95(100.0)
            .build();
        UserMetrics metrics = UserMetrics.builder()
            .zScore(1.0)
            .build();

        // U_curr = 0.2
        // E = 0.2 - 0.7 = -0.5
        // Growth = 5 * 0.5 = 2.5 -> 3
        AdaptationDecision decision = policyEngine.determineAdaptation(health, metrics, 100, 20, 100, 20, 5);

        assertTrue(decision.shouldAdapt());
        assertEquals(103, decision.getRecommendedCapacity());
        assertTrue(decision.getReasoning().get("decision").contains("AI applied"));
    }
}