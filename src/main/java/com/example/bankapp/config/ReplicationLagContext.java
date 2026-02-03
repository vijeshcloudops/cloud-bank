package com.example.bankapp.config;

/**
 * Context holder for tracking replication lag and managing read consistency.
 * Ensures customers don't read stale data immediately after writes.
 */
public class ReplicationLagContext {

    private static final ThreadLocal<ReplicationLagContext> contextHolder = new ThreadLocal<>();
    
    private long lastWriteTimestamp = 0;
    private static final long DEFAULT_REPLICATION_LAG_MS = 100; // Default 100ms replication lag

    private ReplicationLagContext() {
    }

    public static void recordWrite() {
        ReplicationLagContext context = contextHolder.get();
        if (context == null) {
            context = new ReplicationLagContext();
            contextHolder.set(context);
        }
        context.lastWriteTimestamp = System.currentTimeMillis();
    }

    public static boolean isReplicaReady() {
        ReplicationLagContext context = contextHolder.get();
        if (context == null || context.lastWriteTimestamp == 0) {
            return true; // No recent writes, replica is safe
        }
        
        long elapsedTime = System.currentTimeMillis() - context.lastWriteTimestamp;
        return elapsedTime >= DEFAULT_REPLICATION_LAG_MS;
    }

    public static long getTimeSinceLastWrite() {
        ReplicationLagContext context = contextHolder.get();
        if (context == null || context.lastWriteTimestamp == 0) {
            return Long.MAX_VALUE;
        }
        return System.currentTimeMillis() - context.lastWriteTimestamp;
    }

    public static void clear() {
        contextHolder.remove();
    }

    public static void resetLastWrite() {
        ReplicationLagContext context = contextHolder.get();
        if (context != null) {
            context.lastWriteTimestamp = 0;
        }
    }
}
