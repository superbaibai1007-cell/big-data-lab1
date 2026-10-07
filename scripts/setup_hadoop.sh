#!/usr/bin/env bash
set -euo pipefail
[[ $(id -un) == hadoop ]] || { echo 'Run as hadoop'; exit 1; }
: "${JAVA_HOME:?Set JAVA_HOME to a Java 11 JDK}"
: "${HADOOP_HOME:=/usr/local/hadoop}"
export HADOOP_HOME PATH="$HADOOP_HOME/bin:$PATH"
[[ ! -e "$HOME/hadoop-data" ]] || { echo 'Refusing to format existing Hadoop data'; exit 1; }
mkdir -p "$HOME/hadoop-data"
cat > "$HADOOP_HOME/etc/hadoop/hadoop-env.sh" <<EOF
export JAVA_HOME=$JAVA_HOME
export HADOOP_HEAPSIZE_MAX=512
export HADOOP_LOG_DIR=$HOME/hadoop-logs
EOF
cat > "$HADOOP_HOME/etc/hadoop/core-site.xml" <<'EOF'
<configuration>
 <property><name>fs.defaultFS</name><value>hdfs://localhost:9000</value></property>
</configuration>
EOF
cat > "$HADOOP_HOME/etc/hadoop/hdfs-site.xml" <<EOF
<configuration>
 <property><name>dfs.replication</name><value>1</value></property>
 <property><name>dfs.namenode.name.dir</name><value>file://$HOME/hadoop-data/name</value></property>
 <property><name>dfs.datanode.data.dir</name><value>file://$HOME/hadoop-data/data</value></property>
 <property><name>dfs.namenode.http-address</name><value>127.0.0.1:9870</value></property>
 <property><name>dfs.datanode.address</name><value>127.0.0.1:9866</value></property>
 <property><name>dfs.datanode.http.address</name><value>127.0.0.1:9864</value></property>
 <property><name>dfs.datanode.ipc.address</name><value>127.0.0.1:9867</value></property>
</configuration>
EOF
cat > "$HADOOP_HOME/etc/hadoop/mapred-site.xml" <<'EOF'
<configuration>
 <property><name>mapreduce.framework.name</name><value>local</value></property>
</configuration>
EOF
hdfs namenode -format -nonInteractive
hdfs --daemon start namenode
hdfs --daemon start datanode
timeout 90 hdfs dfsadmin -safemode wait
for n in $(seq 1 30); do
  if hdfs dfsadmin -report | grep -q 'Live datanodes (1)'; then break; fi
  sleep 2
done
hdfs dfsadmin -report | grep 'Live datanodes (1)'
jps
hdfs dfs -mkdir -p /user/hadoop/test
hdfs dfs -ls /user/hadoop
hdfs dfs -put ~/.bashrc /user/hadoop/test/
hdfs dfs -ls /user/hadoop/test
hdfs dfs -get /user/hadoop/test /usr/local/hadoop/
cmp ~/.bashrc /usr/local/hadoop/test/.bashrc
echo 'HADOOP BASIC ROUNDTRIP PASS'
