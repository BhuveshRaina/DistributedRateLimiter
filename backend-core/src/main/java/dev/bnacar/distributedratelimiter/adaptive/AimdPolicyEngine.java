package dev.bnacar.distributedratelimiter.adaptive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * FORMAL SYSTEM DESIGN: DISTRIBUTED MULTI-FACTOR ADAPTIVE RATE LIMITER
 * 
 * Implements a mathematically driven control system with:
 * 1. Multi-Factor Stress Score (CPU, RAM, Latency, Error Rate)
 * 2. Proportional Control (Error E = Stress - 0.75 Target)
 * 3. Statistical Fairness (Z-Score normalization)
 * 4. AIMD Logic (Scaled Additive Increase / Multiplicative Decrease)
 */
@Component
public class AimdPolicyEngine {
    
    private static final Logger logger = LoggerFactory.getLogger(AimdPolicyEngine.class);
    
    // Control Constants
    private static final double TARGET_STRESS = 0.75;
    private static final double PANIC_THRESHOLD = 0.95;
    private static final double ALPHA_GROWTH_FACTOR = 10.0;
    private static final double BETA_PENALTY_FACTOR = 1.5;
    
    // SLO Normalization Constants
    private static final double LATENCY_SLO_MS = 1000.0; // 1s P95 latency = 100% stress
    private static final double ERROR_RATE_SLO = 0.20;   // 20% error rate = 100% stress
    
    // Clamping Constants
    private static final int MIN_CAPACITY = 5;
    private static final int MIN_REFILL = 2;

    /**
     * Determine adaptation decision using Multi-Factor Stress and Z-Score Fairness.
     */
    public AdaptationDecision determineAdaptation(SystemHealth health,
                                     UserMetrics userMetrics,
                                     int currentCapacity,
                                     int currentRefillRate) {
        
        // --- PHASE 1: CALCULATE MULTI-FACTOR STRESS SCORE ---
        double hardwareStress = Math.max(health.getCpuUtilization(), health.getMemoryUtilization());
        double latencyStress = Math.min(1.0, health.getResponseTimeP95() / LATENCY_SLO_MS);
        double errorStress = Math.min(1.0, health.getErrorRate() / ERROR_RATE_SLO);
        
        // Global Stress is the maximum of all normalized factors
        double currentStress = Math.max(hardwareStress, Math.max(latencyStress, errorStress));
        double error = currentStress - TARGET_STRESS;
        
        DecisionOutput output = new DecisionOutput();
        output.capacity = currentCapacity;
        output.refillRate = currentRefillRate;
        output.confidence = 1.0;
        output.shouldAdapt = true;

        // --- PHASE 2: THE CIRCUIT BREAKER (PANIC MODE) ---
        if (currentStress >= PANIC_THRESHOLD) {
            output.capacity = MIN_CAPACITY;
            output.refillRate = MIN_REFILL;
            output.reason = String.format("CRITICAL: System Stress at %.0f%% (HW:%.0f%%, Lat:%.0f%%, Err:%.0f%%). Circuit breaker triggered.", 
                            currentStress * 100, hardwareStress * 100, latencyStress * 100, errorStress * 100);
            return buildDecision(output, currentStress, health, userMetrics);
        }

        // --- PHASE 3: MULTIPLICATIVE DECREASE (SYSTEM STRESSED) ---
        if (error > 0) {
            // Formula: Multiplier = max(0.1, 1 - (Beta * E * max(1, Z_i)))
            double intensity = Math.max(1.0, userMetrics.getZScore());
            double multiplier = Math.max(0.1, 1.0 - (BETA_PENALTY_FACTOR * error * intensity));
            
            output.capacity = Math.max(MIN_CAPACITY, (int) Math.round(currentCapacity * multiplier));
            output.refillRate = Math.max(MIN_REFILL, (int) Math.round(currentRefillRate * multiplier));
            
            String role = userMetrics.getZScore() > 1.0 ? "NOISY NEIGHBOR" : "NORMAL USER";
            output.reason = String.format("STRESSED: System over target by %.1f%%. Multiplicative Decrease applied to %s (Z=%.2f, Multiplier=%.2f)", 
                            error * 100, role, userMetrics.getZScore(), multiplier);
        } 
        
        // --- PHASE 4: ADDITIVE INCREASE (SYSTEM HEALTHY) ---
        else if (error < 0) {
            // Formula: Growth_Step = Alpha * |E|
            double growthStep = ALPHA_GROWTH_FACTOR * Math.abs(error);
            
            output.capacity = currentCapacity + (int) Math.round(growthStep);
            output.refillRate = currentRefillRate + (int) Math.round(growthStep / 5.0);
            
            output.reason = String.format("HEALTHY: System under target by %.1f%%. Additive Increase applied (Growth=+%.1f)", 
                            Math.abs(error) * 100, growthStep);
        }
        
        // --- PHASE 5: HOLD STATE (EQUILIBRIUM) ---
        else {
            output.shouldAdapt = false;
            output.reason = "EQUILIBRIUM: System exactly at 75% target. Holding current limits.";
        }

        return buildDecision(output, currentStress, health, userMetrics);
    }

    private AdaptationDecision buildDecision(DecisionOutput output, double stress, SystemHealth health, UserMetrics userMetrics) {
        Map<String, String> reasoning = new HashMap<>();
        reasoning.put("decision", output.reason);
        reasoning.put("systemMetrics", String.format("Global Stress: %.1f%% (CPU:%.0f%%, RAM:%.0f%%, P95:%.0fms, Err:%.1f%%)", 
                      stress * 100, health.getCpuUtilization() * 100, health.getMemoryUtilization() * 100,
                      health.getResponseTimeP95(), health.getErrorRate() * 100));
        reasoning.put("userMetrics", String.format("RPS: %.2f, Z-Score: %.2f", 
                      userMetrics.getCurrentRequestRate(), userMetrics.getZScore()));
        
        return AdaptationDecision.builder()
            .shouldAdapt(output.shouldAdapt)
            .recommendedCapacity(output.capacity)
            .recommendedRefillRate(output.refillRate)
            .confidence(output.confidence)
            .reasoning(reasoning)
            .build();
    }
    
    private static class DecisionOutput {
        boolean shouldAdapt;
        int capacity;
        int refillRate;
        double confidence;
        String reason;
    }
}