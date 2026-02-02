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
        try (SecretsManagerClient client = SecretsManagerClient.builder().region(Region.of(awsRegion)).build()) {
            GetSecretValueRequest getSecretValueRequest = GetSecretValueRequest.builder()
                    .secretId(secretName)
                    .build();

            GetSecretValueResponse getSecretValueResponse = client.getSecretValue(getSecretValueRequest);
            String secretString = getSecretValueResponse.secretString();

            ObjectMapper mapper = new ObjectMapper();
            Map<String, String> secretMap = mapper.readValue(secretString, Map.class);

            String username = secretMap.getOrDefault("username", secretMap.get("user"));
            String password = secretMap.get("password");
            String host = secretMap.get("host");
            String port = secretMap.getOrDefault("port", "3306");
            String dbname = secretMap.getOrDefault("dbname", secretMap.get("database"));

            String url;
            if (host != null && dbname != null) {
                url = String.format("jdbc:mysql://%s:%s/%s?useSSL=true&serverTimezone=UTC&allowPublicKeyRetrieval=true", host, port, dbname);
            } else if (!fallbackUrl.isBlank()) {
                url = fallbackUrl;
            } else {
                throw new IllegalStateException("No valid JDBC URL could be constructed from secret and no fallback provided");
            }

            return DataSourceBuilder.create()
                    .driverClassName("com.mysql.cj.jdbc.Driver")
                    .url(url)
                    .username(username)
                    .password(password)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create DataSource from AWS Secrets Manager", e);
        }
    }
}
