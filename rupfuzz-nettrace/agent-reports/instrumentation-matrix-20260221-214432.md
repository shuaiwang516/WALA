# NetTrace Prebuild Instrumentation Matrix Report

- Timestamp: 2026-02-21T21:43:58-06:00
- Script: `rupfuzz-nettrace/scripts/instrument_prebuild_matrix.sh`
- Summary CSV: `rupfuzz-nettrace/instrumented/source-summary.csv`
- Java alternatives discovered:
  - `java-1.8.0-openjdk-amd64 -> /usr/lib/jvm/java-1.8.0-openjdk-amd64`
  - `java-1.11.0-openjdk-amd64 -> /usr/lib/jvm/java-1.11.0-openjdk-amd64`
  - `java-1.17.0-openjdk-amd64 -> /usr/lib/jvm/java-1.17.0-openjdk-amd64`

## Overall Result

All 9 targets completed with:
- `instrument_status=ok`
- `build_status=ok`
- `package_status=ok`

## Per-Version Results

| Project | Version | Profile | JAVA_HOME | Output Tar | Status |
|---|---|---|---|---|---|
| cassandra | 3.11.19 | `profiles/cassandra-3.11.19.yaml` | `/usr/lib/jvm/java-1.8.0-openjdk-amd64` | `prebuild/cassandra/apache-cassandra-3.11.19-src-instrumented.tar.gz` | ok/ok/ok |
| cassandra | 4.1.10 | `profiles/cassandra-4.1.10.yaml` | `/usr/lib/jvm/java-1.11.0-openjdk-amd64` | `prebuild/cassandra/apache-cassandra-4.1.10-src-instrumented.tar.gz` | ok/ok/ok |
| cassandra | 5.0.6 | `profiles/cassandra-5.0.6.yaml` | `/usr/lib/jvm/java-1.17.0-openjdk-amd64` | `prebuild/cassandra/apache-cassandra-5.0.6-src-instrumented.tar.gz` | ok/ok/ok |
| hdfs | 2.10.2 | `profiles/hadoop-2.10.2.yaml` | `/usr/lib/jvm/java-1.8.0-openjdk-amd64` | `prebuild/hdfs/hadoop-2.10.2-src-instrumented.tar.gz` | ok/ok/ok |
| hdfs | 3.3.6 | `profiles/hadoop-3.3.6.yaml` | `/usr/lib/jvm/java-1.11.0-openjdk-amd64` | `prebuild/hdfs/hadoop-3.3.6-src-instrumented.tar.gz` | ok/ok/ok |
| hdfs | 3.4.2 | `profiles/hadoop-3.4.2.yaml` | `/usr/lib/jvm/java-1.11.0-openjdk-amd64` | `prebuild/hdfs/hadoop-3.4.2-src-instrumented.tar.gz` | ok/ok/ok |
| hbase | 2.5.13 | `profiles/hbase-2.5.13.yaml` | `/usr/lib/jvm/java-1.11.0-openjdk-amd64` | `prebuild/hbase/hbase-2.5.13-src-instrumented.tar.gz` | ok/ok/ok |
| hbase | 2.6.4 | `profiles/hbase-2.6.4.yaml` | `/usr/lib/jvm/java-1.11.0-openjdk-amd64` | `prebuild/hbase/hbase-2.6.4-src-instrumented.tar.gz` | ok/ok/ok |
| hbase | 3.0.0-beta-1 | `profiles/hbase-3.0.0-beta-1.yaml` | `/usr/lib/jvm/java-1.17.0-openjdk-amd64` | `prebuild/hbase/hbase-3.0.0-beta-1-src-instrumented.tar.gz` | ok/ok/ok |

## Injected Runtime Hook Locations

### Cassandra
- `cassandra-3.11.19`: 
  - `MessagingService#sendOneWay` (`recordSend`, `hit`)
  - `IncomingTcpConnection#receiveMessage` (`beginReceive`/`endReceive`)
- `cassandra-4.1.10` and `cassandra-5.0.6`:
  - `MessagingService#doSend` (`recordSend`, `hit`)
  - `InboundMessageHandler.ProcessMessage#run` (`beginReceive`/`endReceive`)
- Bridge file added:
  - `src/java/org/apache/cassandra/net/NetTraceRuntimeBridge.java`

### HDFS
- `Client#call` (`recordSend`, `hit`)
- `Server.Connection#processRpcRequest` (`beginReceive`/`endReceive` around queueing)
- Bridge file added:
  - `hadoop-common-project/hadoop-common/src/main/java/org/apache/hadoop/ipc/NetTraceRuntimeBridge.java`

### HBase
- `AbstractRpcClient#callMethod` (`recordSend`, `hit`)
- `ServerRpcConnection#processRequest` (`beginReceive`/`endReceive` around scheduler dispatch)
- Bridge file added:
  - `hbase-client/src/main/java/org/apache/hadoop/hbase/ipc/NetTraceRuntimeBridge.java`

## Output Artifact Sizes

- Cassandra:
  - `apache-cassandra-3.11.19-src-instrumented.tar.gz` ~39M
  - `apache-cassandra-4.1.10-src-instrumented.tar.gz` ~15M
  - `apache-cassandra-5.0.6-src-instrumented.tar.gz` ~24M
- HDFS:
  - `hadoop-2.10.2-src-instrumented.tar.gz` ~45M
  - `hadoop-3.3.6-src-instrumented.tar.gz` ~36M
  - `hadoop-3.4.2-src-instrumented.tar.gz` ~38M
- HBase:
  - `hbase-2.5.13-src-instrumented.tar.gz` ~36M
  - `hbase-2.6.4-src-instrumented.tar.gz` ~37M
  - `hbase-3.0.0-beta-1-src-instrumented.tar.gz` ~18M

## Logs

Per-target logs are under:
- `rupfuzz-nettrace/instrumented/source-logs`

