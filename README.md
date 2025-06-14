
# Autocomplete Batch Processing Pipeline

## Overview
This project sets up a Docker-based batch processing pipeline for an autocomplete system. It processes query logs, computes top suggestions, and synchronizes them to Redis using:
- **Spark** for batch processing
- **HDFS** for storage
- **MySQL** for intermediate data
- **Debezium/Kafka** for change capture
- **Redis Cluster** for serving suggestions

## Prerequisites
- Docker & Docker Compose
- Maven
- curl (for API requests)

---

## Step 1: Initial Setup
### 1.1 Build project
```bash
mvn clean package
```

### 1.2 Clean environment
```bash
docker compose down --volumes
docker volume rm $(docker volume ls -q)  # Remove existing volumes
docker compose build --no-cache
```

---

## Step 2: Start Containers
```bash
docker compose up -d
```

Wait 29 seconds for services to initialize.

---

## Step 3: Redis Cluster Setup
### 3.1 Create cluster
```bash
docker exec -it redis-node-1 redis-cli --cluster create \
  172.28.0.2:7000 172.28.0.3:7001 172.28.0.4:7002 \
  --cluster-replicas 0
```

### 3.2 Verify cluster
```bash
docker exec -it redis-node-1 sh
> redis-cli -c -p 7000
> CLUSTER NODES

```

---

## Step 4: MySQL Configuration
### 4.1 Create tables
```bash
docker exec -it mysql mysql -u root -proot
```

```sql
CREATE DATABASE autocomplete;
USE autocomplete;

-- Cumulative frequency table
CREATE TABLE prefix_query_frequency (
  prefix VARCHAR(60) NOT NULL,
  query VARCHAR(500) NOT NULL,
  frequency INT NOT NULL,
  last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (prefix, query)
) ENGINE=InnoDB;

-- Top-K suggestions table
CREATE TABLE prefix_suggestions (
  prefix VARCHAR(60) NOT NULL,
  completions JSON NOT NULL,
  last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (prefix)
) ENGINE=InnoDB;

-- Test record
INSERT INTO prefix_suggestions (prefix, completions) 
VALUES ('fudv', '["fudv hhhhhahaha"]');
```

---

## Step 5: Debezium Setup
### 5.1 Enable MySQL binary logging
Add to `my.cnf`:
```ini
[mysqld]
server-id=223344
log_bin=mysql-bin
binlog_format=ROW
```

### 5.2 Create Debezium connector
```bash
curl -X POST http://localhost:8083/connectors -H "Content-Type: application/json" -d '{
  "name": "mysql-autocomplete-connector",
  "config": {
    "connector.class": "io.debezium.connector.mysql.MySqlConnector",
    "database.hostname": "mysql",
    "database.port": "3306",
    "database.user": "root",
    "database.password": "root",
    "database.server.id": "223344",
    "database.server.name": "autocomplete",
    "database.include.list": "autocomplete",
    "table.include.list": "autocomplete.prefix_suggestions",
    "include.schema.changes": "false",
    "schema.history.internal.kafka.bootstrap.servers": "kafka:9092",
    "schema.history.internal.kafka.topic": "schema-changes.autocomplete",
    "topic.prefix": "autocomplete"
  }
}'
```

### 5.3 Verify Kafka topic
```bash
docker exec -it kafka bash
kafka-topics --bootstrap-server kafka:9092 --list
kafka-console-consumer --bootstrap-server kafka:9092 \
  --topic autocomplete.autocomplete.prefix_suggestions \
  --from-beginning
```

---

## Step 6: HDFS Data Upload
### 6.1 Create directory
```bash
docker exec -it namenode hdfs dfs -mkdir -p /logs
```

### 6.2 Upload sample data
```bash
docker cp input-data/2025-06-10-23.txt namenode:/queries.txt
docker exec -it namenode hdfs dfs -put /queries.txt /logs/
```

### 6.3 Verify upload
```bash
docker exec -it namenode hdfs dfs -ls /logs
docker exec -it namenode hdfs dfs -cat /logs/queries.txt
```

---

## Step 7: Run Spark Job
```bash
docker exec -it spark-master /spark/bin/spark-submit \
  --master spark://spark-master:7077 \
  --class me.spark.IncrementalAutocomplete \
  --jars /opt/spark/jars/mysql-connector-j-8.0.33.jar \
  /opt/spark-apps/spark-hdfs.jar \
  hdfs://namenode:8020/logs \
  queries \
  jdbc:mysql://mysql:3306/autocomplete \
  prefix_query_frequency \
  prefix_suggestions \
  10  # Top-K value
```

---

## Step 8: Verify Results
### 8.1 Check MySQL tables
```bash
docker exec -it mysql mysql -u root -proot
USE autocomplete;
SELECT * FROM prefix_suggestions;
```

### 8.2 Check Redis data
```bash
# Node 1
docker exec -it redis-node-1 redis-cli -c -p 7000 SCAN 0 COUNT 1000

# Node 2
docker exec -it redis-node-2 redis-cli -c -p 7001 SCAN 0 COUNT 1000

# Node 3
docker exec -it redis-node-3 redis-cli -c -p 7002 SCAN 0 COUNT 1000
```

---

## Pipeline Architecture
Data Pipeline:
````
┌─────────────┐     ┌───────────────┐     ┌──────────┐     ┌───────────┐     ┌──────────────┐
│ HDFS LOGS   │═══> │ SPARK BATCH   │═══> │ MySQL DB │═══> │ DEBEZIUM  │═══> │   KAFKA      │
└─────────────┘     └───────────────┘     └──────────┘     └───────────┘     └──────────────┘
      ▲                   ║                 (Temp Storage)   (Change Capture)   (Message Bus)
      ║                   ║
      ║            ┌──────╨──────┐                                ║
      ║            │  Frequency  │                                ║
      ║            │  Updates    │                                ║
      ║            └─────────────┘                                ║
      ╚════════════════════════════════════════════════════════════╝
                                  ║
                                  ║
                          ┌───────╨───────┐
                          │ REDIS CLUSTER │
                          │ (Serving Layer)│
                          └───────────────┘
````
````
HDFS → Spark → MySQL → Debezium → Kafka
▲        │                  ▲
│        └──→ Freq.Upd ──→ Redis
│                     │      ▲
└─────────────────────┘      │
Feedback Loop ───────┘
````
1. **HDFS**: Stores hourly query logs (`/logs/2025-05-28-01.txt`)
2. **Spark**:
    - Processes last 24 hours of logs
    - Updates `prefix_query_frequency` table
    - Computes top-K suggestions to `prefix_suggestions`
3. **Debezium**: Captures MySQL changes → Kafka
4. **Redis**: Serves autocomplete suggestions

---

## Troubleshooting
- **Spark failures**: Check container logs with `docker logs spark-master`
- **Debezium errors**: Ignore `Node -1 disconnected` logs unless data isn't flowing
- **Volume issues**: Re-run clean step if containers don't start properly
- **Permission errors**: On Windows, run PowerShell as admin

---

## Maintenance
- **Restart single service**:
  ```bash
  docker compose rm -sfv service-name && docker compose up -d --build service-name
  ```
- **Full rebuild**:
  ```bash
  docker compose down --volumes
  docker volume rm $(docker volume ls -q)
  docker compose build --no-cache
  docker compose up -d
  ```

> **Note**: Last tested on June 12, 2025 at 2:00 AM