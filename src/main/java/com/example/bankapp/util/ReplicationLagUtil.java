package com.example.bankapp.util;

import com.example.bankapp.config.ReplicationLagContext;

/**
 * Utility class for managing replication lag and ensuring data consistency.
 * Provides methods to check replica readiness and wait for synchronization.
 */
public class ReplicationLagUtil {

    private static final long DEFAULT_MAX_WAIT_TIME_MS = 5000; // 5 seconds max wait

    /**
     * Wait for replica to be synchronized after a write operation.
     * Waits up to DEFAULT_MAX_WAIT_TIME_MS for replica to catch up.
     */
    public static void waitForReplicaSync() {
        waitForReplicaSync(DEFAULT_MAX_WAIT_TIME_MS);
    }

    /**
     * Wait for replica to be synchronized with custom timeout.
     * Useful for operations that need stricter consistency guarantees.
     * 
     * @param maxWaitTimeMs Maximum time to wait in milliseconds
     */
    public static void waitForReplicaSync(long maxWaitTimeMs) {
        long startTime = System.currentTimeMillis();
        
        while (!ReplicationLagContext.isReplicaReady()) {
            if (System.currentTimeMillis() - startTime > maxWaitTimeMs) {
                System.err.println("WARNING: Replication lag exceeded " + maxWaitTimeMs + "ms, proceeding with read");
                break;
            }
            
            try {
                Thread.sleep(10); // Check every 10ms
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Check if replica is currently ready for reads.
     */
    public static boolean isReplicaReady() {
        return ReplicationLagContext.isReplicaReady();
    }

    /**
     * Get time elapsed since last write operation (in milliseconds).
     */
    public static long getTimeSinceLastWrite() {
        return ReplicationLagContext.getTimeSinceLastWrite();
    }

    /**
     * Reset replication lag tracking (used for testing or when replica is refreshed).
     */
    public static void resetReplicationTracking() {
        ReplicationLagContext.resetLastWrite();
    }
}
