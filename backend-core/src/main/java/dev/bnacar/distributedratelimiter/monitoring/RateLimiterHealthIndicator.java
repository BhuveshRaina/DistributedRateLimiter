package dev.bnacar.distributedratelimiter.monitoring;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("rateLimiter")
public class RateLimiterHealthIndicator implements HealthIndicator {
    @Override
    public Health health() {
        return Health.up()
                .withDetail("version", "1.2.0")
                .build();
    }
}
