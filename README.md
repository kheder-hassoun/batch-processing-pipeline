
## Project Description
This project reads a text file stored in **HDFS**, processes it using **Apache Spark**, extracts all possible 2-character and 3-character prefixes from the words, and stores the **top 5 most frequent words** for each prefix into a **MongoDB** collection.

It uses:
- **Spark** for parallel processing
- **HDFS** for file storage
- **MongoDB** to save the processed results
- **Docker Compose** to orchestrate the services

---

##  How to Run It

### 1. Start the Docker Environment

Make sure your **docker-compose.yml** (with HDFS, Spark, MongoDB) is up and running:

```bash
docker-compose up -d
```

✅ This will start:
- HDFS (`namenode`, `datanode`)
- Spark (`spark-master`, `spark-worker`)
- MongoDB

---

### 2. Copy and Upload File to HDFS

Copy your local `sample.txt` into the running **namenode** container:

```bash
docker cp input-data/sample.txt namenode:/sample.txt
```

Then move it into HDFS:

```bash
docker exec -it namenode hdfs dfs -mkdir -p /input
docker exec -it namenode hdfs dfs -put /sample.txt /input/
```

Verify that the file is uploaded:

```bash
docker exec -it namenode hdfs dfs -ls /input
docker exec -it namenode hdfs dfs -cat /input/sample.txt
```

✅ Now your text file is stored in HDFS at `/input/sample.txt`.

---

### 3. Submit the Spark Job

Run the Spark job inside the `spark-master` container:

```bash
docker exec -it spark-master /bin/bash
```

Inside the container, run:

```bash
/spark/bin/spark-submit \
  --master spark://spark-master:7077 \
  --packages org.mongodb.spark:mongo-spark-connector_2.12:10.1.1 \
  --class me.spark.WordCount \
  /opt/spark-apps/spark-hdfs.jar \
  hdfs://namenode:8020/input/sample.txt \
  mymongo.prefixcollection
```

✅ This command will:
- Read `sample.txt` from HDFS
- Parse it and extract prefixes
- Find the top 5 autocomplete suggestions for each prefix
- Save results into MongoDB (`mymongo.prefixcollection`)

---

### 4. Verify the MongoDB Results

Connect to your MongoDB container:

```bash
docker container exec -it mongodb mongosh
```

Then inside Mongo Shell:

```bash
show databases
use mymongo
show collections
db.prefixcollection.find()
```

✅ You will see documents like:

```json
{
  "_id": "he",
  "suggestions": ["hello", "help", "heat", "heavy", "hero"]
}
```

Each document represents a prefix (`"he"`) and its **top 5 most frequent words**.

---

## 🛠 Code Overview

The important file is `me.spark.WordCount.java`:
- Connects to MongoDB
- Reads text file from HDFS
- Splits words
- Extracts 2-character and 3-character prefixes
- Groups words by prefix
- Finds **top 5** most frequent words for each prefix
- Writes the result to MongoDB

---

## ⚡ Useful Commands Summary

| Task | Command |
|:-----|:--------|
| Start Docker services | `docker-compose up -d` |
| Copy sample.txt into namenode | `docker cp input-data/sample.txt namenode:/sample.txt` |
| Upload file to HDFS | `docker exec -it namenode hdfs dfs -put /sample.txt /input/` |
| Submit Spark job | `docker exec -it spark-master /bin/bash` then `spark-submit ...` |
| Connect to MongoDB | `docker exec -it mongodb mongosh` |

---

## 📢 Notes
- Make sure **MongoDB URI** in your code is `mongodb://mongodb:27017`.
- `spark-hdfs.jar` must already be copied into `/opt/spark-apps/` inside `spark-master` (use Dockerfile or manual copy).
- If you modify the code, rebuild the JAR and replace it.

