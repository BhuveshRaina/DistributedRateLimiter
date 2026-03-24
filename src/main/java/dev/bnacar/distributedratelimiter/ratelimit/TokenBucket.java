package dev.bnacar.distributedratelimiter.ratelimit;

public class TokenBucket implements RateLimiter {

    private final int capacity;
    private final int refillRate;
    private long lastRefillTime;
    private int currentTokens;


    public TokenBucket(int capacity, int refillRate) {
        this.currentTokens = capacity;
        this.capacity = capacity;
        this.lastRefillTime = System.currentTimeMillis();
        this.refillRate = refillRate;
    }

    public int getCurrentTokens() {
        return currentTokens;
    }

    public int getCapacity() {
        return capacity;
    }

    public long getLastRefillTime() {
        return lastRefillTime;
    }

    public int getRefillRate() {
        return refillRate;
    }

    public synchronized ConsumptionResult tryConsumeWithResult(int tokens) {
        refill();
        if (tokens <= 0 || tokens > currentTokens) {
            return new ConsumptionResult(false, currentTokens);
        }

        currentTokens -= tokens;
        return new ConsumptionResult(true, currentTokens);
    }

    public synchronized boolean tryConsume(int tokens) {
        return tryConsumeWithResult(tokens).allowed;
    }

    private synchronized void refill() {
        long currentTime = System.currentTimeMillis();
        long elapsedMs = currentTime - lastRefillTime;

        if (elapsedMs > 0) {
            // How many tokens were generated in the elapsed time
            double tokensGenerated = (elapsedMs / 1000.0) * refillRate;
            
            if (tokensGenerated >= 1.0) {
                int tokensToAdd = (int) tokensGenerated;
                currentTokens = Math.min(capacity, currentTokens + tokensToAdd);
                
                // Update lastRefillTime by the exact duration used to generate the whole tokens added
                long timeConsumedMs = (long) (tokensToAdd * 1000.0 / refillRate);
                lastRefillTime += timeConsumedMs;
            }
        }
    }
}
