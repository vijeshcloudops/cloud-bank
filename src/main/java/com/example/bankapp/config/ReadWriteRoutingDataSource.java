package com.example.bankapp.config;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;

/**
 * Custom routing datasource that routes queries to read replica or primary based on context.
 * Writes always go to primary (WRITE), reads can use replica (READ).
 * 
 * FAILOVER HANDLING: When replica is down (during RDS failover), automatically falls back to primary.
 */
public class ReadWriteRoutingDataSource extends AbstractRoutingDataSource {

    private FailoverAwareDataSourceConfig failoverConfig;
    private DataSource replicaDataSource;
    private volatile boolean replicaAvailable = true;
    private volatile long lastReplicaCheckTime = 0;
    private static final long REPLICA_CHECK_INTERVAL_MS = 5000; // Check every 5 seconds

    public void setFailoverConfig(FailoverAwareDataSourceConfig failoverConfig) {
        this.failoverConfig = failoverConfig;
    }

    public void setReplicaDataSource(DataSource replicaDataSource) {
        this.replicaDataSource = replicaDataSource;
    }

    @Override
    protected Object determineCurrentLookupKey() {
        DataSourceContext context = DataSourceContext.get();
        
        if (context != null && context.isReadOnly()) {
            // Check if replica is still available (with caching to avoid constant checks)
            if (shouldCheckReplicaHealth()) {
                updateReplicaAvailability();
            }
            
            // If replica is down, failover to primary
            if (!replicaAvailable) {
                System.out.println("FAILOVER: Replica unavailable, routing READ to PRIMARY");
                return "WRITE";
            }
            
            return "READ";
        }
        
        return "WRITE";
    }

    /**
     * Check if it's time to validate replica health.
     */
    private boolean shouldCheckReplicaHealth() {
        long now = System.currentTimeMillis();
        if (now - lastReplicaCheckTime >= REPLICA_CHECK_INTERVAL_MS) {
            lastReplicaCheckTime = now;
            return true;
        }
        return false;
    }

    /**
     * Test replica availability and update status.
     */
    private void updateReplicaAvailability() {
        if (failoverConfig == null || replicaDataSource == null) {
            replicaAvailable = true;
            return;
        }

        boolean wasAvailable = replicaAvailable;
        try {
            replicaAvailable = failoverConfig.isDataSourceAvailable(replicaDataSource);
            
            if (wasAvailable && !replicaAvailable) {
                System.err.println("⚠️  FAILOVER DETECTED: Read replica is NO LONGER AVAILABLE");
                System.err.println("   All READ queries will be routed to PRIMARY database");
            } else if (!wasAvailable && replicaAvailable) {
                System.out.println("✅ RECOVERY: Read replica is now available again");
            }
        } catch (Exception e) {
            System.err.println("Error checking replica availability: " + e.getMessage());
            replicaAvailable = false;
        }
    }

    /**
     * Force replica health check (used for immediate failover detection).
     */
    public void forceReplicaHealthCheck() {
        lastReplicaCheckTime = 0;
        updateReplicaAvailability();
    }

    /**
     * Check if replica is currently available.
     */
    public boolean isReplicaAvailable() {
        return replicaAvailable;
    }
}

