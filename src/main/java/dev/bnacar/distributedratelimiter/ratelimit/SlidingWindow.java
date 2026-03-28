package dev.bnacar.distributedratelimiter.ratelimit;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Standard in-memory Sliding Window Log rate limiting implementation.
 */
public class SlidingWindow implements RateLimiter {
    
    private final int capacity;
    private final int windowMs;
    private final ConcurrentLinkedQueue<Long> requestLog = new ConcurrentLinkedQueue<>();
    
    public SlidingWindow(int capacity, int refillRate) {
        this.capacity = capacity;
        // Interpret refillRate as requests per second, so window is 1 second
        this.windowMs = 1000;
    }
    
    @Override
    public boolean tryConsume(int tokens) {
        return tryConsumeWithResult(tokens).allowed;
    }
    
    @Override
    public synchronized ConsumptionResult tryConsumeWithResult(int tokens) {
        long now = System.currentTimeMillis();
        long windowStart = now - windowMs;
        
        // Remove outdated entries
        while (!requestLog.isEmpty() && requestLog.peek() < windowStart) {
            requestLog.poll();
        }
        
        if (tokens <= 0) {
            return new ConsumptionResult(false, capacity - requestLog.size(), capacity);
        }
        
        if (requestLog.size() + tokens <= capacity) {
            for (int i = 0; i < tokens; i++) {
                requestLog.add(now);
            }
            return new ConsumptionResult(true, capacity - requestLog.size(), capacity);
        }
        
        return new ConsumptionResult(false, capacity - requestLog.size(), capacity);
    }
    
    @Override
    public int getCurrentTokens() {
        long now = System.currentTimeMillis();
        long windowStart = now - windowMs;
        
        // Remove outdated entries (best effort)
        while (!requestLog.isEmpty() && requestLog.peek() < windowStart) {
            requestLog.poll();
        }
        
        return Math.max(0, capacity - requestLog.size());
    }

    @Override
    public void setCurrentTokens(int tokens) {
        requestLog.clear();
        long now = System.currentTimeMillis();
        int toAdd = Math.max(0, capacity - tokens);
        for (int i = 0; i < toAdd; i++) {
            requestLog.add(now);
        }
    }
    
    @Override
    public int getCapacity() {
        return capacity;
    }
    
    @Override
    public int getRefillRate() {
        return capacity; // Simplified for 1s window
    }
    
    @Override
    public long getLastRefillTime() {
        return System.currentTimeMillis();
    }

    public int getCurrentUsage() {
        long now = System.currentTimeMillis();
        long windowStart = now - windowMs;
        while (!requestLog.isEmpty() && requestLog.peek() < windowStart) {
            requestLog.poll();
        }
        return requestLog.size();
    }

    public int getWindowSizeMs() {
        return windowMs;
    }
}