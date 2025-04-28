package me.spark;

import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.sql.*;
import org.apache.spark.sql.expressions.Window;
import org.apache.spark.sql.expressions.WindowSpec;

import static org.apache.spark.sql.functions.*;

import java.util.Arrays;

public class WordCount {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: WordCount <inputPath> <outputCollection>");
            System.exit(1);
        }

        String inputPath = args[0];        // e.g. hdfs://namenode:8020/input/sample.txt
        String outputCollection = args[1]; // e.g. mymongo.prefix_collection

        SparkSession spark = SparkSession.builder()
                .appName("PrefixAutocompletePipeline")
                .config("spark.mongodb.write.connection.uri", "mongodb://mongodb:27017/" + outputCollection)
                .getOrCreate();

        // STEP 1: Read Text File
        Dataset<String> lines = spark.read().textFile(inputPath);

        // STEP 2: Tokenize lines into words
        Dataset<String> words = lines
                .flatMap((FlatMapFunction<String, String>) line ->
                        Arrays.asList(line.toLowerCase().split("\\W+")).iterator(), Encoders.STRING())
                .filter(length(col("value")).gt(2)); // Filter very short noise

        // STEP 3: Create prefixes (2-char and 3-char) for each word
        Dataset<Row> prefixes = words.withColumn("word", col("value"))
                .withColumn("prefix2", substring(col("word"), 1, 2))
                .withColumn("prefix3", substring(col("word"), 1, 3))
                .selectExpr("prefix2 as prefix", "word")
                .union(
                        words.withColumn("word", col("value"))
                                .withColumn("prefix3", substring(col("word"), 1, 3))
                                .selectExpr("prefix3 as prefix", "word")
                );

        // STEP 4: Group by prefix and word, count frequency
        Dataset<Row> counted = prefixes.groupBy("prefix", "word")
                .count();

        // STEP 5: Rank top 5 words for each prefix
        WindowSpec prefixWindow = Window.partitionBy("prefix").orderBy(col("count").desc());

        Dataset<Row> topK = counted.withColumn("rank", row_number().over(prefixWindow))
                .filter(col("rank").leq(5))
                .drop("rank");

        // STEP 6: Write to MongoDB
        topK.write()
                .format("mongodb")
                .mode("overwrite")
                .save();

        spark.stop();
    }
}

