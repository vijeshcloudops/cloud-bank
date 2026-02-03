package com.example.bankapp.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * Main Spring Boot configuration class for read replica support.
 * Enables AOP for automatic read/write datasource routing.
 */
@Configuration
@EnableAspectJAutoProxy
public class DataSourceRouterConfig {
    // AOP is enabled here, DataSourceAspect will handle routing
    // DualDataSourceConfig creates the routing datasource
}
