package com.example.bankapp.controller;

import com.example.bankapp.util.ReplicationLagUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * REST controller for monitoring replication lag and database status.
 * Provides insights into read replica synchronization.
 */
@RestController
@RequestMapping("/api/db")
public class DatabaseMonitoringController {

    /**
     * Check replication lag status.
     * Useful for monitoring dashboard and debugging.
     * 
     * @return JSON with replication status
     */
    @GetMapping("/replication-status")
    public ResponseEntity<Map<String, Object>> getReplicationStatus() {
        Map<String, Object> response = new HashMap<>();
        
        boolean replicaReady = ReplicationLagUtil.isReplicaReady();
        long timeSinceWrite = ReplicationLagUtil.getTimeSinceLastWrite();
        
        response.put("replica_ready", replicaReady);
        response.put("time_since_last_write_ms", timeSinceWrite);
        response.put("replica_synchronized", timeSinceWrite > 100); // Assuming 100ms replication lag
        response.put("status", replicaReady ? "SYNCHRONIZED" : "CATCHING_UP");
        
        if (!replicaReady) {
            response.put("message", "Replica is catching up with primary. Reads may be routed to primary for consistency.");
        } else {
            response.put("message", "Replica is synchronized. Reads are routed to replica.");
        }
        
        return ResponseEntity.ok(response);
    }

    /**
     * Health check endpoint for read replica.
     * Returns 200 if replica is ready, 503 if it's catching up.
     * 
     * @return Health status
     */
    @GetMapping("/replica-health")
    public ResponseEntity<Map<String, String>> getReplicaHealth() {
        Map<String, String> response = new HashMap<>();
        
        if (ReplicationLagUtil.isReplicaReady()) {
            response.put("status", "UP");
            response.put("message", "Read replica is synchronized and ready");
            return ResponseEntity.ok(response);
        } else {
            response.put("status", "DEGRADED");
            response.put("message", "Read replica is catching up, primary will be used for reads");
            return ResponseEntity.status(503).body(response);
        }
    }

    /**
     * Reset replication tracking (use with caution - mainly for testing).
     * 
     * @return Confirmation
     */
    @GetMapping("/replication-reset")
    public ResponseEntity<Map<String, String>> resetReplication() {
        ReplicationLagUtil.resetReplicationTracking();
        
        Map<String, String> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", "Replication tracking reset");
        
        return ResponseEntity.ok(response);
    }
}
