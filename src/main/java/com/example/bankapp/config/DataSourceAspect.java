package com.example.bankapp.config;

import com.example.bankapp.annotation.PrimaryDatabase;
import com.example.bankapp.annotation.ReadReplica;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.sql.SQLException;

/**
 * AOP aspect for automatic read/write datasource routing.
 * Intercepts methods with @ReadReplica and @PrimaryDatabase annotations.
 * Handles replication lag awareness, automatic fallback to primary, and retry logic.
 * 
 * FAILOVER HANDLING:
 * - Automatically detects when replica is unavailable
 * - Retries failed queries on primary
 * - Provides graceful degradation during RDS failover
 */
@Aspect
@Component
public class DataSourceAspect {

    @Autowired(required = false)
    private FailoverAwareDataSourceConfig failoverConfig;

    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 100;

    /**
     * Aspect for routing reads to replica with replication lag awareness.
     * Includes retry logic for transient database failures.
     */
    @Around("@annotation(readReplica)")
    public Object readReplicaRouting(ProceedingJoinPoint joinPoint, ReadReplica readReplica) throws Throwable {
        int maxRetries = failoverConfig != null ? failoverConfig.getMaxRetries() : DEFAULT_MAX_RETRIES;
        Throwable lastException = null;
        
        try {
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    // Check if replica is ready (respecting replication lag)
                    if (!ReplicationLagContext.isReplicaReady() && readReplica.fallbackToPrimary()) {
                        // Use primary database if replica is not yet synchronized
                        DataSourceContext.setWritable();
                    } else {
                        // Route to read replica
                        DataSourceContext.setReadOnly();
                    }

                    return joinPoint.proceed();
                    
                } catch (SQLException e) {
                    lastException = e;
                    System.err.println("Attempt " + attempt + " failed for read replica: " + e.getMessage());
                    
                    // If it's the last attempt, throw the exception
                    if (attempt == maxRetries) {
                        throw e;
                    }
                    
                    // For transient errors, retry after delay
                    if (isTransientError(e)) {
                        System.out.println("Transient error detected. Retrying in " + RETRY_DELAY_MS + "ms...");
                        Thread.sleep(RETRY_DELAY_MS * attempt); // Exponential backoff
                        continue;
                    }
                    
                    // For non-transient errors, fallback to primary on next attempt
                    System.out.println("Non-transient error. Falling back to primary database.");
                    DataSourceContext.setWritable();
                    

                } catch (Throwable e) {
                    lastException = e;
                    if (attempt == maxRetries) {
                        throw e;
                    }
                    Thread.sleep(RETRY_DELAY_MS * attempt);
                }
            }
            
            throw lastException;
        } finally {
            // Always clear context after method completes
            DataSourceContext.clear();
        }
    }

    /**
     * Aspect for routing writes to primary database.
     * Records timestamp for replication lag tracking.
     * Includes retry logic for failover scenarios.
     */
    @Around("@annotation(primaryDatabase)")
    public Object primaryDatabaseRouting(ProceedingJoinPoint joinPoint, PrimaryDatabase primaryDatabase) throws Throwable {
        int maxRetries = failoverConfig != null ? failoverConfig.getMaxRetries() : DEFAULT_MAX_RETRIES;
        Throwable lastException = null;
        
        try {
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    DataSourceContext.setWritable();

                    Object result = joinPoint.proceed();
                    
                    // Record write timestamp for replication lag tracking
                    if (primaryDatabase.trackReplication()) {
                        ReplicationLagContext.recordWrite();
                    }
                    
                    return result;
                    
                } catch (SQLException e) {
                    lastException = e;
                    System.err.println("Attempt " + attempt + " failed for primary write: " + e.getMessage());
                    
                    if (attempt == maxRetries) {
                        throw e;
                    }
                    
                    // Retry with exponential backoff for transient errors
                    if (isTransientError(e)) {
                        System.out.println("Transient error on primary. Retrying in " + (RETRY_DELAY_MS * attempt) + "ms...");
                        Thread.sleep(RETRY_DELAY_MS * attempt);
                    }
                    
                } catch (Throwable e) {
                    lastException = e;
                    if (attempt == maxRetries) {
                        throw e;
                    }
                    Thread.sleep(RETRY_DELAY_MS * attempt);
                }
            }
            
            throw lastException;
        } finally {
            // Always clear context after method completes
            DataSourceContext.clear();
        }
    }

    /**
     * Determine if a SQL exception is transient (might succeed on retry).
     */
    private boolean isTransientError(Throwable ex) {
        if (!(ex instanceof SQLException)) {
            return false;
        }
        
        SQLException sqlEx = (SQLException) ex;
        String sqlState = sqlEx.getSQLState();
        int errorCode = sqlEx.getErrorCode();
        String message = sqlEx.getMessage().toLowerCase();

        // MySQL error codes for transient errors
        boolean isTransient = 
            errorCode == 1205 ||  // ER_LOCK_WAIT_TIMEOUT
            errorCode == 1317 ||  // ER_QUERY_INTERRUPTED
            errorCode == 2002 ||  // Connection refused
            errorCode == 2006 ||  // MySQL server has gone away
            errorCode == 2013 ||  // Lost connection
            errorCode == 1317 ||  // Query interrupted
            (sqlState != null && (
                sqlState.startsWith("08") ||  // Connection exception
                sqlState.equals("40001") ||   // Serialization failure
                sqlState.equals("40002")      // Integrity constraint violation
            )) ||
            message.contains("connection refused") ||
            message.contains("connection timeout") ||
            message.contains("server has gone away") ||
            message.contains("lost connection") ||
            message.contains("broken pipe") ||
            message.contains("connection reset");

        return isTransient;
    }
}

