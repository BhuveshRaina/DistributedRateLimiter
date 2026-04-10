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

    // How many standard deviations this user's RPS is from the mean
    private double zScore;
    
    private UserMetrics(Builder builder) {
        this.currentRequestRate = builder.currentRequestRate;
        this.denialRate = builder.denialRate;
        this.zScore = builder.zScore;
    }
    
    public double getCurrentRequestRate() {
        return currentRequestRate;
    }
    
    public double getDenialRate() {
        return denialRate;
    }

    public double getZScore() {
        return zScore;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private double currentRequestRate = 0.0;
        private double denialRate = 0.0;
        private double zScore = 1.0; // Default to neutral Z-Score
        
        public Builder currentRequestRate(double currentRequestRate) {
            this.currentRequestRate = currentRequestRate;
            return this;
        }
        
        public Builder denialRate(double denialRate) {
            this.denialRate = denialRate;
            return this;
        }

        public Builder zScore(double zScore) {
            this.zScore = zScore;
            return this;
        }
        
        public UserMetrics build() {
            return new UserMetrics(this);
        }
    }
}
