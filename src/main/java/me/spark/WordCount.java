package me.spark;

import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.sql.*;
import static org.apache.spark.sql.functions.*;

public class WordCount {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: WordCount <inputPath> <outputPath>");
            System.exit(1);
        }

        SparkSession spark = SparkSession.builder()
                .appName("Word Count Pipeline")
                // Remove .master("local[*]") to use cluster mode
                .getOrCreate();

        // Load raw text file from HDFS
        Dataset<String> lines = spark.read().textFile(args[0]);

        // Clean and split into words
        Dataset<String> words = lines
                .flatMap((FlatMapFunction<String, String>) line ->
                                java.util.Arrays.asList(line.toLowerCase().split("\\W+")).iterator(),
                        Encoders.STRING());

        // Group and count
        Dataset<Row> wordCounts = words.groupBy("value").count().orderBy(desc("count"));

        // Show sample
        wordCounts.show();

        // Save output to HDFS
        wordCounts.write()
                .format("csv")
                .mode("overwrite")
                .save(args[1]);

        spark.stop();
    }
}