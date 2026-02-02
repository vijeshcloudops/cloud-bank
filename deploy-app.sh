#!/bin/bash
# Bank Application Deployment Script for On-Premise VMs

set -e

# Configuration
APP_NAME="bankapp"
APP_USER="bankapp"
APP_GROUP="bankapp"
APP_HOME="/opt/bankapp"
APP_PORT=8080
AGENT_JAR_NAME="middleware-javaagent-1.3.0.jar"
# Optional: provide a URL to download the agent if it's not present in the repo
# e.g. AGENT_JAR_URL="https://example.com/path/to/middleware-javaagent-1.3.0.jar"
AGENT_JAR_URL=""
MYSQL_HOST="localhost"  # Change to MySQL server IP if on different VM
MYSQL_PORT=3306
MYSQL_DB="bankappdb"
MYSQL_USER="bankappuser"
MYSQL_PASSWORD="BankApp@2024"

echo "=========================================="
echo "Bank Application Deployment Setup"
echo "=========================================="

# 1. Ensure Java 17 is installed and set as default
echo "Checking Java version..."
JAVA_OK=false
if command -v java >/dev/null 2>&1; then
    if java -version 2>&1 | grep -q 'version \"17\|openjdk version \"17'; then
        JAVA_OK=true
    fi
fi
if [ "$JAVA_OK" = false ]; then
    echo "Installing Java 17..."
    sudo apt update
    sudo apt install -y openjdk-17-jdk
    # try to set alternatives to the installed JDK
    if [ -d /usr/lib/jvm/java-17-openjdk-amd64 ]; then
        sudo update-alternatives --set java /usr/lib/jvm/java-17-openjdk-amd64/bin/java || true
        sudo update-alternatives --set javac /usr/lib/jvm/java-17-openjdk-amd64/bin/javac || true
    fi
else
    echo "Java is already installed: $(java -version 2>&1 | head -n 1)"
fi

# Export JAVA_HOME and ensure it's on PATH so Maven uses Java 17
if [ -d /usr/lib/jvm/java-17-openjdk-amd64 ]; then
    export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
else
    export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java))))
fi
export PATH=$JAVA_HOME/bin:$PATH
echo "Using Java: $($JAVA_HOME/bin/java -version 2>&1 | head -n 1)"

# 2. Create application user
if ! id "$APP_USER" &>/dev/null; then
    echo "Creating application user..."
    sudo useradd -r -m -d "$APP_HOME" -s /bin/bash "$APP_USER"
fi

# 3. Create application directory
echo "Setting up application directory..."
sudo mkdir -p "$APP_HOME"
sudo chown -R "$APP_USER:$APP_GROUP" "$APP_HOME"

# 4. Build the application
echo "Building application with Maven (using JAVA_HOME=$JAVA_HOME)..."
mvn clean package -DskipTests

# 5. Copy JAR to application directory
echo "Deploying application JAR..."
sudo cp target/*.jar "$APP_HOME/$APP_NAME.jar"
sudo chown "$APP_USER:$APP_GROUP" "$APP_HOME/$APP_NAME.jar"

# 5.1 Ensure middleware agent JAR is present in $APP_HOME
echo "Ensuring middleware agent JAR is present..."
if [ -f "$APP_HOME/$AGENT_JAR_NAME" ]; then
    echo "Agent already present at $APP_HOME/$AGENT_JAR_NAME"
else
    # If the agent exists in the repository root, copy it
    if [ -f "$PWD/$AGENT_JAR_NAME" ]; then
        echo "Copying agent from repository to $APP_HOME"
        sudo cp "$PWD/$AGENT_JAR_NAME" "$APP_HOME/"
        sudo chown "$APP_USER:$APP_GROUP" "$APP_HOME/$AGENT_JAR_NAME"
    elif [ -n "$AGENT_JAR_URL" ]; then
        echo "Downloading agent from $AGENT_JAR_URL"
        sudo curl -fSL -o "$APP_HOME/$AGENT_JAR_NAME" "$AGENT_JAR_URL"
        sudo chown "$APP_USER:$APP_GROUP" "$APP_HOME/$AGENT_JAR_NAME"
    else
        echo "ERROR: $AGENT_JAR_NAME not found in repository and AGENT_JAR_URL is not set."
        echo "Place $AGENT_JAR_NAME in the project root or set AGENT_JAR_URL in deploy-app.sh and re-run."
        exit 1
    fi
fi

# 6. Create systemd service file
echo "Creating systemd service..."
sudo tee /etc/systemd/system/${APP_NAME}.service > /dev/null <<EOF
[Unit]
Description=Bank Application
After=network.target mysql.service
Wants=mysql.service

[Service]
Type=simple
User=$APP_USER
WorkingDirectory=$APP_HOME
ExecStart=/bin/sh -c 'MW_API_KEY=your-api-key /usr/bin/java -javaagent:$APP_HOME/$AGENT_JAR_NAME \\
    -Dotel.service.name="java-springboot-service" \\
    -Dotel.resource.attributes=project.name="java-springboot-project" \\
    -jar $APP_HOME/$APP_NAME.jar'
Restart=on-failure
RestartSec=10
StandardOutput=journal
StandardError=journal
SyslogIdentifier=bankapp

[Install]
WantedBy=multi-user.target
EOF

# 7. Reload systemd and enable service
echo "Enabling and starting service..."
sudo systemctl daemon-reload
sudo systemctl enable ${APP_NAME}.service

# 8. Create log rotation
sudo tee /etc/logrotate.d/${APP_NAME} > /dev/null <<EOF
/var/log/${APP_NAME}/*.log {
    daily
    rotate 7
    compress
    delaycompress
    missingok
    create 0640 ${APP_USER} ${APP_GROUP}
}
EOF

echo "=========================================="
echo "Deployment setup completed!"
echo "=========================================="
echo ""
echo "Next steps:"
echo "1. Update MySQL host in the systemd service if using remote MySQL:"
echo "   sudo nano /etc/systemd/system/${APP_NAME}.service"
echo ""
echo "2. Start the application:"
echo "   sudo systemctl start ${APP_NAME}"
echo ""
echo "3. Check application status:"
echo "   sudo systemctl status ${APP_NAME}"
echo ""
echo "4. View logs:"
echo "   sudo journalctl -u ${APP_NAME} -f"
echo ""
echo "5. Access application:"
echo "   http://localhost:${APP_PORT}/bankapp"
