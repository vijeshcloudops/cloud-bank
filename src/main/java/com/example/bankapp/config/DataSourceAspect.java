package com.example.bankapp.config;

import com.example.bankapp.annotation.PrimaryDatabase;
import com.example.bankapp.annotation.ReadReplica;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * AOP aspect for automatic read/write datasource routing.
 * Intercepts methods with @ReadReplica and @PrimaryDatabase annotations.
 * Handles replication lag awareness and automatic fallback to primary when needed.
 */
@Aspect
@Component
public class DataSourceAspect {

    /**
     * Aspect for routing reads to replica with replication lag awareness.
     */
    @Around("@annotation(readReplica)")
    public Object readReplicaRouting(ProceedingJoinPoint joinPoint, ReadReplica readReplica) throws Throwable {
        // Check if replica is ready (respecting replication lag)
        if (!ReplicationLagContext.isReplicaReady() && readReplica.fallbackToPrimary()) {
            // Use primary database if replica is not yet synchronized
            DataSourceContext.setWritable();
        } else {
            // Route to read replica
            DataSourceContext.setReadOnly();
        }

        try {
            return joinPoint.proceed();
        } finally {
            DataSourceContext.clear();
        }
    }

    /**
     * Aspect for routing writes to primary database.
     * Records timestamp for replication lag tracking.
     */
    @Around("@annotation(primaryDatabase)")
    public Object primaryDatabaseRouting(ProceedingJoinPoint joinPoint, PrimaryDatabase primaryDatabase) throws Throwable {
        DataSourceContext.setWritable();

        try {
            Object result = joinPoint.proceed();
            
            // Record write timestamp for replication lag tracking
            if (primaryDatabase.trackReplication()) {
                ReplicationLagContext.recordWrite();
            }
            
            return result;
        } finally {
            DataSourceContext.clear();
        }
    }
}
