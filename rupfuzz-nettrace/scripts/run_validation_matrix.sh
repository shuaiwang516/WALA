#!/usr/bin/env bash
set -u

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PREBUILD="$ROOT/rupfuzz-nettrace/prebuild/extracted"
PROFILE_DIR="$ROOT/rupfuzz-nettrace/profiles"
OUT_BASE="$ROOT/rupfuzz-nettrace/validation/output"
LOG_BASE="$ROOT/rupfuzz-nettrace/validation/logs"
SUMMARY_CSV="$ROOT/rupfuzz-nettrace/validation/summary.csv"
CLI_BIN="$ROOT/rupfuzz-nettrace/build/install/rupfuzz-nettrace/bin/rupfuzz-nettrace"
PRECISION="${NETTRACE_PRECISION:-rta}"
ENTRYPOINT_MODE="${NETTRACE_ENTRYPOINT_MODE:-profile-seeded}"
MIN_RESOLVED_SEND="${NETTRACE_MIN_RESOLVED_SEND:-1}"
MIN_RESOLVED_RECV="${NETTRACE_MIN_RESOLVED_RECV:-1}"
MAX_FALLBACK_SEND_RATIO="${NETTRACE_MAX_FALLBACK_SEND_RATIO:-0.50}"
MAX_FALLBACK_RECV_RATIO="${NETTRACE_MAX_FALLBACK_RECV_RATIO:-0.50}"

mkdir -p "$OUT_BASE" "$LOG_BASE"
echo "project,version,build_status,run_status,raw_send,raw_recv,profile_send,profile_recv,phase2_send,phase2_recv_begin,phase2_recv_end,resolved_send,resolved_recv,fallback_send,fallback_recv,fallback_send_ratio,fallback_recv_ratio,mismatch" > "$SUMMARY_CSV"

JAVA8="/usr/lib/jvm/java-8-openjdk-amd64"
JAVA11="/usr/lib/jvm/java-11-openjdk-amd64"
JAVA17="/usr/lib/jvm/java-17-openjdk-amd64"

join_by_colon() {
  local IFS=:
  echo "$*"
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
  if [[ "$version" == 3.* ]]; then
    java_home="$JAVA17"
    modules="hbase-common,hbase-client,hbase-server,hbase-protocol-shaded"
  else
    mvn_flags+=("-Dhadoop.profile=3.0")
  fi
  (cd "$dir" && JAVA_HOME="$java_home" mvn "${mvn_flags[@]}" -pl "$modules" -am compile) >"$log" 2>&1
}

build_nettrace_cli() {
  local log="$LOG_BASE/build-nettrace-cli.log"
  (cd "$ROOT" && JAVA_HOME="$JAVA17" ./gradlew :rupfuzz-nettrace:installDist -x test) >"$log" 2>&1
}

make_cp_cassandra() {
  local version="$1"
  local dir="$PREBUILD/apache-cassandra-${version}-src"
  local -a entries=()
  [[ -d "$dir/build/classes/main" ]] && entries+=("$dir/build/classes/main")
  [[ -d "$dir/build/classes/thrift" ]] && entries+=("$dir/build/classes/thrift")
  [[ -f "$dir/build/apache-cassandra-${version}-SNAPSHOT.jar" ]] && entries+=("$dir/build/apache-cassandra-${version}-SNAPSHOT.jar")
  join_by_colon "${entries[@]}"
}

make_cp_from_list() {
  local -a entries=()
  local d
  for d in "$@"; do
    [[ -d "$d" ]] && entries+=("$d")
  done
  join_by_colon "${entries[@]}"
}

run_analyzer() {
  local project="$1"
  local version="$2"
  local cp="$3"
  local main_class="$4"
  local profile="$5"
  shift 5
  local prefixes=("$@")

  local out_dir="$OUT_BASE/${project}-${version}"
  local log="$LOG_BASE/run-${project}-${version}.log"
  rm -rf "$out_dir"
  mkdir -p "$out_dir"

  local -a cmd=(
    "$CLI_BIN"
    "--app-classpath" "$cp"
    "--main-class" "$main_class"
    "--profile" "$profile"
    "--precision" "$PRECISION"
    "--entrypoint-mode" "$ENTRYPOINT_MODE"
    "--output-dir" "$out_dir"
  )
  local p
  for p in "${prefixes[@]}"; do
    cmd+=("--target-prefix" "$p")
  done

  (cd "$ROOT" && JAVA_HOME="$JAVA17" PATH="$JAVA17/bin:$PATH" timeout 1800 "${cmd[@]}") >"$log" 2>&1
}

count_json() {
  local file="$1"
  if [[ ! -f "$file" ]]; then
    echo 0
    return
  fi
  jq 'length' "$file" 2>/dev/null || echo 0
}

count_profile_json() {
  local file="$1"
  if [[ ! -f "$file" ]]; then
    echo 0
    return
  fi
  jq '[.[] | select(.source == "PROFILE")] | length' "$file" 2>/dev/null || echo 0
}

json_scalar_or_zero() {
  local file="$1"
  local key="$2"
  if [[ ! -f "$file" ]]; then
    echo 0
    return
  fi
  jq -r ".$key // 0" "$file" 2>/dev/null || echo 0
}

ratio6() {
  local numerator="$1"
  local denominator="$2"
  awk -v n="$numerator" -v d="$denominator" 'BEGIN { if (d <= 0) printf "1.000000"; else printf "%.6f", n / d; }'
}

float_gt() {
  local lhs="$1"
  local rhs="$2"
  awk -v a="$lhs" -v b="$rhs" 'BEGIN { exit !(a > b) }'
}

record_summary() {
  local project="$1"
  local version="$2"
  local build_status="$3"
  local run_status="$4"
  local out_dir="$5"

  local raw_send raw_recv profile_send profile_recv p2_send p2_recv_begin p2_recv_end
  local resolved_send resolved_recv fallback_send fallback_recv
  local fallback_send_ratio fallback_recv_ratio mismatch
  raw_send=$(count_json "$out_dir/rawSendAnchors.json")
  raw_recv=$(count_json "$out_dir/rawRecvAnchors.json")
  profile_send=$(count_profile_json "$out_dir/rawSendAnchors.json")
  profile_recv=$(count_profile_json "$out_dir/rawRecvAnchors.json")
  p2_send=$(count_json "$out_dir/netSendPoints.json")
  p2_recv_begin=$(count_json "$out_dir/netRecvBeginPoints.json")
  p2_recv_end=$(count_json "$out_dir/netRecvEndPoints.json")
  resolved_send=$(json_scalar_or_zero "$out_dir/netPhase2Diagnostics.json" "resolvedSend")
  resolved_recv=$(json_scalar_or_zero "$out_dir/netPhase2Diagnostics.json" "resolvedRecv")
  fallback_send=$(json_scalar_or_zero "$out_dir/netPhase2Diagnostics.json" "fallbackSend")
  fallback_recv=$(json_scalar_or_zero "$out_dir/netPhase2Diagnostics.json" "fallbackRecv")
  fallback_send_ratio=$(ratio6 "$fallback_send" "$p2_send")
  fallback_recv_ratio=$(ratio6 "$fallback_recv" "$p2_recv_begin")

  mismatch="ok"
  if [[ "$build_status" != "ok" || "$run_status" != "ok" ]]; then
    mismatch="build_or_run_failed"
  elif [[ "$raw_send" -eq 0 || "$raw_recv" -eq 0 ]]; then
    mismatch="zero_raw_anchor"
  elif [[ "$profile_send" -eq 0 || "$profile_recv" -eq 0 ]]; then
    mismatch="profile_anchor_missing"
  elif [[ "$p2_send" -eq 0 || "$p2_recv_begin" -eq 0 || "$p2_recv_end" -eq 0 ]]; then
    mismatch="phase2_missing"
  elif [[ ! -f "$out_dir/netPhase2Diagnostics.json" ]]; then
    mismatch="phase2_diagnostics_missing"
  elif [[ "$resolved_send" -lt "$MIN_RESOLVED_SEND" || "$resolved_recv" -lt "$MIN_RESOLVED_RECV" ]]; then
    mismatch="phase2_resolved_too_low"
  elif float_gt "$fallback_send_ratio" "$MAX_FALLBACK_SEND_RATIO"; then
    mismatch="phase2_fallback_send_high"
  elif float_gt "$fallback_recv_ratio" "$MAX_FALLBACK_RECV_RATIO"; then
    mismatch="phase2_fallback_recv_high"
  elif [[ "$raw_send" -lt 5 || "$raw_recv" -lt 5 ]]; then
    mismatch="too_few_anchors"
  fi

  echo "$project,$version,$build_status,$run_status,$raw_send,$raw_recv,$profile_send,$profile_recv,$p2_send,$p2_recv_begin,$p2_recv_end,$resolved_send,$resolved_recv,$fallback_send,$fallback_recv,$fallback_send_ratio,$fallback_recv_ratio,$mismatch" >> "$SUMMARY_CSV"
}

run_target() {
  local project="$1"
  local version="$2"
  local main_class="$3"
  local profile_file="$4"
  shift 4
  local prefixes=("$@")

  local build_status="ok"
  local run_status="ok"
  local cp=""

  if [[ "$project" == "cassandra" ]]; then
    if ! build_cassandra "$version"; then
      build_status="fail"
    fi
    cp=$(make_cp_cassandra "$version")
  elif [[ "$project" == "hdfs" ]]; then
    if ! build_hdfs "$version"; then
      build_status="fail"
    fi
    cp=$(make_cp_from_list \
      "$PREBUILD/hadoop-${version}-src/hadoop-common-project/hadoop-common/target/classes" \
      "$PREBUILD/hadoop-${version}-src/hadoop-hdfs-project/hadoop-hdfs/target/classes" \
      "$PREBUILD/hadoop-${version}-src/hadoop-hdfs-project/hadoop-hdfs-client/target/classes")
  elif [[ "$project" == "hbase" ]]; then
    if ! build_hbase "$version"; then
      build_status="fail"
    fi
    if [[ "$version" == 3.* ]]; then
      cp=$(make_cp_from_list \
        "$PREBUILD/hbase-${version}/hbase-common/target/classes" \
        "$PREBUILD/hbase-${version}/hbase-client/target/classes" \
        "$PREBUILD/hbase-${version}/hbase-server/target/classes" \
        "$PREBUILD/hbase-${version}/hbase-protocol-shaded/target/classes")
    else
      cp=$(make_cp_from_list \
        "$PREBUILD/hbase-${version}/hbase-common/target/classes" \
        "$PREBUILD/hbase-${version}/hbase-client/target/classes" \
        "$PREBUILD/hbase-${version}/hbase-server/target/classes" \
        "$PREBUILD/hbase-${version}/hbase-protocol-shaded/target/classes" \
        "$PREBUILD/hbase-${version}/hbase-protocol/target/classes")
    fi
  fi

  if [[ "$build_status" == "ok" ]]; then
    if [[ -z "$cp" ]]; then
      run_status="fail"
    else
      if ! run_analyzer "$project" "$version" "$cp" "$main_class" "$PROFILE_DIR/$profile_file" "${prefixes[@]}"; then
        run_status="fail"
      fi
    fi
  else
    run_status="skip"
  fi

  record_summary "$project" "$version" "$build_status" "$run_status" "$OUT_BASE/${project}-${version}"
}

if ! build_nettrace_cli; then
  echo "nettrace,cli,fail,skip,0,0,0,0,0,0,0,0,0,0,0,1.000000,1.000000,build_or_run_failed" >> "$SUMMARY_CSV"
  cat "$SUMMARY_CSV"
  exit 1
fi

run_target cassandra 3.11.19 org.apache.cassandra.tools.GetVersion cassandra-3.11.19.yaml org.apache.cassandra.net org.apache.cassandra.streaming
run_target cassandra 4.1.10 org.apache.cassandra.tools.GetVersion cassandra-4.1.10.yaml org.apache.cassandra.net org.apache.cassandra.streaming
run_target cassandra 5.0.6 org.apache.cassandra.tools.GetVersion cassandra-5.0.6.yaml org.apache.cassandra.net org.apache.cassandra.streaming

run_target hdfs 2.10.2 org.apache.hadoop.util.VersionInfo hadoop-2.10.2.yaml org.apache.hadoop.ipc org.apache.hadoop.hdfs
run_target hdfs 3.3.6 org.apache.hadoop.util.VersionInfo hadoop-3.3.6.yaml org.apache.hadoop.ipc org.apache.hadoop.hdfs
run_target hdfs 3.4.2 org.apache.hadoop.util.VersionInfo hadoop-3.4.2.yaml org.apache.hadoop.ipc org.apache.hadoop.hdfs

run_target hbase 2.5.13 org.apache.hadoop.hbase.util.VersionInfo hbase-2.5.13.yaml org.apache.hadoop.hbase.ipc org.apache.hbase.thirdparty.io.netty
run_target hbase 2.6.4 org.apache.hadoop.hbase.util.VersionInfo hbase-2.6.4.yaml org.apache.hadoop.hbase.ipc org.apache.hbase.thirdparty.io.netty
run_target hbase 3.0.0-beta-1 org.apache.hadoop.hbase.util.VersionInfo hbase-3.0.0-beta-1.yaml org.apache.hadoop.hbase.ipc org.apache.hbase.thirdparty.io.netty

cat "$SUMMARY_CSV"
