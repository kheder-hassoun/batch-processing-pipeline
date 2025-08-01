package me.spark;

import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.FilterFunction;
import org.apache.spark.api.java.function.MapFunction;
import org.apache.spark.sql.*;
import org.apache.spark.sql.expressions.Window;
import org.apache.spark.sql.expressions.WindowSpec;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.apache.spark.sql.functions.*;

public class IncrementalAutocomplete {

    public static void main(String[] args) throws Exception {

        if (args.length < 6) {
            System.err.println("Usage: IncrementalAutocomplete "
                    + "<hdfsLogsPath> <logfile> <jdbcUrl> "
                    + "<dbTableFreq> <dbTableTopK> <topK>");
            System.exit(1);
        }

        final String logsBasePath = args[0];
        final String logFile = args[1];
        final String jdbcUrl = args[2];
        final String freqTable = args[3];
        final String topKTable = args[4];
        final int topK = Integer.parseInt(args[5]);

        // Load config.properties
        Properties props = new Properties();
        try (InputStream input = IncrementalAutocomplete.class
                .getClassLoader()
                .getResourceAsStream("config.properties")) {
            if (input == null) {
                throw new RuntimeException("config.properties not found in resources");
            }
            props.load(input);
        }

        final String sparkMaster = props.getProperty("spark.master", "local[2]");
        final String driverCores = props.getProperty("spark.driver.cores", "2");
        final String executorCores = props.getProperty("spark.executor.cores", "2");

        final String dbUser = props.getProperty("jdbc.user");
        final String dbPassword = props.getProperty("jdbc.password");
        final String jdbcDriver = props.getProperty("jdbc.driver");

        SparkSession spark = SparkSession.builder()
                .appName("Incremental Autocomplete")
                .master(sparkMaster)
                .config("spark.driver.cores", driverCores)
                .config("spark.executor.cores", executorCores)
                .getOrCreate();

        // 1. Read & NORMALISE raw logs
        String path = String.format("%s/%s.txt", logsBasePath, logFile);
        Dataset<String> lines = spark.read().textFile(path)
                .filter((FilterFunction<String>) line ->
                        line != null && line.trim().length() >= 2)
                .map((MapFunction<String, String>) raw ->
                        raw.trim().toLowerCase(), Encoders.STRING());

        // 2. Build (prefix, query, 1) tuples
        JavaRDD<Row> pairs = lines.javaRDD().flatMap(query -> {
            int maxLen = Math.min(query.length(), 60);
            List<Row> out = new ArrayList<>();
            for (int L = 2; L <= maxLen; L++) {
                out.add(RowFactory.create(query.substring(0, L), query, 1));
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

        // 3. Load & merge with existing cumulative frequencies
        Dataset<Row> existing = spark.read()
                .format("jdbc")
                .option("url", jdbcUrl)
                .option("dbtable", freqTable)
                .option("user", dbUser)
                .option("password", dbPassword)
                .option("driver", jdbcDriver)
                .load();

        Dataset<Row> merged = existing
                .select(col("prefix"), col("query"),
                        col("frequency").alias("old_freq"))
                .unionByName(newCounts
                        .select(col("prefix"), col("query"), col("new_freq").alias("old_freq")))
                .groupBy("prefix", "query")
                .agg(sum("old_freq").alias("frequency"))
                .withColumn("last_updated", current_timestamp());

        // 4. Persist cumulative frequencies
        merged.write()
                .format("jdbc")
                .option("url", jdbcUrl)
                .option("dbtable", freqTable)
                .option("user", dbUser)
                .option("password", dbPassword)
                .option("driver", jdbcDriver)
                .mode(SaveMode.Overwrite)
                .save();

        // 5. Compute top‑K suggestions per prefix
        WindowSpec w = Window.partitionBy("prefix").orderBy(col("frequency").desc());

        Dataset<Row> topKPerPrefix = merged
                .withColumn("rank", row_number().over(w))
                .filter(col("rank").leq(topK))
                .groupBy("prefix")
                .agg(collect_list("query").alias("completions"))
                .withColumn("completions_json", to_json(col("completions")))
                .withColumn("last_updated", current_timestamp())
                .select(
                        col("prefix"),
                        col("completions_json").alias("completions"),
                        col("last_updated")
                );

        // 6. Persist top‑K table
        topKPerPrefix.write()
                .format("jdbc")
                .option("url", jdbcUrl)
                .option("dbtable", topKTable)
                .option("user", dbUser)
                .option("password", dbPassword)
                .option("driver", jdbcDriver)
                .mode(SaveMode.Overwrite)
                .save();

        spark.stop();
    }
}
