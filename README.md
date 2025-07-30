# Autocomplete Batch Processing Pipeline (Kubernetes Edition)

## Overview

This project implements a batch processing pipeline for autocomplete suggestions using Apache Spark and HDFS. It periodically processes query logs, computes top-K suggestions, stores intermediate results in MySQL, and publishes updates to Redis via Debezium and Kafka.

The new version replaces the previous Docker Compose setup with a production-ready **Kubernetes CronJob deployment**.

---

##  Tech Stack

* **Apache Spark** – Batch processing engine
* **HDFS** – Stores query logs
* **MySQL** – Intermediate structured storage
* **Debezium + Kafka** – Change capture and streaming
* **Redis Cluster** – Serving layer for suggestions
* **Kubernetes CronJob** – Scheduled execution every 5 minutes

---

##  CronJob Configuration

```yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: autocomplete-batch-5min
  namespace: kh-pipeline
spec:
  schedule: "*/5 * * * *"
  timeZone: "Europe/Amsterdam"
  concurrencyPolicy: Forbid
  startingDeadlineSeconds: 240
  successfulJobsHistoryLimit: 2
  failedJobsHistoryLimit: 2
  jobTemplate:
    spec:
      backoffLimit: 1
      ttlSecondsAfterFinished: 1800
      activeDeadlineSeconds: 240
      template:
        metadata:
          labels:
            app: autocomplete-batch
        spec:
          restartPolicy: Never
          containers:
            - name: driver
              image: 172.29.3.41:5000/autocomplete-job:1.2
              imagePullPolicy: IfNotPresent
              args:
                - "hdfs://hadoop-hadoop-hdfs-nn.kh-pipeline.svc.cluster.local:9000/logs"
                - "logs"
                - "jdbc:mysql://mysql-svc.kh-pipeline.svc.cluster.local:3306/autocomplete?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                - "prefix_query_frequency"
                - "prefix_suggestions"
                - "10"  # Top-K
```

---

##  Pipeline Architecture

```text
┌─────────────┐     ┌───────────────┐     ┌──────────┐     ┌───────────┐     ┌──────────────┐
│ HDFS LOGS   │───▶│ SPARK BATCH   │───▶│ MySQL DB │───▶│ DEBEZIUM  │───▶│     KAFKA      │
└─────────────┘     └───────────────┘     └──────────┘     └───────────┘     └──────────────┘
      ▲                                         │               ▲                         │
      └─────────────────────────────────────────┘               └─────────────────────────┘
                                      ▼                                       ▼
                            ┌────────────────────┐             ┌──────────────────────┐
                            │ prefix_query_freq  │             │ prefix_suggestions   │
                            └────────────────────┘             └──────────────────────┘
                                       │                                     ▼
                                       └──────────────────────────────▶ REDIS CLUSTER
```

---

##  Pipeline Behavior

1. **Input**: Logs are periodically placed into HDFS under `/logs/YYYY-MM-DD-HH.txt`
2. **Batch Execution**:

   * Spark reads recent logs (last 24 hours)
   * Updates `prefix_query_frequency` in MySQL
   * Computes top-K completions and writes to `prefix_suggestions`
3. **Debezium** monitors MySQL → streams changes into Kafka topics
4. **Redis** reflects latest suggestions via Kafka consumers

---

## ✅ Verification Checklist

* ✅ Logs uploaded to HDFS: `/logs/`
* ✅ Spark batch job finishes within 4 minutes
* ✅ MySQL tables updated:

  * `prefix_query_frequency`
  * `prefix_suggestions`
* ✅ Kafka topic receives updates: `autocomplete.prefix_suggestions`
* ✅ Redis cluster reflects new completions

---

##  Monitoring & Debugging

* View recent CronJob history:

```bash
kubectl get cronjobs -n kh-pipeline
kubectl get jobs -n kh-pipeline
```

* Check logs of last run:

```bash
kubectl logs job/<job-name> -n kh-pipeline
```

* Kafka topic listener:

```bash
kafka-console-consumer --bootstrap-server kafka:9092 \
  --topic autocomplete.prefix_suggestions --from-beginning
```

---

##  Legacy Cleanup

To clean up previous Docker Compose remnants:

```bash
docker compose down --volumes
docker volume rm $(docker volume ls -q)
```

---

##  Notes

* Schedule is localized to Amsterdam time zone and runs every 5 minutes.
* The container is stateless and terminates after each execution.
* Uses `activeDeadlineSeconds` and `startingDeadlineSeconds` to enforce runtime bounds.
* Ensure access to HDFS and MySQL via Kubernetes services.

---

##  Future Enhancements

* Integrate Prometheus/Grafana for batch job metrics
* Implement retry-on-failure via Spark checkpointing
* Add Slack alerting on job failure
* Auto scale batch job based on log volume

---

## 📄 License

kheder khdrhswn32@gmail.com 
