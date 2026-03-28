package dev.bnacar.distributedratelimiter.adaptive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Models simplified user metrics for adaptive rate limiting decisions.
 */
@Component
public class UserMetricsModeler {
    
    private static final Logger logger = LoggerFactory.getLogger(UserMetricsModeler.class);
    
    // Store request events for metrics analysis
    private final Map<String, List<RequestEvent>> requestHistory = new ConcurrentHashMap<>();
    private static final int MAX_EVENTS_PER_KEY = 1000;
    private static final Duration CALCULATION_WINDOW = Duration.ofMinutes(1);
    
    /**
     * Record a request event.
     */
    public void recordRequest(String key, int tokensRequested, boolean allowed) {
        List<RequestEvent> events = requestHistory.computeIfAbsent(key, k -> new ArrayList<>());
        
        synchronized (events) {
            events.add(new RequestEvent(Instant.now(), tokensRequested, allowed));
            
            // Limit history to prevent memory issues
            if (events.size() > MAX_EVENTS_PER_KEY) {
                events.remove(0);
            }
        }
    }
    
    /**
     * Get current user metrics for a key.
     */
    public UserMetrics getUserMetrics(String key) {
        List<RequestEvent> recentRequests = getRecentRequests(key, CALCULATION_WINDOW);
        
        if (recentRequests.isEmpty()) {
            return UserMetrics.builder()
                .currentRequestRate(0.0)
                .denialRate(0.0)
                .build();
        }
        
        double currentRequestRate = calculateRequestRate(recentRequests);
        double denialRate = calculateDenialRate(recentRequests);
        
        return UserMetrics.builder()
            .currentRequestRate(currentRequestRate)
            .denialRate(denialRate)
            .build();
    }
    
    /**
     * Get recent requests within a duration.
     */
    private List<RequestEvent> getRecentRequests(String key, Duration duration) {
        List<RequestEvent> events = requestHistory.get(key);
        if (events == null) {
            return new ArrayList<>();
        }
        
        Instant cutoff = Instant.now().minus(duration);
        List<RequestEvent> recent = new ArrayList<>();
        
        synchronized (events) {
            for (int i = events.size() - 1; i >= 0; i--) {
                RequestEvent event = events.get(i);
                if (event.timestamp.isAfter(cutoff)) {
                    recent.add(event);
                } else {
                    // Since events are ordered by time, we can stop here
                    break;
                }
            }
        }
        
        return recent;
    }
    
    /**
     * Calculate request rate (requests per second).
     */
    private double calculateRequestRate(List<RequestEvent> requests) {
        if (requests.isEmpty()) {
            return 0.0;
        }
        
        long windowSeconds = CALCULATION_WINDOW.toSeconds();
        if (windowSeconds <= 0) windowSeconds = 1;
        
        return (double) requests.size() / windowSeconds;
    }
    
    /**
     * Calculate denial rate (percentage of 429s).
     */
    private double calculateDenialRate(List<RequestEvent> requests) {
        if (requests.isEmpty()) {
            return 0.0;
        }
        
        long deniedCount = requests.stream()
            .filter(r -> !r.allowed)
            .count();
            
        return (double) deniedCount / requests.size();
    }
    
    /**
     * Clear history for a key.
     */
    public void clearHistory(String key) {
        requestHistory.remove(key);
    }
    
    /**
     * Request event for tracking.
     */
    private static class RequestEvent {
        final Instant timestamp;
        final int tokensRequested;
        final boolean allowed;
        
        public RequestEvent(Instant timestamp, int tokensRequested, boolean allowed) {
            this.timestamp = timestamp;
            this.tokensRequested = tokensRequested;
            this.allowed = allowed;
        }
    }
}
