#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p results
exec > >(tee results/full-run.log) 2>&1
date -u +%FT%TZ
id
uname -a
cat /etc/os-release
java -version
hadoop version
getconf _NPROCESSORS_ONLN
free -h
bash scripts/linux_basics.sh
bash scripts/setup_hadoop.sh
bash scripts/build.sh
python3 tests/integration.py
hdfs dfsadmin -report > results/hdfs-report.txt
hdfs fsck /user/hadoop > results/hdfs-fsck.txt
cat results/hdfs-fsck.txt
jps > results/java-processes.txt
echo 'EXPERIMENT COMPLETED'
