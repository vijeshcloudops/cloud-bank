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
    }

    public static void setWritable() {
        contextHolder.set(new DataSourceContext(false));
    }

    public static DataSourceContext get() {
        return contextHolder.get();
    }

    public static void clear() {
        contextHolder.remove();
    }

    public boolean isReadOnly() {
        return readOnly;
    }
}
