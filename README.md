# 实验一 大数据系统基本实验

实现 Linux 与 Hadoop 基础操作、HDFS Java API 和 Shell 操作，以及三个 Hadoop MapReduce 程序。

## 文件

- `src/lab/HdfsCli.java`：上传追加或覆盖、重名下载、查看内容、元数据、递归遍历、文件与目录创建删除、首尾追加、移动。
- `src/lab/LabJobs.java`：合并去重、整数升序排名、child-parent 自连接得到祖孙关系。
- `scripts/linux_basics.sh`：题目给出的 Linux 操作，包含交互式 more 的伪终端翻页。
- `scripts/hdfs_ops.sh`：使用 Hadoop Shell 实现同样的 HDFS 功能。
- `tests/integration.py`：通过真实 HDFS 与真实 MapReduce 的命令行进行断言。
- `data/`：实验文档中的原始样例。
- `.github/workflows/experiment.yml`：Ubuntu 环境自动执行并保存原始日志及结果。

## 运行环境和复现

使用 Ubuntu 22.04、Java 11、Apache Hadoop 3.4.1。NameNode 和 DataNode 分别启动，副本数为 1；MapReduce 使用 Hadoop LocalJobRunner，输入输出位于 HDFS。本实验未启动 YARN，不是多机集群性能实验。

最方便的复现方式是 GitHub Actions 的 **Hadoop experiment → Run workflow**。自动化脚本创建临时 hadoop 用户，下载 Apache 官方二进制并验证 SHA-512，然后完成全部实验。运行结束后下载 `experiment-evidence` 构件，可查看 `verification.json`、`full-run.log`、`commands.log`、三个结果表以及编译好的 jar。

本地复现应使用全新的 Linux 虚拟机。脚本包含题目要求的 `/usr`、`/tmp` 和根目录操作；不要在生产系统中运行。先安装 Java 11，将 Hadoop 安装到 `/usr/local/hadoop` 并交给 hadoop 用户，允许该用户执行实验所需的 sudo 命令，然后执行：

```bash
export JAVA_HOME=/path/to/jdk11
export HADOOP_HOME=/usr/local/hadoop
export PATH="$HADOOP_HOME/bin:$JAVA_HOME/bin:$PATH"
bash scripts/run_all.sh
```

配置脚本拒绝格式化已存在的 Hadoop 数据目录。Linux 脚本遇到已有练习路径也会停止。

## 使用方式

```bash
bash scripts/build.sh
hadoop jar build/lab1.jar lab.HdfsCli help
hadoop jar build/lab1.jar lab.HdfsCli upload local.txt /user/hadoop/demo.txt append
hadoop jar build/lab1.jar lab.HdfsCli download /user/hadoop/demo.txt ./downloads
hadoop jar build/lab1.jar lab.HdfsCli add /user/hadoop/demo.txt prefix.txt begin
bash scripts/hdfs_ops.sh upload local.txt /user/hadoop/demo.txt overwrite
hadoop jar build/lab1.jar lab.LabJobs dedup /input/dedup /output/dedup
hadoop jar build/lab1.jar lab.LabJobs sort /input/sort /output/sort
hadoop jar build/lab1.jar lab.LabJobs family /input/family /output/family
```

输出目录必须不存在；不自动删除历史作业输出。Java 程序支持 Hadoop 通用参数 `-fs`、`-conf`。

## 设计说明

- 合并去重以规范化后的两个字段为键，消除样例中空格数量不同的影响，每键只输出一条记录。
- 整数排序使用 `IntWritable` 数值比较器和一个 Reducer。重复数仍分别占据连续位次；不能将 reducer 数量直接改成多个，否则位次不再全局连续。
- 祖孙关系按中间父辈姓名连接，分别收集孩子与父母并输出笛卡尔积。样例得到 12 条关系；输出顺序按中间键分组，可与参考答案按集合比较。跨不同中间人产生的同一祖孙对不做全局去重，如需集合语义可增加第二个去重作业。
- HDFS `FileStatus` 没有原生创建时间字段。`mtime` 明确标为修改时间；本工具新建文件另用 `user.lab.createdAt` 扩展属性记录应用创建时间。外部创建且没有此属性的文件显示 UNKNOWN，不能把修改时间冒充创建时间。
- 开头追加需要复制原文件。Java 用 `FileContext.rename(..., OVERWRITE)` 替换暂存文件；Shell 使用备份和恢复步骤，两个重命名之间有短暂窗口。实验为单写者，不保证并发写入安全。实现保留普通权限及已记录创建时间，未实现完整 ACL 与全部扩展属性迁移。
- 删除文件拒绝目录；目录删除使用非递归语义；重名下载自动使用 `_1`、`_2` 后缀，保留原文件。

## 参考文档

- [Apache Hadoop 单节点配置](https://hadoop.apache.org/docs/r3.4.1/hadoop-project-dist/hadoop-common/SingleCluster.html)
- [Hadoop FileSystem Shell](https://hadoop.apache.org/docs/r3.4.1/hadoop-project-dist/hadoop-common/FileSystemShell.html)
- [FileStatus Java API](https://hadoop.apache.org/docs/r3.4.1/api/org/apache/hadoop/fs/FileStatus.html)
- [MapReduce Tutorial](https://hadoop.apache.org/docs/r3.4.1/hadoop-mapreduce-client/hadoop-mapreduce-client-core/MapReduceTutorial.html)

运行结果以 Actions 日志和构件为准。报告中的个人身份信息不应上传到公开代码仓库。
