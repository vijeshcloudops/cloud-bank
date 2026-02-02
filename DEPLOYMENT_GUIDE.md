# On-Premise Deployment Guide for Bank Application

## Architecture Overview

```
┌─────────────────────┐         ┌─────────────────────┐
│   Application VM    │         │    MySQL VM         │
│   (EC2 Instance)    │◄───────►│  (EC2 Instance)     │
│  - Java 17          │  Port 3306│ - MySQL 8.0       │
│  - Bank App (Port   │         │ - bankappdb         │
│    8080)            │         │ - bankappuser       │
└─────────────────────┘         └─────────────────────┘
        │
        │ HTTP (Port 8080)
        ▼
   ┌─────────────┐
   │  End Users  │
   └─────────────┘
```

## Prerequisites

- Two Ubuntu 20.04+ VMs (or EC2 instances) with public/private IPs
- Root or sudo access on both VMs
- Network connectivity between VMs (firewall rules configured)
- Git installed to clone the repository

## Deployment Steps

### Step 1: Set Up MySQL Server VM

On the **MySQL Server VM**:

```bash
# Clone the repository
git clone <your-repo-url>
cd bank-CI

# Make scripts executable
chmod +x setup-mysql.sh
chmod +x configure-mysql-remote.sh

# Install MySQL
sudo ./setup-mysql.sh

# Configure MySQL for remote connections (if on different VM)
sudo ./configure-mysql-remote.sh

# Verify MySQL is running
sudo systemctl status mysql
sudo mysql -u root -p
  # Inside MySQL: SELECT VERSION();
```

**MySQL Setup Details:**
- Database: `bankappdb`
- User: `bankappuser`
- Password: `BankApp@2024` (CHANGE THIS IN PRODUCTION!)
- Port: 3306

### Step 2: Configure Firewall/Security Groups

**Important:** If MySQL and App are on different VMs:

For **AWS Security Groups** (EC2):
```
MySQL VM Security Group - Inbound:
  - Type: MySQL/Aurora (3306)
  - Source: <App VM Private IP>/32
  - Description: Bank App Access to MySQL

App VM Security Group - Inbound:
  - Type: HTTP (80) or Custom TCP (8080)
  - Source: 0.0.0.0/0 (or restrict to specific IPs)
  - Description: Public Access to Bank App
```

For **UFW** (Ubuntu Firewall):
```bash
# On MySQL VM
sudo ufw allow from <APP_VM_IP> to any port 3306

# On App VM
sudo ufw allow 8080/tcp
sudo ufw enable
```

### Step 3: Update Application Configuration

If MySQL is on a **different VM**, update the connection string:

Edit `src/main/resources/application.properties`:
```properties
spring.datasource.url=jdbc:mysql://<MYSQL_VM_IP>:3306/bankappdb?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true
```

Or set via command line/systemd when deploying.

### Step 4: Deploy Application Server

On the **Application Server VM**:

```bash
# Clone the repository
git clone <your-repo-url>
cd bank-CI

# Make deploy script executable
chmod +x deploy-app.sh

# Run deployment (this builds and sets up the app)
sudo ./deploy-app.sh
```

If MySQL is on a **different VM**, edit the systemd service:
```bash
sudo nano /etc/systemd/system/bankapp.service
# Update: --spring.datasource.url=jdbc:mysql://<MYSQL_IP>:3306/...
sudo systemctl daemon-reload
```

### Step 5: Start the Application

```bash
# Start the service
sudo systemctl start bankapp

# Check status
sudo systemctl status bankapp

# View logs
sudo journalctl -u bankapp -f

# Enable auto-start on reboot
sudo systemctl enable bankapp
```

### Step 6: Access the Application

```
http://<APP_VM_PUBLIC_IP>:8080/bankapp
```

If using a reverse proxy (Nginx):
```
http://<APP_VM_PUBLIC_IP>/bankapp
```

## Production Hardening Checklist

### 1. **Change Default Passwords**
```bash
# Change MySQL password
sudo mysql -u root -p
ALTER USER 'bankappuser'@'%' IDENTIFIED BY 'NEW_STRONG_PASSWORD';
FLUSH PRIVILEGES;
```

### 2. **Enable MySQL SSL/TLS**
```bash
# Generate SSL certificates
sudo mkdir -p /etc/mysql/ssl
sudo openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
  -keyout /etc/mysql/ssl/mysql-key.pem \
  -out /etc/mysql/ssl/mysql-cert.pem

sudo chown mysql:mysql /etc/mysql/ssl/*
sudo chmod 600 /etc/mysql/ssl/*

# Update mysqld.cnf
sudo nano /etc/mysql/mysql.conf.d/mysqld.cnf
# Add:
# ssl-ca=/etc/mysql/ssl/mysql-cert.pem
# ssl-cert=/etc/mysql/ssl/mysql-cert.pem
# ssl-key=/etc/mysql/ssl/mysql-key.pem

sudo systemctl restart mysql
```

### 3. **Set Up SSL/TLS for Application (Nginx Reverse Proxy)**
```bash
# Install Nginx
sudo apt install nginx -y

# Create Nginx config
sudo nano /etc/nginx/sites-available/bankapp
```

```nginx
server {
    listen 80;
    server_name <YOUR_DOMAIN>;
    
    location /bankapp {
        proxy_pass http://localhost:8080/bankapp;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

```bash
sudo ln -s /etc/nginx/sites-available/bankapp /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl restart nginx
```

### 4. **Configure Application Logging**
```bash
# Logs are in journalctl
sudo journalctl -u bankapp --vacuum-time=30d  # Keep 30 days of logs
```

### 5. **Enable Monitoring & Backups**

**MySQL Backups:**
```bash
# Create backup script
cat > /usr/local/bin/backup-mysql.sh <<'EOF'
#!/bin/bash
BACKUP_DIR="/backups/mysql"
DATE=$(date +%Y%m%d_%H%M%S)
mkdir -p $BACKUP_DIR
mysqldump -u bankappuser -p"BankApp@2024" bankappdb | \
  gzip > $BACKUP_DIR/bankappdb_$DATE.sql.gz
find $BACKUP_DIR -mtime +7 -delete  # Delete backups older than 7 days
EOF

chmod +x /usr/local/bin/backup-mysql.sh

# Add to crontab
sudo crontab -e
# Add: 0 2 * * * /usr/local/bin/backup-mysql.sh
```

## Troubleshooting

### Application won't start
```bash
sudo journalctl -u bankapp -n 50  # View last 50 log lines
sudo systemctl restart bankapp
```

### Can't connect to MySQL
```bash
# Test MySQL connectivity
mysql -u bankappuser -pBankApp@2024 -h <MYSQL_IP> -e "SELECT VERSION();"

# Check MySQL is listening
sudo ss -tlnp | grep 3306

# Check firewall
sudo ufw status
```

### Slow queries
```bash
# Enable slow query log
sudo mysql -u root -p
SET GLOBAL slow_query_log = 'ON';
SET GLOBAL long_query_time = 2;
```

## Performance Optimization

1. **Connection pooling** - Already configured in application.properties
2. **Database indexing** - Create indexes on frequently queried columns
3. **Caching** - Consider adding Redis for session/data caching
4. **Load balancing** - Deploy multiple app instances with Nginx load balancer

## Maintenance Tasks

| Task | Frequency | Command |
|------|-----------|---------|
| Backup database | Daily | `/usr/local/bin/backup-mysql.sh` |
| Check app logs | Daily | `sudo journalctl -u bankapp` |
| Restart app | Weekly | `sudo systemctl restart bankapp` |
| Update system | Monthly | `sudo apt update && sudo apt upgrade` |
| Clean old logs | Weekly | `sudo journalctl --vacuum-time=30d` |

## Rolling Updates

```bash
# Update code
cd /path/to/bank-CI
git pull origin main

# Rebuild
mvn clean package -DskipTests

# Copy new JAR
sudo cp target/bankapp-*.jar /opt/bankapp/bankapp.jar
sudo chown bankapp:bankapp /opt/bankapp/bankapp.jar

# Restart app (systemd handles graceful shutdown)
sudo systemctl restart bankapp

# Verify
sudo systemctl status bankapp
```
