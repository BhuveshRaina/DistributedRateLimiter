package dev.bnacar.distributedratelimiter.ratelimit;

/**
 * Standard in-memory Token Bucket implementation.
 */
public class TokenBucket implements RateLimiter {
    
    private final int capacity;
    private final int refillRate;
    private double currentTokens;
    private long lastRefillTime;
    
    public TokenBucket(int capacity, int refillRate) {
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.currentTokens = capacity;
        this.lastRefillTime = System.currentTimeMillis();
    }
    
    @Override
    public synchronized boolean tryConsume(int tokens) {
        return tryConsumeWithResult(tokens).allowed;
    }
    
    @Override
    public synchronized ConsumptionResult tryConsumeWithResult(int tokens) {
        refill();
        
        if (tokens <= 0) {
            return new ConsumptionResult(false, (int) currentTokens, capacity);
        }
        
        if (currentTokens >= tokens) {
            currentTokens -= tokens;
            return new ConsumptionResult(true, (int) currentTokens, capacity);
        }
        
        return new ConsumptionResult(false, (int) currentTokens, capacity);
    }
    
    private void refill() {
        long now = System.currentTimeMillis();
        double timeElapsed = (now - lastRefillTime) / 1000.0;
        double tokensToAdd = timeElapsed * refillRate;
        
        if (tokensToAdd > 0) {
            currentTokens = Math.min(capacity, currentTokens + tokensToAdd);
            lastRefillTime = now;
        }
    }
    
    @Override
    public synchronized int getCurrentTokens() {
        refill();
        return (int) currentTokens;
    }

    @Override
    public synchronized void setCurrentTokens(int tokens) {
        this.currentTokens = Math.min(capacity, Math.max(0, tokens));
        this.lastRefillTime = System.currentTimeMillis();
    }
    
    @Override
    public int getCapacity() {
        return capacity;
    }
    
    @Override
    public int getRefillRate() {
        return refillRate;
    }
    
    @Override
    public long getLastRefillTime() {
        return lastRefillTime;
    }
}