# RDS Read Replica Setup Guide - Manual Configuration

## Overview
This guide explains how to manually set up RDS read replicas for the CloudBank application with automatic read/write splitting and replication lag handling.

## Architecture

### Components
1. **Primary RDS Instance** (`cloudbank`): Handles all write operations
2. **Read Replica** (`cloudbank-read-replica`): Handles read-only queries
3. **Application Layer**: Automatically routes reads to replica, writes to primary
4. **Replication Lag Tracking**: Ensures data consistency despite lag

### How It Works

```
┌─────────────────────────────────────┐
│  CloudBank Application              │
│  ┌──────────────────────────────┐   │
│  │ Service Layer (@ReadReplica) │   │
│  │ Service Layer (@PrimaryDB)   │   │
│  └──────────────────────────────┘   │
│           │                         │
│    ┌──────┴──────┐                  │
│    │             │                  │
│    v             v                  │
│  WRITE        READ                  │
│  Routes       Routes                │
│    │             │                  │
└────┼─────────────┼──────────────────┘
     │             │
     v             v
  ┌──────────┐  ┌──────────────┐
  │ Primary  │  │ Read Replica │
  │ Instance │  │ Instance     │
  └──────────┘  └──────────────┘
     (write)      (read-only)
     (Multi-AZ)   (async replicated)
```

## Prerequisites

### AWS Requirements
- AWS Account with RDS access
- VPC with at least 2 subnets in different AZs (for primary Multi-AZ)
- Security group for application access
- AWS Secrets Manager enabled for storing credentials

### Local Requirements
- AWS CLI configured with credentials
- MySQL Client 8.0+
- AWS Management Console access

## Manual Setup Steps

### Step 1: Create Primary RDS Instance

**Via AWS Console:**
1. Go to AWS RDS Dashboard → Databases → Create database
2. Configure:
   - **Engine**: MySQL 8.0.35
   - **Instance Class**: db.t3.micro (or larger for production)
   - **Storage**: 20 GB (gp3)
   - **DB Instance Identifier**: `cloudbank`
   - **Master username**: `admin`
   - **Master password**: Generate secure password (`openssl rand -base64 32`)
   - **VPC**: Select your VPC
   - **Multi-AZ**: Yes (for high availability)
   - **Storage encryption**: Yes (KMS)
   - **Backup retention**: 7 days (adjust for production)
3. Click **Create database** and wait 5-10 minutes

**Via AWS CLI:**
```bash
aws rds create-db-instance \
  --db-instance-identifier cloudbank \
  --db-instance-class db.t3.micro \
  --engine mysql \
  --engine-version 8.0.35 \
  --master-username admin \
  --master-user-password "YOUR_SECURE_PASSWORD" \
  --allocated-storage 20 \
  --storage-type gp3 \
  --storage-encrypted \
  --db-name bankapp \
  --vpc-security-group-ids sg-xxxxxxxx \
  --db-subnet-group-name default-vpc-xxxxxxx \
  --multi-az \
  --backup-retention-period 7
```

### Step 2: Create Read Replica

**Via AWS Console:**
1. Go to AWS RDS Databases → Select `cloudbank` instance
2. Click **Actions** → **Create read replica**
3. Configure:
   - **DB Instance Identifier**: `cloudbank-read-replica`
   - **Instance Class**: Same as primary (db.t3.micro)
   - **Multi-AZ**: No (read replicas don't need Multi-AZ)
   - **Storage encryption**: Yes
4. Click **Create read replica** and wait 5-10 minutes

**Via AWS CLI:**
```bash
aws rds create-db-instance-read-replica \
  --db-instance-identifier cloudbank-read-replica \
  --source-db-instance-identifier cloudbank
```

### Step 3: Verify Replication Setup

```bash
# Check primary instance
aws rds describe-db-instances \
  --db-instance-identifier cloudbank \
  --query 'DBInstances[0].[DBInstanceIdentifier,DBInstanceStatus,MultiAZ]'

# Check read replica
aws rds describe-db-instances \
  --db-instance-identifier cloudbank-read-replica \
  --query 'DBInstances[0].[DBInstanceIdentifier,DBInstanceStatus,ReadReplicaSourceDBInstanceIdentifier]'

# Check replication lag
aws cloudwatch get-metric-statistics \
  --namespace AWS/RDS \
  --metric-name ReplicaLag \
  --dimensions Name=DBInstanceIdentifier,Value=cloudbank-read-replica \
  --start-time $(date -u -d '5 minutes ago' +%Y-%m-%dT%H:%M:%S) \
  --end-time $(date -u +%Y-%m-%dT%H:%M:%S) \
  --period 300 \
  --statistics Average
```

### Step 4: Configure Application

**Step 4A: Store Credentials in AWS Secrets Manager**

```bash
# Create secret for primary database
aws secretsmanager create-secret \
  --name cloudbank/db/credentials \
  --description "RDS primary database credentials" \
  --secret-string '{
    "username": "admin",
    "password": "YOUR_SECURE_PASSWORD",
    "host": "cloudbank.xxxxx.rds.amazonaws.com",
    "port": 3306,
    "dbname": "bankapp",
    "engine": "mysql"
  }'

# Create secret for read replica
aws secretsmanager create-secret \
  --name cloudbank/db/replica-credentials \
  --description "RDS read replica database credentials" \
  --secret-string '{
    "username": "admin",
    "password": "YOUR_SECURE_PASSWORD",
    "host": "cloudbank-read-replica.xxxxx.rds.amazonaws.com",
    "port": 3306,
    "dbname": "bankapp",
    "engine": "mysql"
  }'
```

The application automatically fetches and uses these secrets:
1. Fetches primary credentials from `cloudbank/db/credentials`
2. Fetches replica credentials from `cloudbank/db/replica-credentials`
3. Routes queries based on the annotation
Run the application
java -jar target/bankapp-0.0.1-SNAPSHOT.jar

# 
**Step 4B: Verify application.properties Configuration**

Configured in [src/main/resources/application.properties](src/main/resources/application.properties):

```properties
# Primary database
aws.secrets.name=cloudbank/db/credentials

# Read replica database
aws.secrets.replica-name=cloudbank/db/replica-credentials

# Replication lag configuration (milliseconds)
rds.replication-lag-ms=100

# Enable read/write splitting
rds.read-replica.enabled=true

# Fallback to primary if replica lags too much
rds.read-replica.fallback-to-primary=true
```

### Step 5: Build and Deploy Application

```bash
# Build the application
mvn clean package

# The application will automatically:
# - Use @ReadReplica annotation for queries (routed to replica with lag awareness)
# - Use @PrimaryDatabase annotation for writes (always use primary)
# - Track replication lag to ensure consistency
# - Fall back to primary if replica is not synchronized
```

## Code Integration

### Write Operations
```java
@Service
public class AccountService {
    
    @PrimaryDatabase(trackReplication = true)
    public void deposit(Account account, BigDecimal amount) {
        // Automatically routed to PRIMARY database
        // Write timestamp recorded for replication tracking
        account.setBalance(account.getBalance().add(amount));
        accountRepository.save(account);
    }
}
```

### Read Operations
```java
@Service
public class AccountService {
    
    @ReadReplica(fallbackToPrimary = true)
    public Account findAccountByUsername(String username) {
        // Automatically routed to READ REPLICA
        // Falls back to PRIMARY if replica lag > threshold
        return accountRepository.findByUsername(username).orElse(null);
    }
}
```

## Data Consistency Strategy

### Replication Lag Handling

The application implements a multi-layer consistency approach:

1. **Write Timestamp Tracking**: Records when writes occur
2. **Replica Readiness Check**: Before read, checks if replica has caught up
3. **Automatic Fallback**: Routes to primary if replica lag exceeds threshold
4. **Safe Default**: Configured to 100ms typical replication lag

```java
// From DataSourceAspect.java
@Around("@annotation(readReplica)")
public Object readReplicaRouting(ProceedingJoinPoint joinPoint, ReadReplica readReplica) {
    // Check if replica is ready (respecting replication lag)
    if (!ReplicationLagContext.isReplicaReady() && readReplica.fallbackToPrimary()) {
        DataSourceContext.setWritable();  // Use primary
    } else {
        DataSourceContext.setReadOnly();  // Use replica
    }
    return joinPoint.proceed();
}
```

### Customer Experience

- **Deposits/Withdrawals**: Always consistent (written to primary)
- **Account Checks**: Usually from replica (fast), falls back to primary if needed
- **Transaction History**: Usually from replica, consistent read if just after write
- **No Stale Data**: Customers never see data older than replication lag threshold

## Monitoring and Maintenance

### CloudWatch Metrics

Terraform creates alarms for:
- **Replication Lag**: Alert if > 5 seconds
- **Replica CPU**: Alert if > 80%

View in AWS Console:
```
CloudWatch → Alarms → cloudbank-replica-lag-alarm-prod
```

### Check Replication Status

```bash
# SSH into an RDS-accessible instance and run:
mysql -h cloudbank-prod.xxx.rds.amazonaws.com -u admin -p

# Check master status
SHOW MASTER STATUS\G

# Check replica status (on replica instance)
SHOW SLAVE STATUS\G
```

### Manual Lag Check

```bash
# Using application utilities
curl http://localhost:8080/api/replication/lag

# Or directly in code:
import com.example.bankapp.util.ReplicationLagUtil;

long lagMs = ReplicationLagUtil.getTimeSinceLastWrite();
if (!ReplicationLagUtil.isReplicaReady()) {
    // Replica is still catching up
}
```

## Scaling and Production Considerations

### Instance Classes for Production
- **Light Load**: `db.t3.small` (1 GB RAM, burstable)
- **Medium Load**: `db.m6i.large` (8 GB RAM, general purpose)
- **Heavy Load**: `db.r6i.xlarge` (32 GB RAM,  (set during creation)
- Automatic failover on primary failure (30-120 seconds)
- Read replica stays in separate AZ
- Combined: Read Replica + Primary + Standby = High Availability

### Backup Strategy
- **Backup retention**: Increase to 30 days for production (via AWS Console)
- **Automated backups**: Enabled by default
- **Manual snapshots**: Create before major changes

### Monitoring Configuration
1. **CloudWatch Logs**: Enable in RDS Console
   - Error logs, General logs, Slow query logs
2. **Performance Insights**: Enable in RDS Console
3. **Enhanced Monitoring**: Enable in RDS Console (60-second granularity)
- Enable Performance Insights (included in setup)
- CloudWatch Logs: error, general, slowquery
- Enhanced Monitoring: 60-second granularity

## Troubleshooting

### Issue: High Replication Lag
**Symptoms**: Customers see stale data, frequent primary database fallback

**Solutions**:
1. Increase replica instance size: `terraform apply -var="db_instance_class=db.m6i.large"`
2. Reduce application query load on replica
3. Check primary CPU/memory usage
4. Add more read replicas in different AZ/regions

### Issue: Replication Stopped
**Symptoms**: Replica lag keeps increasing, alarms triggered

**Solutions**:
1. Check if primary is alive and accepting connections
2. SSH into replica and check: `SHOW SLAVE STATUS\G`
3. If replication thread crashed, restart: `START SLAVE;`
4. May need to recreate replica: `terraform destroy` then `terraform apply`

### Issue: Too Many Connections
**Symptoms**: Application cannot get database connections

**Solutions**:
1. Increase `max_connections` parameter in RDS parameter group
2. Increase application connection pool: `spring.datasource.hikari.maximum-pool-size`
3. Profile and optimize slow queries

## Performance Tuning

### Application Configuration
```properties
# Optimize for more reads than writes (typical banking pattern)
spring.datasource.hikari.maximum-pool-size=20  # Increase for high load
spring.datasource.hikari.connection-timeout=20000

# Query optimization
spring.jpa.properties.hibernate.query.in_clause_parameter_padding=true
spring.jpa.properties.hibernate.jdbc.batch_size=20
```

### RDS Parameter Group
```
# For banking workload with frequent reads
max_connections = 1000
query_cache_type = 1
query_cache_size = 268435456  # 256MB
```

## Cleanup (Delete Infrastructure)

**To delete RDS instances via AWS Console:**
1. Go to RDS Dashboard → Databases
2. Select read replica → Delete → Confirm
3. Select primary → Delete → Confirm (takes 5-10 minutes)

**Via AWS CLI:**
```bash
# Delete read replica
aws rds delete-db-instance \
  --db-instance-identifier cloudbank-read-replica \
  --skip-final-snapshot

# Delete primary (keep final snapshot for backup)
aws rds delete-db-instance \
  --db-instance-identifier cloudbank \
  --final-db-snapshot-identifier cloudbank-final-snapshot
```

## Support and Further Reading

### AWS Documentation
- [RDS Read Replicas](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_ReadRepl.html)
- [RDS High Availability](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Concepts.MultiAZSingleStandby.html)
- [MySQL Replication](https://dev.mysql.com/doc/refman/8.0/en/replication.html)

### Application Code
- [DataSourceAspect.java](/src/main/java/com/example/bankapp/config/DataSourceAspect.java) - Read/write routing logic
- [ReplicationLagContext.java](/src/main/java/com/example/bankapp/config/ReplicationLagContext.java) - Lag tracking
- [ReplicationLagUtil.java](/src/main/java/com/example/bankapp/util/ReplicationLagUtil.java) - Utilities

## Summary

✅ **What's been implemented:**
- Dual datasource configuration (primary + read replica)
- Automatic read/write splitting via annotations
- Replication lag awareness and fallback logic
- AWS Secrets Manager integration for credentials
- Terraform for infrastructure as code
- CloudWatch monitoring and alarms
- Service layer annotations on all database operations

✅ **Customer Experience:**
- Fast reads from replica (when synchronized)
- Consistent writes always to primary
- Automatic safety fallback if replica lags
- No stale data visibility
- Transparent to application code (annotations handle it)

✅ **Production Ready:**
- Multi-AZ primary with automatic failover
- Separate read replica for scaling
- Encrypted storage and in-transit
- Comprehensive monitoring
- Disaster recovery backups
