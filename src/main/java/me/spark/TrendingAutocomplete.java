package me.spark;

import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.sql.*;

import org.apache.spark.sql.expressions.Window;
import org.apache.spark.sql.expressions.WindowSpec;
import org.apache.spark.sql.types.*;
import org.apache.spark.sql.functions.*;

import java.util.ArrayList;
import java.util.List;

import static org.apache.spark.sql.functions.*;

public class TrendingAutocomplete {

    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("Usage: TrendingAutocomplete <inputPath> <kafkaBootstrapServers> <topK>");
            System.exit(1);
        }

        String inputPath = args[0];                    // e.g. hdfs://namenode:8020/logs/queries.txt
        String kafkaBootstrapServers = args[1];        // e.g. kafka:9092
        int topK = Integer.parseInt(args[2]);          // e.g. 5

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

        // Define schema for (prefix, query)
        StructType schema = new StructType(new StructField[]{
                new StructField("prefix", DataTypes.StringType, false, Metadata.empty()),
                new StructField("query", DataTypes.StringType, false, Metadata.empty())
        });

        Dataset<Row> df = spark.createDataFrame(prefixPairs, schema);

        // STEP 3: Count frequencies of each (prefix, query)
        Dataset<Row> counted = df.groupBy("prefix", "query")
                .count();

        // STEP 4: Rank by frequency within each prefix, keep top K
        WindowSpec w = Window.partitionBy("prefix").orderBy(col("count").desc());
        Dataset<Row> ranked = counted
                .withColumn("rank", functions.row_number().over(w))
                .filter(col("rank").leq(topK))
                .drop("rank");

        // STEP 5: Assemble completions as JSON
        Dataset<Row> grouped = ranked
                .groupBy("prefix")
                .agg(to_json(collect_list(struct(
                        col("query").alias("query"),
                        col("count").alias("frequency"),
                        current_timestamp().alias("last_updated")
                ))).alias("value"))
                .selectExpr("prefix as key", "value");

        // STEP 6: Write to Kafka
        grouped.write()
                .format("kafka")
                .option("kafka.bootstrap.servers", kafkaBootstrapServers)
                .option("topic", "autocomplete_prefixes")
                .save();

        spark.stop();
    }
}
