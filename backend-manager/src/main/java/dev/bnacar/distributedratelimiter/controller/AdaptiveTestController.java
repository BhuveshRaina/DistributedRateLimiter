package dev.bnacar.distributedratelimiter.controller;

import dev.bnacar.distributedratelimiter.adaptive.SystemHealth;
import dev.bnacar.distributedratelimiter.adaptive.SystemMetricsCollector;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/admin/adaptive-test")
@Tag(name = "adaptive-test-controller", description = "Utilities for testing the Adaptive AIMD engine")
@CrossOrigin(origins = "*")
public class AdaptiveTestController {

    private final SystemMetricsCollector metricsCollector;

    @Autowired
    public AdaptiveTestController(SystemMetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }

    @PostMapping("/mock-health")
    @Operation(summary = "Force mock system health metrics", 
               description = "Allows manual testing of AIMD by injecting fake CPU/Latency/Error values.")
    public ResponseEntity<String> setMockHealth(@RequestBody Map<String, Object> params) {
        double cpu = ((Number) params.getOrDefault("cpu", 0.0)).doubleValue();
        double latency = ((Number) params.getOrDefault("latency", 0.0)).doubleValue();
        double errorRate = ((Number) params.getOrDefault("errorRate", 0.0)).doubleValue();

        SystemHealth health = SystemHealth.builder()
                .cpuUtilization(cpu)
                .responseTimeP95(latency)
                .errorRate(errorRate)
                .redisHealthy(true)
                .downstreamServicesHealthy(true)
                .build();

        metricsCollector.setMockHealth(health);
        return ResponseEntity.ok("Mock health active: CPU=" + (cpu * 100) + "%, Latency=" + latency + "ms");
    }

    @DeleteMapping("/mock-health")
    @Operation(summary = "Clear mock health and return to real metrics")
    public ResponseEntity<String> clearMockHealth() {
        metricsCollector.clearMockHealth();
        return ResponseEntity.ok("Returned to real hardware metrics.");
    }
}
