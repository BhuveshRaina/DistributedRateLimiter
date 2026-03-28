package dev.bnacar.distributedratelimiter.ratelimit;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Standard in-memory Fixed Window rate limiting implementation.
 */
public class FixedWindow implements RateLimiter {
    
    private final int capacity;
    private final int refillRate;
    private final int windowDurationMs;
    private final AtomicInteger currentCount = new AtomicInteger(0);
    private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());
    
    public FixedWindow(int capacity, int refillRate) {
        this(capacity, refillRate, 1000); // Default 1-second window
    }

    public FixedWindow(int capacity, int refillRate, int windowDurationMs) {
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.windowDurationMs = windowDurationMs;
    }
    
    @Override
    public boolean tryConsume(int tokens) {
        return tryConsumeWithResult(tokens).allowed;
    }
    
    @Override
    public ConsumptionResult tryConsumeWithResult(int tokens) {
        resetIfNecessary();
        
        if (tokens <= 0) {
            return new ConsumptionResult(false, capacity - currentCount.get(), capacity);
        }
        
        int current = currentCount.addAndGet(tokens);
        if (current <= capacity) {
            return new ConsumptionResult(true, capacity - current, capacity);
        }
        
        // Exceeded capacity, rollback
        currentCount.addAndGet(-tokens);
        return new ConsumptionResult(false, capacity - currentCount.get(), capacity);
    }
    
    private void resetIfNecessary() {
        long now = System.currentTimeMillis();
        long elapsed = now - windowStart.get();
        
        if (elapsed > windowDurationMs) {
            if (windowStart.compareAndSet(windowStart.get(), now)) {
                currentCount.set(0);
            }
        }
    }
    
    @Override
    public int getCurrentTokens() {
        resetIfNecessary();
        return Math.max(0, capacity - currentCount.get());
    }

    @Override
    public void setCurrentTokens(int tokens) {
        this.currentCount.set(Math.max(0, capacity - tokens));
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
        return windowStart.get();
    }

    public int getCurrentUsage() {
        resetIfNecessary();
        return currentCount.get();
    }

    public long getWindowDurationMs() {
        return windowDurationMs;
    }

    public long getWindowTimeRemaining() {
        long now = System.currentTimeMillis();
        long elapsed = now - windowStart.get();
        return Math.max(0, windowDurationMs - elapsed);
    }
}