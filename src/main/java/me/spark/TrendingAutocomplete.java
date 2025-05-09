package me.spark;

import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.FilterFunction;
import org.apache.spark.sql.*;
import org.apache.spark.sql.expressions.Window;
import org.apache.spark.sql.expressions.WindowSpec;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.Metadata;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;

import java.util.ArrayList;
import java.util.List;

import static org.apache.spark.sql.functions.*;

public class TrendingAutocomplete {

    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("Usage: TrendingAutocomplete <inputPath> <mongoDatabase>.<mongoCollection> <topK>");
            System.exit(1);
        }

        String inputPath        = args[0]; //  hdfs://namenode:8020/logs/queries.txt
        String mongoNamespace   = args[1]; //  mymongo.autocomplete_prefixes
        int topK                = Integer.parseInt(args[2]); // e.g. 5

        SparkSession spark = SparkSession.builder()
                .appName("Trending Autocomplete Pipeline")
                // MongoDB write URI (read not needed here)
                .config("spark.mongodb.write.connection.uri", "mongodb://mongodb:27017/" + mongoNamespace)
                .getOrCreate();

        // STEP 1: Read queries from HDFS
        JavaRDD<String> queries = spark.read()
                .textFile(inputPath)
                .filter((FilterFunction<String>) line -> line != null && line.trim().length() >= 2)
                .javaRDD();

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
                new StructField("query",  DataTypes.StringType, false, Metadata.empty())
        });

        Dataset<Row> df = spark.createDataFrame(prefixPairs, schema);

        // STEP 3: Count frequencies of each (prefix, query)
        Dataset<Row> counted = df.groupBy("prefix", "query")
                .count();

        // STEP 4: Rank by frequency within each prefix, keep top K
        WindowSpec w = Window.partitionBy("prefix").orderBy(col("count").desc());
        Dataset<Row> ranked = counted
                .withColumn("rank", row_number().over(w))
                .filter(col("rank").leq(topK))
                .drop("rank");

        // STEP 5: Assemble completions array with timestamp
        // Use current_timestamp() for batch time
        Column completionStruct = struct(
                col("query").alias("query"),
                col("count").alias("frequency"),
                current_timestamp().alias("last_updated")
        );
        Dataset<Row> aggregated = ranked.groupBy("prefix")
                .agg(collect_list(completionStruct).alias("completions"));

        // STEP 6: Write to MongoDB
        aggregated.write()
                .format("mongodb.spark.sql.DefaultSource")
                .mode("overwrite")
                .save();

        spark.stop();
    }
}