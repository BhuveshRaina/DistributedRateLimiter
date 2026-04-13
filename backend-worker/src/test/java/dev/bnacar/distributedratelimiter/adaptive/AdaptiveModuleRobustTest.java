package dev.bnacar.distributedratelimiter.adaptive;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Robust Scenario-based tests for the Adaptive Rate Limiting Module.
 * These tests verify the complex interactions between Multi-Factor Stress, 
 * Z-Score statistical fairness, and AIMD clamping based on the Formal Spec.
 */
class AdaptiveModuleRobustTest {

    private AimdPolicyEngine policyEngine;

    @BeforeEach
    void setUp() {
        policyEngine = new AimdPolicyEngine();
    }

    @Test
    @DisplayName("Scenario: High Latency Stress + Noisy Neighbor Isolation")
    void testNoisyNeighborIsolation_UnderLatencyStress() {
        // System is slow: P95 = 1600ms (80% stress, 10% above target 0.70)
        SystemHealth health = SystemHealth.builder()
            .cpuUtilization(0.30)
            .responseTimeP95(1600.0)
            .build();

        // User A: Normal user (RPS matches mean, Z=0)
        UserMetrics userA = UserMetrics.builder().zScore(0.0).currentRequestRate(10.0).build();
        
        // User B: Noisy Neighbor (RPS far above mean, Z=4.0)
        UserMetrics userB = UserMetrics.builder().zScore(4.0).currentRequestRate(100.0).build();

        // Calculate decisions (N=5 users to enable Z-score logic)
        AdaptationDecision decisionA = policyEngine.determineAdaptation(health, userA, 100, 20, 100, 20, 5);
        AdaptationDecision decisionB = policyEngine.determineAdaptation(health, userB, 100, 20, 100, 20, 5);

        // Analysis for User A (Normal):
        // U = 0.80, E = 0.10. Intensity = 0. sTraffic = 0.8 > 0.7. Tax = 0.5.
        // Mult = 1 - (0.3 * 0.1 * 0.5) = 0.985
        assertEquals(99, decisionA.getRecommendedCapacity());

        // Analysis for User B (Noisy):
        // U = 0.80, E = 0.10. Intensity = 4.0. Tax = 1 + 4 = 5.0.
        // Mult = 1 - (0.3 * 0.1 * 5.0) = 0.85
        assertEquals(85, decisionB.getRecommendedCapacity());

        // Assert that the noisy neighbor was penalized significantly more
        assertTrue(decisionB.getRecommendedCapacity() < decisionA.getRecommendedCapacity());
    }

    @Test
    @DisplayName("Scenario: Multiple Bottlenecks (CPU + Error Rate)")
    void testMultipleBottlenecks_TakesMaxStress() {
        // CPU is high (80%), but Error Rate is even higher (18% / 20% SLO = 90% stress)
        SystemHealth health = SystemHealth.builder()
            .cpuUtilization(0.80)
            .errorRate(0.18) 
            .build();

        // N=5 users
        UserMetrics metrics = UserMetrics.builder().zScore(1.0).build();

        AdaptationDecision decision = policyEngine.determineAdaptation(health, metrics, 100, 20, 100, 20, 5);

        // Global Stress should be 90% (from error rate)
        // E = 0.90 - 0.70 = 0.20
        // Intensity = 1.0. Tax = 1 + 1 = 2.0.
        // Mult = 1 - (0.3 * 0.20 * 2.0) = 1 - 0.12 = 0.88
        // 100 * 0.88 = 88
        assertEquals(88, decision.getRecommendedCapacity());
        assertTrue(decision.getReasoning().get("decision").contains("MD applied"));
    }

    @Test
    @DisplayName("Scenario: Convergence to Equilibrium")
    void testEquilibrium_NoAdaptationNeeded() {
        // System at exactly 70% Target Stress
        SystemHealth health = SystemHealth.builder().cpuUtilization(0.70).build();
        UserMetrics metrics = UserMetrics.builder().zScore(1.0).build();

        AdaptationDecision decision = policyEngine.determineAdaptation(health, metrics, 100, 20, 100, 20, 5);

        assertFalse(decision.shouldAdapt());
        assertEquals(100, decision.getRecommendedCapacity());
    }

    @Test
    @DisplayName("Scenario: Minimum Clamping Protection")
    void testClamping_NeverDropsBelowMinimum() {
        // System at 90% Stress, but user already at minimum
        SystemHealth health = SystemHealth.builder().cpuUtilization(0.90).build();
        UserMetrics metrics = UserMetrics.builder().zScore(10.0).build(); // Huge spammer

        // User already at 11 tokens (near L_MIN = 10)
        AdaptationDecision decision = policyEngine.determineAdaptation(health, metrics, 11, 2, 100, 20, 5);

        // Even with huge error and Z-score, it shouldn't drop below 10
        assertEquals(10, decision.getRecommendedCapacity());
    }

    @Test
    @DisplayName("Scenario: Panic Mode (Any factor > 95%)")
    void testMultiFactorPanicMode() {
        // CPU is extremely high (99%)
        SystemHealth health = SystemHealth.builder()
            .cpuUtilization(0.99)
            .build();
        UserMetrics metrics = UserMetrics.builder().zScore(1.0).build();

        AdaptationDecision decision = policyEngine.determineAdaptation(health, metrics, 100, 20, 100, 20, 5);

        // Instant 50% geometric cut
        assertEquals(50, decision.getRecommendedCapacity());
        assertEquals(10, decision.getRecommendedRefillRate());
        assertTrue(decision.getReasoning().get("decision").contains("PANIC"));
    }

    @Test
    @DisplayName("Scenario: Zero Activity AI")
    void testZeroActivity_NeutralZScore() {
        // System healthy (20% CPU)
        SystemHealth health = SystemHealth.builder().cpuUtilization(0.20).build();
        UserMetrics metrics = UserMetrics.builder().zScore(0.0).currentRequestRate(0.0).build();

        // Current capacity is 100, base is 100.
        // E = 0.20 - 0.70 = -0.50. Growth = 5 * 0.5 = 2.5 -> round to 3.
        AdaptationDecision decision = policyEngine.determineAdaptation(health, metrics, 100, 20, 100, 20, 5);

        assertEquals(103, decision.getRecommendedCapacity());
    }

    @Test
    @DisplayName("Scenario: Fast Recovery (Reset Rule)")
    void testFastRecovery() {
        // System perfectly healthy (20% CPU)
        SystemHealth health = SystemHealth.builder().cpuUtilization(0.20).build();
        UserMetrics metrics = UserMetrics.builder().zScore(0.0).currentRequestRate(5.0).build();

        // Throttled to 50, but Base is 100
        AdaptationDecision decision = policyEngine.determineAdaptation(health, metrics, 50, 10, 100, 20, 5);

        // Should jump back to 100 instantly
        assertEquals(100, decision.getRecommendedCapacity());
        assertTrue(decision.getReasoning().get("decision").contains("RESET"));
    }
}