package com.example.bankapp.config;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * Custom routing datasource that routes queries to read replica or primary based on context.
 * Writes always go to primary (WRITE), reads can use replica (READ).
 */
public class ReadWriteRoutingDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        DataSourceContext context = DataSourceContext.get();
        if (context != null && context.isReadOnly()) {
            return "READ";
        }
        return "WRITE";
    }
}
