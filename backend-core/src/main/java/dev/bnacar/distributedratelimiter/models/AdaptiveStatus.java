package dev.bnacar.distributedratelimiter.models;

import java.time.Instant;
import java.util.Map;

/**
 * Response model for adaptive rate limiting status
 */
public class AdaptiveStatus {
    
    private String key;
    private String displayName;
    private boolean adaptiveEnabled;
    private CurrentLimits currentLimits;
    private OriginalLimits originalLimits;
    private AdaptiveStatusInfo adaptiveStatus;
    private Instant timestamp;
    
    public AdaptiveStatus() {
        this.timestamp = Instant.now();
    }
    
    public AdaptiveStatus(String key, String displayName, boolean adaptiveEnabled, CurrentLimits currentLimits, OriginalLimits originalLimits, AdaptiveStatusInfo adaptiveStatus) {
        this.key = key;
        this.displayName = displayName;
        this.adaptiveEnabled = adaptiveEnabled;
        this.currentLimits = currentLimits;
        this.originalLimits = originalLimits;
        this.adaptiveStatus = adaptiveStatus;
        this.timestamp = Instant.now();
    }

    public boolean isAdaptiveEnabled() {
        return adaptiveEnabled;
    }

    public void setAdaptiveEnabled(boolean adaptiveEnabled) {
        this.adaptiveEnabled = adaptiveEnabled;
    }
    
    // Getters and Setters
    public String getKey() {
        return key;
    }
    
    public void setKey(String key) {
        this.key = key;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }
    
    public CurrentLimits getCurrentLimits() {
        return currentLimits;
    }
    
    public void setCurrentLimits(CurrentLimits currentLimits) {
        this.currentLimits = currentLimits;
    }

    public OriginalLimits getOriginalLimits() {
        return originalLimits;
    }

    public void setOriginalLimits(OriginalLimits originalLimits) {
        this.originalLimits = originalLimits;
    }
    
    public AdaptiveStatusInfo getAdaptiveStatus() {
        return adaptiveStatus;
    }
    
    public void setAdaptiveStatus(AdaptiveStatusInfo adaptiveStatus) {
        this.adaptiveStatus = adaptiveStatus;
    }
    
    public Instant getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }
    
    public static class CurrentLimits {
        private int capacity;
        private int refillRate;
        
        public CurrentLimits() {}
        
        public CurrentLimits(int capacity, int refillRate) {
            this.capacity = capacity;
            this.refillRate = refillRate;
        }
        
        public int getCapacity() {
            return capacity;
        }
        
        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }
        
        public int getRefillRate() {
            return refillRate;
        }
        
        public void setRefillRate(int refillRate) {
            this.refillRate = refillRate;
        }
    }

    public static class OriginalLimits {
        private int capacity;
        private int refillRate;

        public OriginalLimits() {}

        public OriginalLimits(int capacity, int refillRate) {
            this.capacity = capacity;
            this.refillRate = refillRate;
        }

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public int getRefillRate() {
            return refillRate;
        }

        public void setRefillRate(int refillRate) {
            this.refillRate = refillRate;
        }
    }
    
    public static class AdaptiveStatusInfo {
        private String mode;
        private double confidence;
        private RecommendedLimits recommendedLimits;
        private Map<String, String> reasoning;
        private boolean adaptiveEnabled;
        
        public AdaptiveStatusInfo() {}
        
        public AdaptiveStatusInfo(String mode, double confidence, RecommendedLimits recommendedLimits, Map<String, String> reasoning, boolean adaptiveEnabled) {
            this.mode = mode;
            this.confidence = confidence;
            this.recommendedLimits = recommendedLimits;
            this.reasoning = reasoning;
            this.adaptiveEnabled = adaptiveEnabled;
        }
        
        public String getMode() {
            return mode;
        }
        
        public void setMode(String mode) {
            this.mode = mode;
        }
        
        public double getConfidence() {
            return confidence;
        }
        
        public void setConfidence(double confidence) {
            this.confidence = confidence;
        }
        
        public RecommendedLimits getRecommendedLimits() {
            return recommendedLimits;
        }
        
        public void setRecommendedLimits(RecommendedLimits recommendedLimits) {
            this.recommendedLimits = recommendedLimits;
        }
        
        public Map<String, String> getReasoning() {
            return reasoning;
        }
        
        public void setReasoning(Map<String, String> reasoning) {
            this.reasoning = reasoning;
        }

        public boolean isAdaptiveEnabled() {
            return adaptiveEnabled;
        }

        public void setAdaptiveEnabled(boolean adaptiveEnabled) {
            this.adaptiveEnabled = adaptiveEnabled;
        }
    }
    
    public static class RecommendedLimits {
        private int capacity;
        private int refillRate;
        
        public RecommendedLimits() {}
        
        public RecommendedLimits(int capacity, int refillRate) {
            this.capacity = capacity;
            this.refillRate = refillRate;
        }
        
        public int getCapacity() {
            return capacity;
        }
        
        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }
        
        public int getRefillRate() {
            return refillRate;
        }
        
        public void setRefillRate(int refillRate) {
            this.refillRate = refillRate;
        }
    }
}
