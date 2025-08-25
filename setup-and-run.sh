#!/bin/bash

echo "=== Elastic APM Java Agent Setup ==="

# 1. Stáhni APM agent jar
echo "Downloading Elastic APM Java Agent..."
curl -o 'elastic-apm-agent.jar' -L 'https://oss.sonatype.org/service/local/artifact/maven/redirect?r=releases&g=co.elastic.apm&a=elastic-apm-agent&v=1.50.0'

if [ ! -f "elastic-apm-agent.jar" ]; then
    echo "ERROR: Failed to download APM agent jar"
    exit 1
fi

echo "APM agent jar downloaded successfully: $(ls -lh elastic-apm-agent.jar)"

# 2. Build aplikace
echo "Building application..."

# Zkus nejdřív Maven wrapper, pak globální Maven
if [ -f "./mvnw" ]; then
    echo "Using Maven wrapper..."
    ./mvnw clean package -DskipTests
elif command -v mvn >/dev/null 2>&1; then
    echo "Using global Maven..."
    mvn clean package -DskipTests
else
    echo "ERROR: Neither Maven wrapper nor global Maven found!"
    echo "Please install Maven or add Maven wrapper to your project"
    exit 1
fi

if [ $? -ne 0 ]; then
    echo "ERROR: Maven build failed"
    exit 1
fi

# 3. Spusť aplikaci s APM agentem
echo "Starting application with Elastic APM agent..."

java -javaagent:./elastic-apm-agent.jar \
     -Delastic.apm.service_name=kafka-producer-java \
     -Delastic.apm.application_packages=com.example.kafkaapmdemo \
     -Delastic.apm.server_url=http://localhost:8200 \
     -Delastic.apm.environment=local \
     -Delastic.apm.log_level=INFO \
     -Delastic.apm.capture_body=all \
     -Delastic.apm.capture_headers=true \
     -Delastic.apm.enable_log_correlation=true \
     -Delastic.apm.trace_methods="com.example.kafkaapmdemo.*" \
     -Delastic.apm.trace_methods_duration_threshold=0ms \
     -jar target/kafka-producer-java-1.0.0.jar

echo "Application stopped."