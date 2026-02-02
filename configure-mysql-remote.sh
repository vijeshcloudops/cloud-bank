#!/bin/bash
# MySQL Configuration for On-Premise Deployment
# This allows remote connections from other VMs

echo "Configuring MySQL for remote connections..."

# Backup original MySQL configuration
sudo cp /etc/mysql/mysql.conf.d/mysqld.cnf /etc/mysql/mysql.conf.d/mysqld.cnf.backup

# Update bind-address to allow all connections
# Comment out the bind-address line or set it to 0.0.0.0
sudo sed -i 's/bind-address.*/bind-address = 0.0.0.0/' /etc/mysql/mysql.conf.d/mysqld.cnf

# Restart MySQL to apply changes
sudo systemctl restart mysql

echo "MySQL configuration updated!"
echo ""
echo "Note: If using a specific network, consider updating:"
echo "sudo nano /etc/mysql/mysql.conf.d/mysqld.cnf"
echo "And setting: bind-address = <your-network-ip>"
echo ""
echo "Verify MySQL is listening on all interfaces:"
sudo netstat -tlnp | grep mysql || sudo ss -tlnp | grep mysql
