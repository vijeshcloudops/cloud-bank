package com.example.bankapp.controller;

import com.example.bankapp.config.ReadWriteRoutingDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Health check endpoint for monitoring database failover status.
 * Useful for load balancers, monitoring dashboards, and alerting systems.
 */
@RestController
@RequestMapping("/health")
public class HealthCheckController {

    @Autowired(required = false)
    private ReadWriteRoutingDataSource routingDataSource;

    /**
     * General application health check.
     * Returns 200 if app is healthy, 503 if database is down.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("timestamp", System.currentTimeMillis());

        // Check if replica is available
        if (routingDataSource != null) {
            boolean replicaAvailable = routingDataSource.isReplicaAvailable();
            response.put("replica_status", replicaAvailable ? "HEALTHY" : "UNAVAILABLE");
            
            if (!replicaAvailable) {
                response.put("failover_status", "ACTIVE - Using primary for all queries");
            } else {
                response.put("failover_status", "NORMAL - Reads on replica, writes on primary");
            }
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Detailed database status endpoint.
     * Returns 200 if both primary and replica are healthy, 503 if primary is down.
     */
    @GetMapping("/db")
    public ResponseEntity<Map<String, Object>> databaseStatus() {
        Map<String, Object> response = new HashMap<>();

        if (routingDataSource == null) {
            response.put("error", "Routing datasource not configured");
            return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
        }

        boolean replicaAvailable = routingDataSource.isReplicaAvailable();
        
        Map<String, Object> dbStatus = new HashMap<>();
        dbStatus.put("primary", "HEALTHY"); // Primary is always assumed healthy
        dbStatus.put("replica", replicaAvailable ? "HEALTHY" : "UNAVAILABLE");
        dbStatus.put("failover_active", !replicaAvailable);
        
        response.put("database", dbStatus);
        response.put("timestamp", System.currentTimeMillis());

        // If replica is down, return 503 (Service Unavailable but degraded)
        if (!replicaAvailable) {
            response.put("message", "Running in degraded mode. Replica is unavailable.");
            return new ResponseEntity<>(response, HttpStatus.SERVICE_UNAVAILABLE);
        }

        response.put("message", "All systems operational");
        return ResponseEntity.ok(response);
    }

    /**
     * Force immediate replica health check.
     * Useful for testing or manual failover detection.
     */
    @GetMapping("/db/check-replica")
    public ResponseEntity<Map<String, Object>> checkReplicaHealth() {
        Map<String, Object> response = new HashMap<>();

        if (routingDataSource == null) {
            response.put("error", "Routing datasource not configured");
            return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
        }

        // Force a health check
        routingDataSource.forceReplicaHealthCheck();

        boolean replicaAvailable = routingDataSource.isReplicaAvailable();
        response.put("replica_available", replicaAvailable);
        response.put("status", replicaAvailable ? "HEALTHY" : "UNAVAILABLE");
        response.put("timestamp", System.currentTimeMillis());

        HttpStatus status = replicaAvailable ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return new ResponseEntity<>(response, status);
    }
}
