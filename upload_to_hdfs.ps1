$files = Get-ChildItem -Path "input-data" -Filter "*.txt"
foreach ($file in $files) {
    $filename = $file.Name
    docker cp "input-data\$filename" namenode:/$filename
    docker exec -i namenode hdfs dfs -mkdir -p /logs
    docker exec -i namenode hdfs dfs -put -f "/$filename" /logs/
}
