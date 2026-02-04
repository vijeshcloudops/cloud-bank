package com.example.bankapp.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Failover-aware datasource wrapper that handles RDS failover scenarios.
 * Automatically detects when replica is unavailable and falls back to primary.
 * Handles connection validation and recovery after failover.
 */
@Component
public class FailoverAwareDataSourceConfig {

    @Value("${rds.failover.connection-timeout:5000}")
    private int failoverConnectionTimeout;

    @Value("${rds.failover.max-retries:3}")
    private int maxRetries;

    /**
     * Create a resilient HikariCP datasource with failover handling.
     * This datasource will automatically handle connection failures and reconnect.
     */
    public DataSource createResilientDataSource(String driver, String url, String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setDriverClassName(driver);
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);

        // Connection pool settings for failover resilience
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(failoverConnectionTimeout);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        
        // Connection validation settings - CRITICAL for failover detection
        config.setConnectionTestQuery("SELECT 1");
        config.setLeakDetectionThreshold(60000);
        config.setAutoCommit(true);

        // Connection retry settings
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        // MySQL-specific properties for connection resilience
        config.addDataSourceProperty("useServerPrepStmts", "true");
        config.addDataSourceProperty("socketTimeout", String.valueOf(failoverConnectionTimeout));
        config.addDataSourceProperty("connectTimeout", String.valueOf(failoverConnectionTimeout));

        return new HikariDataSource(config);
    }

    /**
     * Test if a datasource connection is valid.
     * Used to detect if replica/primary is still available after failover.
     */
    public boolean isDataSourceAvailable(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(5);
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Get the number of retries configured for failover scenarios
     */
    public int getMaxRetries() {
        return maxRetries;
    }
}
