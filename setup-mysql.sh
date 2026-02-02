#!/bin/bash
# MySQL Installation and Setup Script for On-Premise Deployment

# Update system packages
sudo apt update
sudo apt upgrade -y

# Install MySQL Server 8.0
sudo apt install -y mysql-server

# Start MySQL service
sudo systemctl start mysql
sudo systemctl enable mysql

# Create database and user
sudo mysql -u root << EOF
-- Create database
CREATE DATABASE IF NOT EXISTS bankappdb CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Create application user (change password as needed)
CREATE USER IF NOT EXISTS 'bankappuser'@'%' IDENTIFIED BY 'BankApp@2024';
GRANT ALL PRIVILEGES ON bankappdb.* TO 'bankappuser'@'%';

-- Allow connections from all hosts (adjust % to specific IP if needed)
CREATE USER IF NOT EXISTS 'bankappuser'@'localhost' IDENTIFIED BY 'BankApp@2024';
GRANT ALL PRIVILEGES ON bankappdb.* TO 'bankappuser'@'localhost';

FLUSH PRIVILEGES;
EOF

echo "MySQL setup completed successfully!"
echo "Database: bankappdb"
echo "User: bankappuser"
echo "Password: BankApp@2024 (change this!)"
