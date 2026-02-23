#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROFILES_DIR="$ROOT/profiles"
OUTPUT_DIR="$ROOT/validation/output"

write_prefixes() {
  local project="$1"
  case "$project" in
    cassandra)
      cat <<'EOF'
targetPrefixes:
  - org.apache.cassandra.net
  - org.apache.cassandra.streaming
excludePrefixes:
  - org.apache.cassandra.utils.btree
EOF
      ;;
    hadoop)
      cat <<'EOF'
targetPrefixes:
  - org.apache.hadoop.ipc
  - org.apache.hadoop.hdfs.protocol
excludePrefixes:
  - org.apache.hadoop.io.compress
EOF
      ;;
    hbase)
      cat <<'EOF'
targetPrefixes:
  - org.apache.hadoop.hbase.ipc
  - org.apache.hbase.thirdparty.io.netty
EOF
      ;;
    *)
      echo "unsupported project: $project" >&2
      exit 1
      ;;
  esac
}

write_rules_block() {
  local label="$1"
  local rules_tsv="$2"

  echo "$label:"
  while IFS=$'\t' read -r owner method descriptor reason; do
    [[ -n "${owner:-}" ]] || continue
    echo "  - ownerPattern: $owner"
    echo "    methodPattern: $method"
    printf "    descriptorPattern: '%s'\n" "$descriptor"
    echo "    reason: $reason"
  done < "$rules_tsv"
}

profile_id_from_cassandra_tar() {
  local tar_name="$1"
  if [[ "$tar_name" =~ ^apache-cassandra-(.+)-src(-instrumented)?\.tar\.gz$ ]]; then
    echo "cassandra-${BASH_REMATCH[1]}${BASH_REMATCH[2]:-}"
    return 0
  fi
  return 1
}

profile_id_from_hadoop_tar() {
  local tar_name="$1"
  if [[ "$tar_name" =~ ^hadoop-(.+)-src\.tar\.gz$ ]]; then
    echo "hadoop-${BASH_REMATCH[1]}"
    return 0
  fi
  return 1
}

profile_id_from_hbase_tar() {
  local tar_name="$1"
  if [[ "$tar_name" =~ ^hbase-(.+)-src\.tar\.gz$ ]]; then
    echo "hbase-${BASH_REMATCH[1]}"
    return 0
  fi
  return 1
}

output_dir_name_for_profile() {
  local profile_id="$1"
  if [[ "$profile_id" == hadoop-* ]]; then
    echo "hdfs-${profile_id#hadoop-}"
  else
    echo "$profile_id"
  fi
}

project_for_profile() {
  local profile_id="$1"
  case "$profile_id" in
    cassandra-*) echo "cassandra" ;;
    hadoop-*) echo "hadoop" ;;
    hbase-*) echo "hbase" ;;
    *)
      echo "unsupported profile id: $profile_id" >&2
      exit 1
      ;;
  esac
}

generate_profile() {
  local profile_id="$1"
  local project="$2"
  local output_name="$3"
  local output_path="$OUTPUT_DIR/$output_name"
  local send_raw="$output_path/rawSendAnchors.json"
  local recv_raw="$output_path/rawRecvAnchors.json"
  local send_rules
  local recv_rules
  local profile_path="$PROFILES_DIR/$profile_id.yaml"
  local description="Version-specific $project profile mined from validation output $output_name"

  [[ -f "$send_raw" ]] || {
    echo "missing raw send anchors: $send_raw" >&2
    exit 1
  }
  [[ -f "$recv_raw" ]] || {
    echo "missing raw recv anchors: $recv_raw" >&2
    exit 1
  }

  send_rules="$(mktemp)"
  recv_rules="$(mktemp)"
  trap 'rm -f "$send_rules" "$recv_rules"' RETURN

  jq -r '.[] | select(.source == "PROFILE") | [.invokedClass, .invokedMethod, .invokedDescriptor, .reason] | @tsv' \
    "$send_raw" | sort -u > "$send_rules"
  jq -r '.[] | select(.source == "PROFILE") | [.invokedClass, .invokedMethod, .invokedDescriptor, .reason] | @tsv' \
    "$recv_raw" | sort -u > "$recv_rules"

  [[ -s "$send_rules" ]] || {
    echo "no PROFILE send rules mined from: $send_raw" >&2
    exit 1
  }
  [[ -s "$recv_rules" ]] || {
    echo "no PROFILE recv rules mined from: $recv_raw" >&2
    exit 1
  }

  {
    echo "id: $profile_id"
    echo "description: $description"
    write_prefixes "$project"
    write_rules_block "sendRules" "$send_rules"
    write_rules_block "recvRules" "$recv_rules"
  } > "$profile_path"

  echo "generated $profile_path from $output_name"

  rm -f "$send_rules" "$recv_rules"
  trap - RETURN
}

main() {
  local tar profile_id project output_name

  shopt -s nullglob
  for tar in "$ROOT"/prebuild/cassandra/apache-cassandra-*.tar.gz; do
    profile_id="$(profile_id_from_cassandra_tar "$(basename "$tar")")"
    project="$(project_for_profile "$profile_id")"
    output_name="$(output_dir_name_for_profile "$profile_id")"
    generate_profile "$profile_id" "$project" "$output_name"
  done

  for tar in "$ROOT"/prebuild/hdfs/hadoop-*-src.tar.gz; do
    profile_id="$(profile_id_from_hadoop_tar "$(basename "$tar")")"
    project="$(project_for_profile "$profile_id")"
    output_name="$(output_dir_name_for_profile "$profile_id")"
    generate_profile "$profile_id" "$project" "$output_name"
  done

  for tar in "$ROOT"/prebuild/hbase/hbase-*-src.tar.gz; do
    profile_id="$(profile_id_from_hbase_tar "$(basename "$tar")")"
    project="$(project_for_profile "$profile_id")"
    output_name="$(output_dir_name_for_profile "$profile_id")"
    generate_profile "$profile_id" "$project" "$output_name"
  done
  shopt -u nullglob
}

main "$@"
