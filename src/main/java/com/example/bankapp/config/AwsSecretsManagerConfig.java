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

    private String getStringValue(Map<String, Object> map, String key, Object defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            value = defaultValue;
        }
        if (value == null) {
            return null;
        }
        return String.valueOf(value);
    }

    private String getStringValue(Map<String, Object> map, String key) {
        return getStringValue(map, key, null);
    }
}
