#!/usr/bin/env bash
set -euo pipefail

# Build + instrument matrix for Cassandra / HDFS / HBase versions.
#
# Notes:
# - Build logic intentionally mirrors run_validation_matrix.sh.
# - Instrumentation uses a Shrike offline tool class (default: MethodTracer).
# - This script emits instrumented jars and a summary CSV.

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PREBUILD="$ROOT/rupfuzz-nettrace/prebuild/extracted"
LOG_BASE="$ROOT/rupfuzz-nettrace/instrumented/logs"
TMP_BASE="$ROOT/rupfuzz-nettrace/instrumented/input"
OUT_BASE="$ROOT/rupfuzz-nettrace/instrumented/output"
SUMMARY_CSV="$ROOT/rupfuzz-nettrace/instrumented/summary.csv"

JAVA8="/usr/lib/jvm/java-8-openjdk-amd64"
JAVA11="/usr/lib/jvm/java-11-openjdk-amd64"
JAVA17="/usr/lib/jvm/java-17-openjdk-amd64"

INSTRUMENTER_MAIN="${NETTRACE_INSTRUMENTER_MAIN:-com.ibm.wala.shrike.shrikeBT.shrikeCT.tools.MethodTracer}"
JAR_BIN="${NETTRACE_JAR_BIN:-$JAVA17/bin/jar}"
JAVA_BIN="${NETTRACE_JAVA_BIN:-$JAVA17/bin/java}"

join_by_colon() {
  local IFS=:
  echo "$*"
}

append_summary() {
  local project="$1"
  local version="$2"
  local module="$3"
  local input_jar="$4"
  local output_jar="$5"
  local build_status="$6"
  local instrument_status="$7"
  local note="$8"
  echo "$project,$version,$module,$input_jar,$output_jar,$build_status,$instrument_status,$note" >> "$SUMMARY_CSV"
}

build_nettrace_cli() {
  local log="$LOG_BASE/build-nettrace-cli.log"
  (cd "$ROOT" && JAVA_HOME="$JAVA17" ./gradlew :rupfuzz-nettrace:installDist -x test) >"$log" 2>&1
}

build_cassandra() {
  local version="$1"
  local dir="$PREBUILD/apache-cassandra-${version}-src"
  local log="$LOG_BASE/build-cassandra-${version}.log"
  local java_home="$JAVA11"
  if [[ "$version" == 3.* ]]; then
    java_home="$JAVA8"
  elif [[ "$version" == 5.* ]]; then
    java_home="$JAVA17"
  fi

  if [[ "$version" == 3.* ]]; then
    (cd "$dir" && JAVA_HOME="$java_home" ant -DskipTests=true -Dskip.test=true -Drat.skip=true jar) >"$log" 2>&1
  elif [[ "$version" == 4.* ]]; then
    (cd "$dir" && JAVA_HOME="$java_home" ant -Duse.jdk11=true -DskipTests=true -Dskip.test=true -Drat.skip=true _main-jar) >"$log" 2>&1
  else
    (cd "$dir" && JAVA_HOME="$java_home" ant -DskipTests=true -Dskip.test=true -Drat.skip=true _main-jar) >"$log" 2>&1
  fi
}

build_hdfs() {
  local version="$1"
  local dir="$PREBUILD/hadoop-${version}-src"
  local log="$LOG_BASE/build-hdfs-${version}.log"
  local java_home="$JAVA11"
  if [[ "$version" == 2.* ]]; then
    java_home="$JAVA8"
  fi
  (cd "$dir" && JAVA_HOME="$java_home" mvn -q -DskipTests -pl hadoop-common-project/hadoop-common,hadoop-hdfs-project/hadoop-hdfs,hadoop-hdfs-project/hadoop-hdfs-client -am compile) >"$log" 2>&1
}

build_hbase() {
  local version="$1"
  local dir="$PREBUILD/hbase-${version}"
  local log="$LOG_BASE/build-hbase-${version}.log"
  local java_home="$JAVA11"
  local modules="hbase-common,hbase-client,hbase-server,hbase-protocol-shaded,hbase-protocol"
  local -a mvn_flags=("-q" "-DskipTests" "-Denforcer.skip=true")
  if [[ "$version" == 3.* || "$version" == 4.* ]]; then
    java_home="$JAVA17"
    modules="hbase-common,hbase-client,hbase-server,hbase-protocol-shaded"
  else
    mvn_flags+=("-Dhadoop.profile=3.0")
  fi
  (cd "$dir" && JAVA_HOME="$java_home" mvn "${mvn_flags[@]}" -pl "$modules" -am compile) >"$log" 2>&1
}

package_classes_jar() {
  local classes_dir="$1"
  local out_jar="$2"
  mkdir -p "$(dirname "$out_jar")"
  "$JAR_BIN" cf "$out_jar" -C "$classes_dir" .
}

instrument_jar() {
  local input_jar="$1"
  local output_jar="$2"
  local log_file="$3"
  local tool_cp="$4"

  local workdir
  workdir="$(dirname "$output_jar")"
  mkdir -p "$workdir"

  (
    cd "$workdir"
    JAVA_HOME="$JAVA17" "$JAVA_BIN" -cp "$tool_cp" "$INSTRUMENTER_MAIN" "$input_jar" -o "$output_jar"
    if [[ -f report ]]; then
      mv -f report "$(basename "$output_jar").report.txt"
    fi
  ) >"$log_file" 2>&1
}

run_instrumented_module() {
  local project="$1"
  local version="$2"
  local module="$3"
  local input_jar="$4"
  local build_status="$5"
  local tool_cp="$6"

  local out_dir="$OUT_BASE/${project}-${version}"
  local output_jar="$out_dir/${module}-instrumented.jar"
  local log_file="$LOG_BASE/instrument-${project}-${version}-${module}.log"

  if [[ "$build_status" != "ok" ]]; then
    append_summary "$project" "$version" "$module" "$input_jar" "$output_jar" "$build_status" "skip" "build failed"
    return
  fi
  if [[ ! -f "$input_jar" ]]; then
    append_summary "$project" "$version" "$module" "$input_jar" "$output_jar" "$build_status" "skip" "input jar missing"
    return
  fi

  if instrument_jar "$input_jar" "$output_jar" "$log_file" "$tool_cp"; then
    append_summary "$project" "$version" "$module" "$input_jar" "$output_jar" "$build_status" "ok" ""
  else
    append_summary "$project" "$version" "$module" "$input_jar" "$output_jar" "$build_status" "fail" "instrumentation failed"
  fi
}

run_cassandra_version() {
  local version="$1"
  local tool_cp="$2"
  local build_status="ok"
  if ! build_cassandra "$version"; then
    build_status="fail"
  fi

  local dir="$PREBUILD/apache-cassandra-${version}-src/build"
  local main_jar="$dir/apache-cassandra-${version}-SNAPSHOT.jar"
  run_instrumented_module "cassandra" "$version" "main" "$main_jar" "$build_status" "$tool_cp"

  local thrift_jar="$dir/apache-cassandra-thrift-${version}-SNAPSHOT.jar"
  if [[ -f "$thrift_jar" ]]; then
    run_instrumented_module "cassandra" "$version" "thrift" "$thrift_jar" "$build_status" "$tool_cp"
  fi
}

run_hdfs_version() {
  local version="$1"
  local tool_cp="$2"
  local build_status="ok"
  if ! build_hdfs "$version"; then
    build_status="fail"
  fi

  local root="$PREBUILD/hadoop-${version}-src"
  local common_classes="$root/hadoop-common-project/hadoop-common/target/classes"
  local hdfs_classes="$root/hadoop-hdfs-project/hadoop-hdfs/target/classes"
  local hdfs_client_classes="$root/hadoop-hdfs-project/hadoop-hdfs-client/target/classes"

  local common_in="$TMP_BASE/hdfs-${version}-hadoop-common.jar"
  local hdfs_in="$TMP_BASE/hdfs-${version}-hadoop-hdfs.jar"
  local hdfs_client_in="$TMP_BASE/hdfs-${version}-hadoop-hdfs-client.jar"

  if [[ "$build_status" == "ok" && -d "$common_classes" ]]; then
    package_classes_jar "$common_classes" "$common_in"
  fi
  if [[ "$build_status" == "ok" && -d "$hdfs_classes" ]]; then
    package_classes_jar "$hdfs_classes" "$hdfs_in"
  fi
  if [[ "$build_status" == "ok" && -d "$hdfs_client_classes" ]]; then
    package_classes_jar "$hdfs_client_classes" "$hdfs_client_in"
  fi

  run_instrumented_module "hdfs" "$version" "hadoop-common" "$common_in" "$build_status" "$tool_cp"
  run_instrumented_module "hdfs" "$version" "hadoop-hdfs" "$hdfs_in" "$build_status" "$tool_cp"
  run_instrumented_module "hdfs" "$version" "hadoop-hdfs-client" "$hdfs_client_in" "$build_status" "$tool_cp"
}

run_hbase_version() {
  local version="$1"
  local tool_cp="$2"
  local build_status="ok"
  if ! build_hbase "$version"; then
    build_status="fail"
  fi

  local root="$PREBUILD/hbase-${version}"
  local modules=("hbase-common" "hbase-client" "hbase-server" "hbase-protocol-shaded")
  if [[ "$version" != 3.* && "$version" != 4.* ]]; then
    modules+=("hbase-protocol")
  fi

  local module
  for module in "${modules[@]}"; do
    local classes_dir="$root/$module/target/classes"
    local input_jar="$TMP_BASE/hbase-${version}-${module}.jar"
    if [[ "$build_status" == "ok" && -d "$classes_dir" ]]; then
      package_classes_jar "$classes_dir" "$input_jar"
    fi
    run_instrumented_module "hbase" "$version" "$module" "$input_jar" "$build_status" "$tool_cp"
  done
}

main() {
  mkdir -p "$LOG_BASE" "$TMP_BASE" "$OUT_BASE"
  echo "project,version,module,input_jar,output_jar,build_status,instrument_status,note" > "$SUMMARY_CSV"

  build_nettrace_cli
  local tool_cp
  tool_cp=$(join_by_colon "$ROOT"/rupfuzz-nettrace/build/install/rupfuzz-nettrace/lib/*.jar)

  run_cassandra_version "3.11.19" "$tool_cp"
  run_cassandra_version "4.1.10" "$tool_cp"
  run_cassandra_version "5.0.6" "$tool_cp"

  run_hdfs_version "2.10.2" "$tool_cp"
  run_hdfs_version "3.3.6" "$tool_cp"
  run_hdfs_version "3.4.2" "$tool_cp"

  run_hbase_version "2.5.13" "$tool_cp"
  run_hbase_version "2.6.4" "$tool_cp"
  run_hbase_version "4.0.0-alpha-1-SNAPSHOT" "$tool_cp"

  echo "Wrote summary: $SUMMARY_CSV"
}

main "$@"
