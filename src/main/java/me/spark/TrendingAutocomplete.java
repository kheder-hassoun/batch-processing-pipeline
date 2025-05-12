package me.spark;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.sql.*;
import org.apache.spark.sql.expressions.WindowSpec;
import org.apache.spark.sql.expressions.Window;
import org.apache.spark.sql.types.*;
import org.apache.spark.sql.functions.*;

import java.util.ArrayList;
import java.util.List;

public class TrendingAutocomplete {

    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("Usage: TrendingAutocomplete <inputPath> <topK> <kafkaBootstrapServers>");
            System.exit(1);
        }

        String inputPath = args[0]; // e.g., hdfs://namenode:8020/logs/queries.txt
        int topK = Integer.parseInt(args[1]); // e.g., 5
        String kafkaBootstrap = args[2]; // e.g., kafka:9092

        SparkSession spark = SparkSession.builder()
                .appName("Trending Autocomplete Pipeline")
                .getOrCreate();

        // STEP 1: Read queries from HDFS
        JavaRDD<String> queries = spark.read()
                .textFile(inputPath)
                .javaRDD()
                .filter(line -> line != null && line.trim().length() >= 2);
        // STEP 2: Generate (prefix, fullQuery) pairs
        JavaRDD<Row> prefixPairs = queries.flatMap(line -> {
            String q = line.trim();
            int maxLen = Math.min(q.length(), 60);
            List<Row> out = new ArrayList<>(maxLen - 1);
            for (int L = 2; L <= maxLen; L++) {
                out.add(RowFactory.create(q.substring(0, L), q));
            }
            return out.iterator();
        });

        // Define schema
        StructType schema = new StructType(new StructField[]{
                new StructField("prefix", DataTypes.StringType, false, Metadata.empty()),
                new StructField("query", DataTypes.StringType, false, Metadata.empty())
        });

        Dataset<Row> df = spark.createDataFrame(prefixPairs, schema);

        // STEP 3: Count frequencies
        Dataset<Row> counted = df.groupBy("prefix", "query")
                .count();

        // STEP 4: Rank and keep top K
        WindowSpec w = Window.partitionBy("prefix").orderBy(functions.col("count").desc());
        Dataset<Row> ranked = counted
                .withColumn("rank", functions.row_number().over(w))
                .filter(functions.col("rank").leq(topK))
                .drop("rank");

        // STEP 5: Aggregate completions per prefix
        Column completionStruct = functions.struct(
                functions.col("query").alias("query"),
                functions.col("count").alias("frequency"),
                functions.current_timestamp().alias("last_updated")
        );

        Dataset<Row> aggregated = ranked.groupBy("prefix")
                .agg(functions.collect_list(completionStruct).alias("completions"));

        // STEP 6: Write to Kafka
        // Prepare key/value strings (Kafka needs both as strings)
        Dataset<Row> kafkaReady = aggregated.select(
                functions.col("prefix").alias("key"),
                functions.to_json(functions.struct(
                        functions.col("prefix"),
                        functions.col("completions")
                )).alias("value")
        );

        kafkaReady.write()
                .format("kafka")
                .option("kafka.bootstrap.servers", kafkaBootstrap)
                .option("topic", "trending-prefixes")
                .save();

        spark.stop();
    }
}
