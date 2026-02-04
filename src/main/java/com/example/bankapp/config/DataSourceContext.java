package com.example.bankapp.config;

/**
 * ThreadLocal-based context holder for managing read/write datasource routing.
 * Tracks whether current operation should use read replica or primary database.
 */
public class DataSourceContext {

    private static final ThreadLocal<DataSourceContext> contextHolder = new ThreadLocal<>();
    
    private boolean readOnly = false;

    private DataSourceContext(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public static void setReadOnly() {
        contextHolder.set(new DataSourceContext(true));
        System.out.println("🔵 DataSourceContext set to READ-ONLY (Replica)");
    }

    public static void setWritable() {
        contextHolder.set(new DataSourceContext(false));
        System.out.println("🔴 DataSourceContext set to WRITABLE (Primary)");
    }

    public static DataSourceContext get() {
        DataSourceContext ctx = contextHolder.get();
        if (ctx != null) {
            System.out.println("📍 DataSourceContext retrieved: " + (ctx.isReadOnly() ? "READ" : "WRITE"));
        } else {
            System.out.println("⚠️  DataSourceContext is NULL - will use default (PRIMARY)");
        }
        return ctx;
    }

    public static void clear() {
        contextHolder.remove();
        System.out.println("🧹 DataSourceContext cleared");
    }

    public boolean isReadOnly() {
        return readOnly;
    }
}
