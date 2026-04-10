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
        // CPU at 96%
        SystemHealth health = SystemHealth.builder()
            .cpuUtilization(0.96)
            .build();
        UserMetrics metrics = UserMetrics.builder().zScore(1.0).build();

        AdaptationDecision decision = policyEngine.determineAdaptation(health, metrics, 100, 20);

        assertTrue(decision.shouldAdapt());
        assertEquals(5, decision.getRecommendedCapacity());
        assertTrue(decision.getReasoning().get("decision").contains("System Stress at 96%"));
    }

    @Test
    void testPanicMode_LatencyStress() {
        // Latency at 1000ms (1.0 stress)
        SystemHealth health = SystemHealth.builder()
            .cpuUtilization(0.10)
            .responseTimeP95(1000.0)
            .build();
        UserMetrics metrics = UserMetrics.builder().zScore(1.0).build();

        AdaptationDecision decision = policyEngine.determineAdaptation(health, metrics, 100, 20);

        assertTrue(decision.shouldAdapt());
        assertEquals(5, decision.getRecommendedCapacity());
        assertTrue(decision.getReasoning().get("decision").contains("Lat:100%"));
    }

    @Test
    void testMultiplicativeDecrease_NoisyNeighbor_ErrorRateStress() {
        // CPU low (20%), but Error Rate high (10% which is 0.50 stress score)
        // Wait, 0.50 stress is below 0.75 target, so it would INCREASE.
        // Let's make error rate 17% (0.85 stress score, which is 10% over target 0.75)
        SystemHealth health = SystemHealth.builder()
            .cpuUtilization(0.20)
            .errorRate(0.17) // 17% / 20% = 0.85 stress
            .build();
        UserMetrics metrics = UserMetrics.builder()
            .zScore(3.0)
            .currentRequestRate(50.0)
            .build();

        // Error = 0.85 - 0.75 = 0.10
        // Formula: Multiplier = max(0.1, 1 - (1.5 * 0.1 * 3.0)) = 1 - 0.45 = 0.55
        AdaptationDecision decision = policyEngine.determineAdaptation(health, metrics, 100, 20);

        assertTrue(decision.shouldAdapt());
        assertEquals(55, decision.getRecommendedCapacity());
        assertTrue(decision.getReasoning().get("decision").contains("NOISY NEIGHBOR"));
    }

    @Test
    void testAdditiveIncrease_LowLatencySystem() {
        // System is very fast (100ms P95 = 0.1 stress)
        // CPU 40% (0.4 stress)
        SystemHealth health = SystemHealth.builder()
            .cpuUtilization(0.40)
            .responseTimeP95(100.0)
            .build();
        UserMetrics metrics = UserMetrics.builder()
            .zScore(1.0)
            .build();

        // Max Stress = 0.4
        // Error = 0.4 - 0.75 = -0.35
        // Growth = 10 * |-0.35| = 3.5 -> round to 4
        AdaptationDecision decision = policyEngine.determineAdaptation(health, metrics, 100, 20);

        assertTrue(decision.shouldAdapt());
        assertEquals(104, decision.getRecommendedCapacity()); // 100 + 4
        assertTrue(decision.getReasoning().get("decision").contains("HEALTHY"));
    }
}
