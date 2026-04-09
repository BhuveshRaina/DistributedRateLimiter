package dev.bnacar.distributedratelimiter.ratelimit;

/**
 * Interface for rate limiting algorithms.
 */
public interface RateLimiter {
    
    /**
     * Try to consume the specified number of tokens.
     * 
     * @param tokens Number of tokens to consume
     * @return true if tokens were consumed, false otherwise
     */
    boolean tryConsume(int tokens);
    
    /**
     * Try to consume tokens and return the detailed result.
     */
    ConsumptionResult tryConsumeWithResult(int tokens);
    
    /**
     * Get the current number of available tokens.
     */
    int getCurrentTokens();

    /**
     * Set the current number of available tokens manually.
     */
    void setCurrentTokens(int tokens);
    
    /**
     * Get the maximum capacity of the rate limiter.
     */
    int getCapacity();
    
    /**
     * Get the refill rate (tokens per second).
     */
    int getRefillRate();
    
    /**
     * Get the timestamp of the last refill operation.
     */
    long getLastRefillTime();
    
    /**
     * Result of a token consumption attempt.
     */
    class ConsumptionResult {
        public final boolean allowed;
        public final int remainingTokens;
        public final int capacity;
        
        public ConsumptionResult(boolean allowed, int remainingTokens) {
            this(allowed, remainingTokens, 0);
        }

        public ConsumptionResult(boolean allowed, int remainingTokens, int capacity) {
            this.allowed = allowed;
            this.remainingTokens = remainingTokens;
            this.capacity = capacity;
        }
    }
}