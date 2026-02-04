package com.example.bankapp.config;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.util.HashMap;
import java.util.Map;

/**
 * Global exception handler for database and connection errors.
 * Handles failover scenarios gracefully with appropriate error messages.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handle SQL connection errors (e.g., when replica is down during failover)
     */
    @ExceptionHandler(SQLRecoverableException.class)
    public ResponseEntity<Map<String, Object>> handleSQLRecoverableException(
            SQLRecoverableException ex, WebRequest request) {
        
        Map<String, Object> response = new HashMap<>();
        response.put("error", "Database connection error");
        response.put("message", "Connection failed. This may be due to RDS failover. Please try again.");
        response.put("status", HttpStatus.SERVICE_UNAVAILABLE.value());
        response.put("timestamp", System.currentTimeMillis());

        System.err.println("SQLRecoverableException: " + ex.getMessage());
        return new ResponseEntity<>(response, HttpStatus.SERVICE_UNAVAILABLE);
    }

    /**
     * Handle general SQL exceptions (connection timeouts, network errors, etc.)
     */
    @ExceptionHandler(SQLException.class)
    public ResponseEntity<Map<String, Object>> handleSQLException(
            SQLException ex, WebRequest request) {
        
        Map<String, Object> response = new HashMap<>();
        String message = ex.getMessage();
        
        // Determine if it's a transient error
        boolean isTransient = isTransientError(ex);
        
        response.put("error", "Database error");
        response.put("message", isTransient ? 
            "Temporary database issue. Please try again." : 
            "Database connection error");
        response.put("status", isTransient ? 
            HttpStatus.SERVICE_UNAVAILABLE.value() : 
            HttpStatus.INTERNAL_SERVER_ERROR.value());
        response.put("transient", isTransient);
        response.put("timestamp", System.currentTimeMillis());

        System.err.println("SQLException: " + message);
        
        HttpStatus status = isTransient ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.INTERNAL_SERVER_ERROR;
        return new ResponseEntity<>(response, status);
    }

    /**
     * Handle generic runtime exceptions
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(
            RuntimeException ex, WebRequest request) {
        
        Map<String, Object> response = new HashMap<>();
        
        // Check if it's database-related
        if (isDatabaseError(ex)) {
            response.put("error", "Database operation failed");
            response.put("message", "Database is currently unavailable. Please try again later.");
            response.put("status", HttpStatus.SERVICE_UNAVAILABLE.value());
            return new ResponseEntity<>(response, HttpStatus.SERVICE_UNAVAILABLE);
        }
        
        response.put("error", "Internal server error");
        response.put("message", ex.getMessage());
        response.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        response.put("timestamp", System.currentTimeMillis());

        System.err.println("RuntimeException: " + ex.getMessage());
        ex.printStackTrace();
        
        return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * Determine if a SQLException is a transient error that might resolve on retry.
     */
    private boolean isTransientError(SQLException ex) {
        String sqlState = ex.getSQLState();
        int errorCode = ex.getErrorCode();
        String message = ex.getMessage().toLowerCase();

        // MySQL error codes for transient errors
        return 
            errorCode == 1205 ||  // ER_LOCK_WAIT_TIMEOUT
            errorCode == 1317 ||  // ER_QUERY_INTERRUPTED
            errorCode == 2002 ||  // Connection refused (network issue)
            errorCode == 2006 ||  // MySQL server has gone away
            errorCode == 2013 ||  // Lost connection to server
            sqlState != null && (
                sqlState.startsWith("08") ||  // Connection exception
                sqlState.equals("40001") ||   // Serialization failure
                sqlState.equals("40002")      // Integrity constraint violation
            ) ||
            message.contains("connection refused") ||
            message.contains("connection timeout") ||
            message.contains("server has gone away") ||
            message.contains("broken pipe");
    }

    /**
     * Check if exception is database-related
     */
    private boolean isDatabaseError(RuntimeException ex) {
        String message = ex.getMessage();
        if (message == null) return false;
        
        message = message.toLowerCase();
        return message.contains("datasource") ||
               message.contains("database") ||
               message.contains("connection") ||
               message.contains("sql") ||
               message.contains("hibernate");
    }
}
