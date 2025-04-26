package me.spark;


import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.sql.*;
import static org.apache.spark.sql.functions.*;

public class WordCount {
    public static void main(String[] args) {
        SparkSession spark = SparkSession.builder()
                .appName("Word Count Pipeline")
                .master("local[*]") // Use all local cores
                .getOrCreate();

        // Load raw text file
        Dataset<String> lines = spark.read().textFile("input_data/documents.txt");

        // Clean and split into words
        Dataset<String> words = lines
                .flatMap((FlatMapFunction<String, String>) line ->
                        java.util.Arrays.asList(line.toLowerCase().split("\\W+")).iterator(), Encoders.STRING());

        // Group and count
        Dataset<Row> wordCounts = words.groupBy("value").count().orderBy(desc("count"));

        // Show sample
        wordCounts.show();

        // Save as key-value pairs (text file output or DB write here)
        wordCounts.write().format("csv").mode("overwrite").save("output_data/word_counts");

        spark.stop();
    }
}