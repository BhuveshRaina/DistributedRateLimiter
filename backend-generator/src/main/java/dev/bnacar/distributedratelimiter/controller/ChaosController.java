package dev.bnacar.distributedratelimiter.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chaos")
@Tag(name = "chaos-controller", description = "Endpoints for simulating backend degradation")
@CrossOrigin(origins = "*")
public class ChaosController {

    @GetMapping("/work")
    @Operation(summary = "Simulate work with optional delay or failure", 
               description = "Use this to test how AIMD reacts to high P95 latency or error rates.")
    public ResponseEntity<String> doWork(
            @RequestParam(defaultValue = "0") int delayMs,
            @RequestParam(defaultValue = "false") boolean fail) {
        
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (fail) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Simulated backend failure!");
        }

        return ResponseEntity.ok("Work completed in " + delayMs + "ms");
    }
}
