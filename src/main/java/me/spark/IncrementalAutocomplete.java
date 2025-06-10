package me.spark;

import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.FilterFunction;
import org.apache.spark.sql.*;
import org.apache.spark.sql.expressions.Window;
import org.apache.spark.sql.expressions.WindowSpec;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;

import static org.apache.spark.sql.functions.*;


import java.util.ArrayList;
import java.util.List;

public class IncrementalAutocomplete {

    public static void main(String[] args) throws Exception {
        if (args.length < 6) {
            System.err.println("Usage: IncrementalAutocomplete <hdfsLogsPath> <hour> <jdbcUrl> <dbTableFreq> <dbTableTopK> <topK>");
            System.exit(1);
        }
        String logsBasePath = args[0];       // e.g. hdfs://namenode:8020/logs
        String hour = args[1];               // e.g. 2025-05-28-12
        String jdbcUrl = args[2];            // e.g. jdbc:mysql://mysql:3306/autocomplete
        String freqTable = args[3];          // prefix_query_frequency
        String topKTable = args[4];          // prefix_suggestions
        int topK = Integer.parseInt(args[5]);

        SparkSession spark = SparkSession.builder()
                .appName("Incremental Autocomplete")
                .getOrCreate();

        // 1. Read last-hour logs
        String path = String.format("%s/%s.txt", logsBasePath, hour);
        Dataset<String> lines = spark.read().textFile(path)
                .filter((FilterFunction<String>) line -> line != null && line.trim().length() >= 2);

        // 2. Generate (prefix, query, count=1) rows
        JavaRDD<Row> pairs = lines.javaRDD().flatMap(line -> {
            String q = line.trim();
            int maxLen = Math.min(q.length(), 60);
            List<Row> out = new ArrayList<>();
            for (int L = 2; L <= maxLen; L++) {
                out.add(RowFactory.create(q.substring(0, L), q, 1));
            }
            return out.iterator();
        });

        StructType schema = new StructType()
                .add("prefix", DataTypes.StringType)
                .add("query", DataTypes.StringType)
                .add("count", DataTypes.IntegerType);

        Dataset<Row> newCounts = spark.createDataFrame(pairs, schema)
                .groupBy("prefix", "query")
                .agg(sum("count").alias("new_freq"));

        // 3. Load existing cumulative frequencies
        Dataset<Row> existing = spark.read()
                .format("jdbc")
                .option("url", jdbcUrl)
                .option("dbtable", freqTable)
                .option("user", "root")
                .option("password", "root")
                .option("driver", "com.mysql.cj.jdbc.Driver")
                .load();

        // 4. Merge: sum existing.frequency + new_freq
        Dataset<Row> merged = existing.select(col("prefix"), col("query"), col("frequency").alias("old_freq"))
                .unionByName(newCounts.select(col("prefix"), col("query"), col("new_freq").alias("old_freq")))
                .groupBy("prefix", "query")
                .agg(sum("old_freq").alias("frequency"))
                .withColumn("last_updated", current_timestamp());

        // 5. Write back cumulative freq (overwrite table)
        merged.write()
                .format("jdbc")
                .option("url", jdbcUrl)
                .option("dbtable", freqTable)
                .option("user", "root")
                .option("password", "root")
                .option("driver", "com.mysql.cj.jdbc.Driver")
                .mode(SaveMode.Overwrite)
                .save();

        // 6. Compute top-K per prefix


        // Step 6: Compute top-K queries per prefix
        WindowSpec w = Window.partitionBy("prefix").orderBy(col("frequency").desc());

        Dataset<Row> topKPerPrefix = merged
                .withColumn("rank", row_number().over(w))
                .filter(col("rank").leq(topK))
                .select("prefix", "query")
                .groupBy("prefix")
                .agg(collect_list("query").alias("completions"))
                .withColumn("completions_json", to_json(col("completions")))
                .withColumn("last_updated", current_timestamp())
                .select(
                        col("prefix"),
                        col("completions_json").alias("completions"),
                        col("last_updated")
                );

        //this complex type cannot be writen to the db
//        WindowSpec w = Window.partitionBy("prefix").orderBy(col("frequency").desc());
//        Dataset<Row> topKdf = merged
//                .withColumn("rank", row_number().over(w))
//                .filter(col("rank").leq(topK))
//                .groupBy("prefix")
//                .agg(
//                        collect_list(
//                                struct(
//                                        col("query"),
//                                        col("frequency")
//                                )
//                        ).alias("completions")
//                )
//                .withColumn("last_updated", current_timestamp());

        // Step 7: Write top-K completions to DB
        topKPerPrefix.write()
                .format("jdbc")
                .option("url", jdbcUrl)
                .option("dbtable", topKTable)
                .option("user", "root")
                .option("password", "root")
                .option("driver", "com.mysql.cj.jdbc.Driver")
                .mode(SaveMode.Overwrite)
                .save();


        spark.stop();
    }
}
