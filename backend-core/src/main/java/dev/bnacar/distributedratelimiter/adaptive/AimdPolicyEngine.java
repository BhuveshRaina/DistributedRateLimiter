package dev.bnacar.distributedratelimiter.adaptive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Enhanced AimdPolicyEngine - Final alignment with Formal Spec and Log Transparency.
 */
@Component
public class AimdPolicyEngine {
    
    private static final Logger logger = LoggerFactory.getLogger(AimdPolicyEngine.class);
    
    // simulation thresholds
    private static final double U_TARGET = 0.70; // Target 70% System CPU
    private static final double PANIC_THRESHOLD = 0.85; // Panic at 85% System CPU
    private static final double ALPHA = 10.0; 
    private static final double BETA = 1.0;  
    
    private static final double SLO_LATENCY_MS = 100.0; 
    private static final double SLO_ERROR_RATE = 0.05;
    
    private static final int L_MIN = 10;
    private static final int ABSOLUTE_MAX_CAPACITY = 5000;
    private static final int ABSOLUTE_MAX_REFILL_RATE = 1000;

    public AdaptationDecision determineAdaptation(SystemHealth health,
                                     UserMetrics userMetrics,
                                     int lOld,
                                     int rOld,
                                     int lBase,
                                     int rBase,
                                     int activeUserCount) {
        
        // --- PHASE 1: TELEMETRY NORMALIZATION ---
        double sCpu = health.getCpuUtilization();
        double sLat = Math.min(1.0, health.getResponseTimeP95() / SLO_LATENCY_MS);
        double sErr = Math.min(1.0, health.getErrorRate() / SLO_ERROR_RATE);
        
        double sTraffic = Math.max(sLat, sErr);
        double uCurrent = Math.max(sCpu, sTraffic);
        double e = uCurrent - U_TARGET;

        // --- PHASE 2: STATISTICAL PROFILING ---
        double zScore = userMetrics.getZScore();
        double intensity = (activeUserCount < 3) ? 0.0 : Math.max(0.0, zScore);

        DecisionOutput output = new DecisionOutput();
        output.capacity = lOld;
        output.refillRate = rOld;
        output.shouldAdapt = true;

        // --- PHASE 3: THE AIMD CONTROL LAW ---

        // A. Panic Mode (Extreme Stress across all dimensions)
        if (uCurrent >= PANIC_THRESHOLD && sTraffic > 0.9) {
            output.capacity = (int) (lOld * 0.50);
            output.refillRate = (int) (rOld * 0.50);
            output.reason = "PANIC";
        }
        
        // B. Multiplicative Decrease
        else if (e > 0) {
            double tax = 0.0;
            if (intensity > 0.5) {
                tax = 1.0 + intensity; // PUNITIVE: Crushing the villain
            } else if (intensity > 0.0 || sTraffic > 0.7) {
                tax = 0.5; // SHARED SACRIFICE: Light warning for noisy users
            }

            if (tax > 0) {
                double m = 1.0 - (BETA * e * tax);
                double mSafe = Math.max(0.80, m); 
                // Use floor to ensure we actually decrease even for small integers (e.g., 2 -> 1)
                output.capacity = (int) Math.floor(lOld * mSafe);
                output.refillRate = (int) Math.floor(rOld * mSafe);
                output.reason = "DECREASE";
            } else {
                output.shouldAdapt = false;
                output.reason = "HOLD";
            }
        } 
        
        // C. Slow Start (Exponential Recovery to Base)
        else if (uCurrent < U_TARGET && lOld < lBase) {
            // Ramping up exponentially (25% growth) until we hit the SLA/Base
            double m = 1.25; 
            output.capacity = Math.min(lBase, (int) Math.ceil(lOld * m));
            output.refillRate = Math.min(rBase, (int) Math.ceil(rOld * m));
            output.reason = "RECOVERING";
        }
        
        // D. Additive Increase (Scaling up beyond base)
        else if (e < 0 && uCurrent < U_TARGET) {
            double growth = ALPHA * Math.abs(e);
            double growthSafe = Math.min(growth, lOld * 0.10); 
            // Use ceil to ensure we actually increase even for small increments (e.g., 2 -> 3)
            output.capacity = lOld + (int) Math.ceil(growthSafe);
            output.refillRate = rOld + (int) Math.ceil(growthSafe / 5.0);
            output.reason = "INCREASE";
        }
        
        // E. Equilibrium
        else {
            output.shouldAdapt = false;
            output.reason = "EQUILIBRIUM";
        }

        // --- PHASE 4: FINAL SAFEGUARDS (CEILING & FLOOR) ---
        int lCeiling = Math.min(ABSOLUTE_MAX_CAPACITY, (int) (lBase * 2.0));
        int rCeiling = Math.min(ABSOLUTE_MAX_REFILL_RATE, (int) (rBase * 2.0));
        
        int finalCap = Math.min(lCeiling, Math.max(L_MIN, output.capacity));
        int finalRefill = Math.min(rCeiling, Math.max(2, output.refillRate));

        boolean hardCapped = (finalCap == ABSOLUTE_MAX_CAPACITY || finalRefill == ABSOLUTE_MAX_REFILL_RATE) 
                           && (output.capacity > finalCap || output.refillRate > finalRefill);

        // LOG FINAL DECISION
        if (output.shouldAdapt && (finalCap != lOld || finalRefill != rOld)) {
            String suffix = hardCapped ? " [HARD CAPPED]" : "";
            logger.info("[TRANSITION] -> {}: {}/{} -> {}/{} (U={}%, Z={}){}", 
                output.reason, lOld, rOld, finalCap, finalRefill, 
                Math.round(uCurrent * 100), String.format("%.2f", zScore), suffix);
        }

        output.capacity = finalCap;
        output.refillRate = finalRefill;

        return buildDecision(output, uCurrent, health, userMetrics, zScore);
    }

    private AdaptationDecision buildDecision(DecisionOutput output, double u, SystemHealth h, UserMetrics m, double z) {
        Map<String, String> reasoning = new HashMap<>();
        reasoning.put("decision", output.reason);
        reasoning.put("telemetry", String.format("U=%d%%, CPU=%d%%, Lat=%.2fms, Err=%d%%", 
            Math.round(u * 100), Math.round(h.getCpuUtilization() * 100), 
            h.getResponseTimeP95(), Math.round(h.getErrorRate() * 100)));
        reasoning.put("profile", String.format("RPS=%.1f, Z=%.2f", m.getCurrentRequestRate(), z));
        
        return AdaptationDecision.builder()
            .shouldAdapt(output.shouldAdapt)
            .recommendedCapacity(output.capacity)
            .recommendedRefillRate(output.refillRate)
            .reasoning(reasoning)
            .build();
    }
    
    private static class DecisionOutput {
        boolean shouldAdapt;
        int capacity;
        int refillRate;
        String reason;
    }
}