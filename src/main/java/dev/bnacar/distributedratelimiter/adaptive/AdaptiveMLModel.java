package dev.bnacar.distributedratelimiter.adaptive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Adaptive ML Model for rate limit optimization.
 * Simplified version using only System Health and User Metrics.
 */
@Component
public class AdaptiveMLModel {
    
    private static final Logger logger = LoggerFactory.getLogger(AdaptiveMLModel.class);
    
    /**
     * Predict adaptation decision based on system health and user metrics.
     */
    public AdaptationDecision predict(SystemHealth health,
                                     UserMetrics userMetrics,
                                     int currentCapacity,
                                     int currentRefillRate) {
        
        // Generate decision using rule-based logic
        DecisionOutput output = makeRuleBasedDecision(health, userMetrics, currentCapacity, currentRefillRate);
        
        Map<String, String> reasoning = generateReasoning(health, userMetrics, output);
        
        return AdaptationDecision.builder()
            .shouldAdapt(output.shouldAdapt)
            .recommendedCapacity(output.capacity)
            .recommendedRefillRate(output.refillRate)
            .confidence(output.confidence)
            .reasoning(reasoning)
            .build();
    }
    
    /**
     * Make rule-based decision focusing on System Health and User Metrics using AIMD principle.
     * AIMD: Additive Increase / Multiplicative Decrease
     */
    private DecisionOutput makeRuleBasedDecision(SystemHealth health, 
                                                 UserMetrics userMetrics,
                                                 int currentCapacity,
                                                 int currentRefillRate) {
        
        DecisionOutput output = new DecisionOutput();
        output.capacity = currentCapacity;
        output.refillRate = currentRefillRate;
        output.confidence = 1.0; 
        output.shouldAdapt = false;
        
        // --- 1. MULTIPLICATIVE DECREASE (MD) ---
        // Triggered ONLY by System Stress to protect infrastructure.
        
        // CRITICAL STRESS: CPU > 80% or Latency > 1s
        if (health.getCpuUtilization() > 0.8 || health.getResponseTimeP95() > 1000) {
            output.shouldAdapt = true;
            output.capacity = Math.max(10, (int) (currentCapacity * 0.5)); // 50% cut
            output.refillRate = Math.max(2, (int) (currentRefillRate * 0.5));
            output.reason = "CRITICAL: System stress detected (CPU/Latency) - Multiplicative Decrease Applied (-50%)";
            return output;
        }

        // MODERATE STRESS: CPU > 60% 
        if (health.getCpuUtilization() > 0.6) {
            output.shouldAdapt = true;
            output.capacity = Math.max(10, (int) (currentCapacity * 0.8)); // 20% cut
            output.refillRate = Math.max(2, (int) (currentRefillRate * 0.8));
            output.reason = "NOTICE: Moderate system stress detected - Multiplicative Decrease Applied (-20%)";
            return output;
        }
        
        // --- 2. ADDITIVE INCREASE (AI) ---
        // Triggered when System is Healthy to maximize throughput and meet user demand.
        
        // HEALTHY: CPU < 40% and low Error Rate
        if (health.getCpuUtilization() < 0.4 && health.getErrorRate() < 0.01) {
            output.shouldAdapt = true;
            output.capacity = currentCapacity + 10; // Fixed Additive Increase
            output.refillRate = currentRefillRate + 2;
            
            // Context-aware reasoning
            if (userMetrics.getDenialRate() > 0.05) {
                output.reason = "HEALTHY: System has headroom and user is hitting limits - Additive Increase Applied to meet demand";
            } else {
                output.reason = "HEALTHY: System stable - Additive Increase Applied to probe capacity";
            }
            return output;
        }
        
        // --- 3. STABLE ---
        output.reason = "STABLE: System metrics within normal range, maintaining current limits";
        return output;
    }
    
    /**
     * Generate human-readable reasoning.
     */
    private Map<String, String> generateReasoning(SystemHealth health,
                                                  UserMetrics userMetrics,
                                                  DecisionOutput output) {
        Map<String, String> reasoning = new HashMap<>();
        
        reasoning.put("decision", output.reason);
        
        // System metrics
        reasoning.put("systemMetrics", 
            String.format("CPU: %.1f%%, Memory: %.1f%%, P95: %.0fms, Error Rate: %.3f%%",
                         health.getCpuUtilization() * 100,
                         health.getMemoryUtilization() * 100,
                         health.getResponseTimeP95(),
                         health.getErrorRate() * 100));
        
        // User metrics
        reasoning.put("userMetrics",
            String.format("Request Rate: %.2f req/s, Denial Rate: %.1f%%",
                         userMetrics.getCurrentRequestRate(),
                         userMetrics.getDenialRate() * 100));
        
        return reasoning;
    }
    
    /**
     * Decision output holder.
     */
    private static class DecisionOutput {
        boolean shouldAdapt;
        int capacity;
        int refillRate;
        double confidence;
        String reason;
    }
}