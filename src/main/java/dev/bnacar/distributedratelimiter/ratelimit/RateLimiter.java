package dev.bnacar.distributedratelimiter.ratelimit;

/**
 * Common interface for rate limiting algorithms.
 * Provides a unified API for different rate limiting strategies.
 */
public interface RateLimiter {
    
    /**
     * Result of a consumption attempt.
     */
    class ConsumptionResult {
        public final boolean allowed;
        public final int remainingTokens;
        
        public ConsumptionResult(boolean allowed, int remainingTokens) {
            this.allowed = allowed;
            this.remainingTokens = remainingTokens;
        }
    }
    
    /**
     * Attempts to consume tokens and returns a result with current state.
     * 
     * @param tokens Number of tokens to consume
     * @return ConsumptionResult with allowed status and remaining tokens
     */
    default ConsumptionResult tryConsumeWithResult(int tokens) {
        boolean allowed = tryConsume(tokens);
        return new ConsumptionResult(allowed, getCurrentTokens());
    }
    
    /**
     * Attempts to consume the specified number of tokens.
     * 
     * @param tokens Number of tokens to consume
     * @return true if tokens were successfully consumed, false otherwise
     */
    boolean tryConsume(int tokens);
    
    /**
     * Get the current number of available tokens.
     * For token bucket: returns actual available tokens.
     * For sliding window: returns remaining capacity in current window.
     * 
     * @return Current available tokens
     */
    int getCurrentTokens();
    
    /**
     * Get the maximum capacity of this rate limiter.
     * 
     * @return Maximum capacity
     */
    int getCapacity();
    
    /**
     * Get the refill rate for this rate limiter.
     * 
     * @return Refill rate (tokens per second)
     */
    int getRefillRate();
    
    /**
     * Get the last refill/update time.
     * For token bucket: actual last refill time.
     * For sliding window: current time (for compatibility).
     * 
     * @return Last refill time in milliseconds
     */
    long getLastRefillTime();
}