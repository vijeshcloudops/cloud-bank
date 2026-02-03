package com.example.bankapp.annotation;

import java.lang.annotation.*;

/**
 * Annotation to mark methods that must use the primary database.
 * Applied to write operations (inserts, updates, deletes).
 * Records write timestamp for replication lag tracking.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PrimaryDatabase {
    
    /**
     * If true, records this write operation for replication lag tracking.
     * Default is true to ensure data consistency.
     */
    boolean trackReplication() default true;
}
