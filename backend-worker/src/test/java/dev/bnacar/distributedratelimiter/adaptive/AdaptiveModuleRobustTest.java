package dev.bnacar.distributedratelimiter.adaptive;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Robust Scenario-based tests for the Adaptive Rate Limiting Module.
 * These tests verify the complex interactions between Multi-Factor Stress, 
 * Z-Score statistical fairness, and AIMD clamping.
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
        // System is slow: P95 = 850ms (85% stress, 10% above target)
        SystemHealth health = SystemHealth.builder()
            .cpuUtilization(0.30)
            .responseTimeP95(850.0)
            .build();

        // User A: Normal user (RPS matches mean, Z=0)
        UserMetrics userA = UserMetrics.builder().zScore(0.0).currentRequestRate(10.0).build();
        
        // User B: Noisy Neighbor (RPS far above mean, Z=4.0)
        UserMetrics userB = UserMetrics.builder().zScore(4.0).currentRequestRate(100.0).build();

        // Calculate decisions
        AdaptationDecision decisionA = policyEngine.determineAdaptation(health, userA, 100, 20);
        AdaptationDecision decisionB = policyEngine.determineAdaptation(health, userB, 100, 20);

        // Analysis for User A (Normal):
        // Error = 0.10, Intensity = max(1, 0) = 1.0
        // Multiplier = 1 - (1.5 * 0.10 * 1.0) = 0.85 (15% cut)
        assertEquals(85, decisionA.getRecommendedCapacity());

        // Analysis for User B (Noisy):
        // Error = 0.10, Intensity = max(1, 4.0) = 4.0
        // Multiplier = 1 - (1.5 * 0.10 * 4.0) = 1 - 0.60 = 0.40 (60% cut)
        assertEquals(40, decisionB.getRecommendedCapacity());

        // Assert that the noisy neighbor was penalized 4x more than the normal user
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

        UserMetrics metrics = UserMetrics.builder().zScore(1.0).build();

        AdaptationDecision decision = policyEngine.determineAdaptation(health, metrics, 100, 20);

        // Global Stress should be 90% (from error rate), not 80% (from CPU)
        // Error = 0.90 - 0.75 = 0.15
        // Multiplier = 1 - (1.5 * 0.15 * 1.0) = 1 - 0.225 = 0.775 -> round to 0.78
        // 100 * 0.78 = 78
        assertEquals(78, decision.getRecommendedCapacity());
        assertTrue(decision.getReasoning().get("decision").contains("STRESSED"));
    }

    @Test
    @DisplayName("Scenario: Convergence to Equilibrium")
    void testEquilibrium_NoAdaptationNeeded() {
        // System at exactly 75% Target Stress
        SystemHealth health = SystemHealth.builder().cpuUtilization(0.75).build();
        UserMetrics metrics = UserMetrics.builder().zScore(1.0).build();

        AdaptationDecision decision = policyEngine.determineAdaptation(health, metrics, 100, 20);

        assertFalse(decision.shouldAdapt());
        assertEquals(100, decision.getRecommendedCapacity());
    }

    @Test
    @DisplayName("Scenario: Minimum Clamping Protection")
    void testClamping_NeverDropsBelowMinimum() {
        // System at 90% Stress, but user already at minimum
        SystemHealth health = SystemHealth.builder().cpuUtilization(0.90).build();
        UserMetrics metrics = UserMetrics.builder().zScore(10.0).build(); // Huge spammer

        // User already at 6 tokens (near MIN_CAPACITY = 5)
        AdaptationDecision decision = policyEngine.determineAdaptation(health, metrics, 6, 2);

        // Even with huge error and Z-score, it shouldn't drop to 0
        assertEquals(5, decision.getRecommendedCapacity());
        assertEquals(2, decision.getRecommendedRefillRate());
    }

    @Test
    @DisplayName("Scenario: High Memory Stress")
    void testMemoryStress_TriggersDecrease() {
        // CPU is fine (10%), but Memory is at 85% (10% over target)
        SystemHealth health = SystemHealth.builder()
            .cpuUtilization(0.10)
            .memoryUtilization(0.85)
            .build();
        UserMetrics metrics = UserMetrics.builder().zScore(1.0).build();

        AdaptationDecision decision = policyEngine.determineAdaptation(health, metrics, 100, 20);

        // Error = 0.10 -> Multiplier = 0.85
        assertEquals(85, decision.getRecommendedCapacity());
        assertTrue(decision.getReasoning().get("systemMetrics").contains("RAM:85%"));
    }

    @Test
    @DisplayName("Scenario: Multi-Factor Panic Mode (Any factor > 95%)")
    void testMultiFactorPanicMode() {
        // Everything else is fine, but Error Rate is 20% (100% stress)
        SystemHealth health = SystemHealth.builder()
            .cpuUtilization(0.10)
            .memoryUtilization(0.10)
            .responseTimeP95(100.0)
            .errorRate(0.20) // 20% / 20% SLO = 1.0 (Panic!)
            .build();
        UserMetrics metrics = UserMetrics.builder().zScore(1.0).build();

        AdaptationDecision decision = policyEngine.determineAdaptation(health, metrics, 100, 20);

        // Instant reset to minimums
        assertEquals(5, decision.getRecommendedCapacity());
        assertEquals(2, decision.getRecommendedRefillRate());
        assertTrue(decision.getReasoning().get("decision").contains("CRITICAL"));
    }

    @Test
    @DisplayName("Scenario: Zero Activity Fallback")
    void testZeroActivity_NeutralZScore() {
        // System healthy, but no users active
        SystemHealth health = SystemHealth.builder().cpuUtilization(0.50).build();
        // RPS=0 results in StdDev issues in some implementations, but our Z-Score logic should be safe
        UserMetrics metrics = UserMetrics.builder().zScore(0.0).currentRequestRate(0.0).build();

        AdaptationDecision decision = policyEngine.determineAdaptation(health, metrics, 100, 20);

        // Error = -0.25 -> Growth = 10 * 0.25 = 2.5 -> round to 3
        assertEquals(103, decision.getRecommendedCapacity());
    }
}
