package com.example.bankapp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration for dual datasources (primary and read replica).
 * Fetches credentials from AWS Secrets Manager for both primary and replica databases.
 * Sets up routing datasource for automatic read/write splitting.
 */
@Configuration
public class DualDataSourceConfig {

    @Autowired
    private FailoverAwareDataSourceConfig failoverConfig;

    @Value("${aws.secrets.name}")
    private String primarySecretName;

    @Value("${aws.secrets.replica-name:}")
    private String replicaSecretName;

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    @Value("${rds.primary.host:}")
    private String primaryHost;

    @Value("${rds.replica.host:}")
    private String replicaHost;

    @Value("${rds.failover.enabled:true}")
    private boolean failoverEnabled;

    /**
     * Create datasource for primary (write) database from AWS Secrets Manager or config.
     */
    @Bean(name = "primaryDataSource")
    public DataSource primaryDataSource() {
        // If replica secret is not configured, use same datasource as before
        if (replicaSecretName == null || replicaSecretName.isEmpty()) {
            System.out.println("Replica secret not configured, using single datasource mode");
            return createDataSourceFromSecret(primarySecretName);
        }

        System.out.println("Creating PRIMARY datasource for write operations");
        return createDataSourceFromSecret(primarySecretName);
    }

    /**
     * Create datasource for read replica database from AWS Secrets Manager or config.
     */
    @Bean(name = "replicaDataSource")
    public DataSource replicaDataSource() {
        // If replica secret is not configured, use same as primary
        if (replicaSecretName == null || replicaSecretName.isEmpty()) {
            System.out.println("Replica secret not configured, using primary datasource for reads");
            return primaryDataSource();
        }

        System.out.println("Creating REPLICA datasource for read operations");
        return createDataSourceFromSecret(replicaSecretName);
    }

    /**
     * Create routing datasource that switches between primary and replica.
     * Includes failover handling for when replica becomes unavailable.
     */
    @Bean
    @Primary
    public DataSource dataSource(DataSource primaryDataSource, DataSource replicaDataSource) {
        ReadWriteRoutingDataSource routingDataSource = new ReadWriteRoutingDataSource();
        
        // Add failover awareness to routing datasource
        if (failoverConfig != null) {
            routingDataSource.setFailoverConfig(failoverConfig);
            routingDataSource.setReplicaDataSource(replicaDataSource);
        }
        
        Map<Object, Object> targetDataSources = new HashMap<>();
        targetDataSources.put("WRITE", primaryDataSource);
        targetDataSources.put("READ", replicaDataSource);

        routingDataSource.setTargetDataSources(targetDataSources);
        routingDataSource.setDefaultTargetDataSource(primaryDataSource);

        System.out.println("========================================");
        System.out.println("Routing DataSource configured successfully");
        System.out.println("WRITE operations -> Primary Database");
        System.out.println("READ operations  -> Replica Database");
        System.out.println("Failover enabled -> " + failoverEnabled);
        System.out.println("========================================");

        return routingDataSource;
    }

    /**
     * Helper method to create datasource from AWS Secrets Manager.
     */
    private DataSource createDataSourceFromSecret(String secretName) {
        System.out.println("Fetching database credentials from AWS Secrets Manager: " + secretName);
        
        try (SecretsManagerClient client = SecretsManagerClient.builder().region(Region.of(awsRegion)).build()) {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(secretName)
                    .build();

            GetSecretValueResponse response = client.getSecretValue(request);
            String secretString = response.secretString();

            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> secretMap = mapper.readValue(secretString, Map.class);

            String username = getStringValue(secretMap, "username", secretMap.get("user"));
            String password = getStringValue(secretMap, "password");
            String host = getStringValue(secretMap, "host");
            String port = getStringValue(secretMap, "port", "3306");
            String dbname = getStringValue(secretMap, "dbname", secretMap.get("database"));

            if (host == null || dbname == null) {
                throw new IllegalStateException(
                    "AWS Secret does not contain required fields. Expected 'host' and 'dbname' (or 'database'). " +
                    "Secret contains keys: " + secretMap.keySet()
                );
            }

            if (username == null || password == null) {
                throw new IllegalStateException(
                    "AWS Secret does not contain authentication fields. Expected 'username' and 'password'. " +
                    "Secret contains keys: " + secretMap.keySet()
                );
            }

            String url = String.format("jdbc:mysql://%s:%s/%s?useSSL=true&serverTimezone=UTC&allowPublicKeyRetrieval=true", 
                    host, port, dbname);
            
            System.out.println("DataSource URL: " + url);
            System.out.println("Username: " + username);

            // Use failover-aware resilient datasource instead of plain DataSourceBuilder
            return failoverConfig.createResilientDataSource(
                    "com.mysql.cj.jdbc.Driver",
                    url,
                    username,
                    password
            );
        } catch (Exception e) {
            System.err.println("ERROR: Failed to create DataSource from AWS Secrets Manager: " + secretName);
            System.err.println("Exception: " + e.getMessage());
            throw new RuntimeException("Failed to create DataSource from AWS Secrets Manager", e);
        }
    }

    private String getStringValue(Map<String, Object> map, String key, Object defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            if (defaultValue instanceof String) {
                return (String) defaultValue;
            }
            return null;
        }
        return value.toString();
    }

    private String getStringValue(Map<String, Object> map, String key) {
        return getStringValue(map, key, null);
    }
}
