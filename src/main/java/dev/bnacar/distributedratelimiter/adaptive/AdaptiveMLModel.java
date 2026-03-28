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
     * Make rule-based decision focusing on System Health and User Metrics.
     */
    private DecisionOutput makeRuleBasedDecision(SystemHealth health, 
                                                 UserMetrics userMetrics,
                                                 int currentCapacity,
                                                 int currentRefillRate) {
        
        DecisionOutput output = new DecisionOutput();
        output.capacity = currentCapacity;
        output.refillRate = currentRefillRate;
        output.confidence = 0.7;
        output.shouldAdapt = false;
        
        // Rule 1: System under heavy stress - aggressive reduction
        if (health.getCpuUtilization() > 0.8 || health.getResponseTimeP95() > 1000) {
            output.shouldAdapt = true;
            output.capacity = (int) (currentCapacity * 0.5);
            output.refillRate = (int) (currentRefillRate * 0.5);
            output.confidence = 0.95;
            output.reason = "System under heavy stress (CPU > 80% or P95 > 1s)";
            return output;
        }

        // Rule 2: High denial rate for user - likely hitting limits too hard, reduce further to protect system
        if (userMetrics.getDenialRate() > 0.3) {
            output.shouldAdapt = true;
            output.capacity = (int) (currentCapacity * 0.7);
            output.refillRate = (int) (currentRefillRate * 0.7);
            output.confidence = 0.85;
            output.reason = "High user denial rate (> 30%)";
            return output;
        }
        
        // Rule 3: System under moderate stress or user rate very high - moderate reduction
        if (health.getCpuUtilization() > 0.6 || userMetrics.getCurrentRequestRate() > currentRefillRate * 2) {
            output.shouldAdapt = true;
            output.capacity = (int) (currentCapacity * 0.8);
            output.refillRate = (int) (currentRefillRate * 0.8);
            output.confidence = 0.8;
            output.reason = "Moderate system stress or excessive user request rate";
            return output;
        }
        
        // Rule 4: System has high capacity and user is stable - increase limits
        if (health.getCpuUtilization() < 0.3 && health.getErrorRate() < 0.001 && userMetrics.getDenialRate() < 0.05) {
            output.shouldAdapt = true;
            output.capacity = (int) (currentCapacity * 1.2);
            output.refillRate = (int) (currentRefillRate * 1.2);
            output.confidence = 0.8;
            output.reason = "System has high capacity and low user denial rate";
            return output;
        }
        
        // No adaptation needed
        output.reason = "System and user metrics stable, no adaptation needed";
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