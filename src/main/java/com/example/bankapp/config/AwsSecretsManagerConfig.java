package com.example.bankapp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import javax.sql.DataSource;
import java.util.Map;

@Configuration
public class AwsSecretsManagerConfig {

    @Value("${aws.secrets.name}")
    private String secretName;

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    @Value("${spring.datasource.url:}")
    private String fallbackUrl;

    @Bean
    public DataSource dataSource() {
        System.out.println("==================================================");
        System.out.println("Attempting to fetch database credentials from AWS Secrets Manager");
        System.out.println("Secret Name: " + secretName);
        System.out.println("AWS Region: " + awsRegion);
        System.out.println("==================================================");
        
        try (SecretsManagerClient client = SecretsManagerClient.builder().region(Region.of(awsRegion)).build()) {
            GetSecretValueRequest getSecretValueRequest = GetSecretValueRequest.builder()
                    .secretId(secretName)
                    .build();

            System.out.println("Fetching secret: " + secretName);
            GetSecretValueResponse getSecretValueResponse = client.getSecretValue(getSecretValueRequest);
            String secretString = getSecretValueResponse.secretString();

            System.out.println("Secret retrieved successfully");
            ObjectMapper mapper = new ObjectMapper();
            Map<String, String> secretMap = mapper.readValue(secretString, Map.class);

            String username = secretMap.getOrDefault("username", secretMap.get("user"));
            String password = secretMap.get("password");
            String host = secretMap.get("host");
            String port = secretMap.getOrDefault("port", "3306");
            String dbname = secretMap.getOrDefault("dbname", secretMap.get("database"));

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

            String url = String.format("jdbc:mysql://%s:%s/%s?useSSL=true&serverTimezone=UTC&allowPublicKeyRetrieval=true", host, port, dbname);
            System.out.println("DataSource URL: " + url);
            System.out.println("Username: " + username);

            return DataSourceBuilder.create()
                    .driverClassName("com.mysql.cj.jdbc.Driver")
                    .url(url)
                    .username(username)
                    .password(password)
                    .build();
        } catch (Exception e) {
            System.err.println("==================================================");
            System.err.println("ERROR: Failed to create DataSource from AWS Secrets Manager");
            System.err.println("==================================================");
            System.err.println("Exception Type: " + e.getClass().getName());
            System.err.println("Exception Message: " + e.getMessage());
            System.err.println("==================================================");
            System.err.println("Troubleshooting tips:");
            System.err.println("1. Ensure AWS credentials are configured (~/.aws/credentials or environment variables)");
            System.err.println("2. Verify the secret exists: aws secretsmanager get-secret-value --secret-id " + secretName + " --region " + awsRegion);
            System.err.println("3. Verify the secret JSON contains: username, password, host, dbname, port (optional)");
            System.err.println("4. Ensure IAM user has 'secretsmanager:GetSecretValue' permission");
            System.err.println("==================================================");
            e.printStackTrace();
            throw new RuntimeException("Failed to create DataSource from AWS Secrets Manager", e);
        }
    }
}
