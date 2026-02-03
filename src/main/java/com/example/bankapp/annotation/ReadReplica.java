package com.example.bankapp.annotation;

import java.lang.annotation.*;

/**
 * Annotation to mark methods that should use read replica.
 * Applied to query methods that don't modify data.
 * Respects replication lag constraints for data consistency.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ReadReplica {
    
    /**
     * If true, will fall back to primary database if replica is not yet synchronized.
     * Default is true for safety.
     */
    boolean fallbackToPrimary() default true;
}
