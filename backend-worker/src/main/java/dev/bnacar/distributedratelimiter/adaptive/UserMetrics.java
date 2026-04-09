package dev.bnacar.distributedratelimiter.adaptive;

/**
 * Simplified individual user metrics for adaptive rate limiting decisions.
 * This replaces the complex UserBehavior class.
 */
public class UserMetrics {
    
    // How many requests per second this user is making right now
    private double currentRequestRate; 
    
    // Percentage of requests being denied (HTTP 429). E.g., 0.05 = 5% denied
    private double denialRate; 
    
    private UserMetrics(Builder builder) {
        this.currentRequestRate = builder.currentRequestRate;
        this.denialRate = builder.denialRate;
    }
    
    public double getCurrentRequestRate() {
        return currentRequestRate;
    }
    
    public double getDenialRate() {
        return denialRate;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private double currentRequestRate = 0.0;
        private double denialRate = 0.0;
        
        public Builder currentRequestRate(double currentRequestRate) {
            this.currentRequestRate = currentRequestRate;
            return this;
        }
        
        public Builder denialRate(double denialRate) {
            this.denialRate = denialRate;
            return this;
        }
        
        public UserMetrics build() {
            return new UserMetrics(this);
        }
    }
}
