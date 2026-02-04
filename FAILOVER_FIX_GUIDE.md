# RDS Failover Error Fix - Implementation Guide

## Problem Summary

When the standby RDS instance becomes the primary during an RDS failover event:
- The application receives **500 Internal Server Error (Whitelabel Error)**
- The replica datasource endpoint becomes invalid/unreachable
- Existing connections pool stale references to the old replica
- No automatic recovery mechanism exists

## Root Cause Analysis

1. **Stale Datasource References**: Once created, datasources maintain cached connections
2. **No Health Checks**: The routing datasource doesn't verify replica availability
3. **No Retry Logic**: Transient connection failures aren't automatically retried
4. **No Error Handling**: Database errors fall through to default Spring error page

## Solution Architecture

### Components Implemented

#### 1. **FailoverAwareDataSourceConfig** (`config/FailoverAwareDataSourceConfig.java`)
- Creates resilient HikariCP datasources with optimal failover settings
- Provides connection health validation methods
- Handles datasource creation with proper timeout configuration
- **Key Features:**
  - Connection test queries for validation
  - Optimized connection pool settings
  - Socket timeout configuration for faster failure detection

#### 2. **Enhanced ReadWriteRoutingDataSource** (`config/ReadWriteRoutingDataSource.java`)
- Now monitors replica availability continuously
- **Automatic Failover Logic:**
  - Checks replica health every 5 seconds (configurable)
  - Routes all queries to PRIMARY if replica is unavailable
  - Automatic recovery when replica becomes available again
- Non-blocking health checks to avoid performance impact
- Informative logging for failover events

#### 3. **Retry-Enabled DataSourceAspect** (`config/DataSourceAspect.java`)
- Implements exponential backoff retry logic (default: 3 retries)
- Distinguishes between transient and permanent errors
- **Transient Error Detection:**
  - MySQL error codes: 1205, 1317, 2002, 2006, 2013, etc.
  - SQL states: 08* (connection), 40001/40002 (transactions)
  - Message patterns: "gone away", "broken pipe", "connection reset", etc.
- Automatic fallback to primary for failed replica reads
- Separate retry strategies for reads vs. writes

#### 4. **Global Exception Handler** (`config/GlobalExceptionHandler.java`)
- Catches all database connection errors globally
- Returns proper HTTP status codes:
  - **503 Service Unavailable** for transient errors
  - **500 Internal Server Error** for permanent failures
- Provides meaningful error messages for users
- Includes retry recommendation in responses

#### 5. **Health Check Endpoints** (`controller/HealthCheckController.java`)
- `/health` - Quick application health status
- `/health/db` - Detailed database failover status
- `/health/db/check-replica` - Force immediate replica health check
- Returns appropriate HTTP status codes for monitoring

#### 6. **Error Page Template** (`templates/error-503.html`)
- User-friendly error page for service unavailable
- Auto-refresh every 10 seconds
- Shows failover status information
- Option for manual retry

## Configuration

### Update `application.properties`

```properties
# Failover & Resilience Configuration
rds.failover.enabled=true
rds.failover.connection-timeout=5000         # Timeout for health checks
rds.failover.max-retries=3                   # Number of retries

# Connection Pool - Failover optimized
spring.datasource.hikari.connection-test-query=SELECT 1
spring.datasource.hikari.maximum-pool-size=10
spring.datasource.hikari.minimum-idle=5
spring.datasource.hikari.connection-timeout=20000
```

## Failover Flow Diagram

```
User Request
    ↓
┌─────────────────────────────────┐
│ DataSourceAspect (Interceptor) │
│ - Checks annotation            │
│ - Sets read/write context      │
└────────────┬────────────────────┘
             ↓
┌─────────────────────────────────────┐
│ ReadWriteRoutingDataSource         │
│ - Checks if READ or WRITE          │
│ - For READ: Checks replica health  │
│   - If unavailable → USE PRIMARY   │
│   - If available → USE REPLICA     │
│ - For WRITE: Always use PRIMARY    │
└────────────┬────────────────────────┘
             ↓
┌─────────────────────────────────────┐
│ Execute Query                      │
│ - If success → Return result       │
│ - If failure → Catch exception     │
└────────────┬────────────────────────┘
             ↓
┌─────────────────────────────────────┐
│ Exception Handling                 │
│ - Is it transient? (YES/NO)       │
│ - YES → Retry with backoff        │
│ - NO → Return 500 error           │
└────────────┬────────────────────────┘
             ↓
         Response
```

## Failover Timeline

### During Normal Operation
```
User reads data
    ↓
Replica health check (every 5 seconds)
    ↓
Replica HEALTHY → Query goes to replica
    ↓
Response returned
```

### During RDS Failover (Standby → Primary)

```
Time: T+0s
  - AWS RDS initiates failover
  - Replica endpoint becomes unreachable

Time: T+1-2s
  - Next READ query attempts replica
  - Connection fails (transient error)
  - DataSourceAspect detects and retries (attempt 1/3)

Time: T+2-3s
  - Retry still fails (replica still recovering)
  - ReadWriteRoutingDataSource detects replica unhealthy
  - All new READ queries route to PRIMARY

Time: T+3-5s
  - RDS failover completes
  - New replica promoted (if read replicas enabled)

Time: T+5-10s
  - Replica health check passes
  - New READ replica endpoint becomes available
  - Queries resume routing to replica

Recovery: T+10s
  - All operations normal
  - Zero downtime achieved!
```

## Testing the Failover

### Test 1: Simulate Replica Failure
```bash
# Check current status
curl http://localhost:8080/health/db

# Manually trigger replica health check
curl http://localhost:8080/health/db/check-replica
```

### Test 2: Make a Database Query During Failover
```bash
curl -X POST http://localhost:8080/deposit \
  -d "amount=100" \
  -H "Content-Type: application/x-www-form-urlencoded"
```

### Test 3: Monitor Failover Logs
```bash
# Watch logs for failover messages
tail -f logs/app.log | grep -i "FAILOVER\|retry\|replica"
```

## Monitoring & Alerting

### CloudWatch Metrics to Monitor
1. RDS failover events
2. Connection pool utilization
3. Query retry rates
4. Replica availability status

### Alerting Rules
```
- Alert if `/health/db` returns 503 for > 30 seconds
- Alert if retry rate > 5% for > 1 minute
- Alert if replica unavailable for > 5 minutes
```

### Log Messages to Watch For
```
✅ "Replica is synchronized. Reads are routed to replica."
⚠️  "FAILOVER DETECTED: Read replica is NO LONGER AVAILABLE"
✅ "RECOVERY: Read replica is now available again"
⏱️  "Transient error detected. Retrying..."
```

## Configuration Tuning

### For Faster Failover Detection
```properties
# Check replica health more frequently
rds.replica.health-check-interval=2000  # Default: 5000ms
rds.failover.connection-timeout=3000    # Default: 5000ms
```

### For More Aggressive Retries
```properties
rds.failover.max-retries=5              # Default: 3
spring.datasource.hikari.connection-timeout=15000  # Default: 20000
```

### For Constrained Environments
```properties
spring.datasource.hikari.maximum-pool-size=5       # Default: 10
rds.failover.connection-timeout=10000  # Longer timeout
rds.failover.max-retries=2             # Fewer retries
```

## Troubleshooting

### Still Getting 500 Errors?
1. Check if replica is actually back online: `curl http://localhost:8080/health/db`
2. Verify AWS Secrets Manager has correct credentials for both primary and replica
3. Ensure security groups allow connectivity to both database endpoints
4. Check CloudWatch logs for persistent connection errors

### Replica Health Check Always Fails?
1. Verify replica database endpoint is correct in AWS Secrets Manager
2. Test connectivity: `mysql -h <replica-endpoint> -u <user> -p<password> -e "SELECT 1"`
3. Check if read replica is actually enabled in RDS console
4. Ensure database credentials have SELECT permissions

### Performance Degradation During Failover?
1. Reduce health check frequency if too aggressive
2. Increase connection pool size: `spring.datasource.hikari.maximum-pool-size`
3. Increase retry delays: `rds.failover.connection-timeout`

## Success Criteria

✅ Application continues serving requests during RDS failover
✅ Automatic recovery without manual intervention
✅ Meaningful error messages for users
✅ Detailed logging for troubleshooting
✅ Health check endpoints for monitoring
✅ No code changes required in business logic
✅ Transparent to application users

## Files Modified/Created

```
Created:
  src/main/java/com/example/bankapp/config/FailoverAwareDataSourceConfig.java
  src/main/java/com/example/bankapp/config/GlobalExceptionHandler.java
  src/main/java/com/example/bankapp/controller/HealthCheckController.java
  src/main/resources/templates/error-503.html

Modified:
  src/main/java/com/example/bankapp/config/DualDataSourceConfig.java
  src/main/java/com/example/bankapp/config/ReadWriteRoutingDataSource.java
  src/main/java/com/example/bankapp/config/DataSourceAspect.java
  src/main/resources/application.properties
```

## Next Steps

1. Build and deploy the updated application
2. Monitor the `/health` endpoint in production
3. Test actual RDS failover from AWS console
4. Set up CloudWatch alarms for failover events
5. Document runbook for team troubleshooting

## References

- [AWS RDS Failover Documentation](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Concepts.MultiAZ.html)
- [MySQL Connection Error Codes](https://dev.mysql.com/doc/mysql-errors/8.0/en/)
- [HikariCP Configuration](https://github.com/brettwooldridge/HikariCP)
- [Spring AOP Documentation](https://spring.io/projects/spring-framework)
