#!/usr/bin/env bash
# Phase 5 version-aware family-map profile generator.
#
# Mines per-system per-version (rpcService, rpcMethod) inventories from the
# instrumented prebuild source tarballs and emits a YAML profile per version
# under nettrace-shuai/rupfuzz-nettrace/profiles/family-maps/.
#
# Each profile is a long-tail extension to the Phase 1 hardcoded
# ProtocolFamilyClassifier — entries are consulted ONLY when the live
# classifier returns UNKNOWN, so adding a profile is additive and cannot
# regress the Phase 1 taxonomy. See
# upfuzz-shuai/.../trace/VersionAwareFamilyProfile.java for the loader.
#
# Inventory sources (per Phase 5 plan):
#   - Cassandra: org.apache.cassandra.net.Verb (3.x) / Verb enum (4.x/5.x)
#   - HDFS:      protobuf .proto files under hadoop-hdfs / hadoop-common
#   - HBase:     protobuf .proto files (ClientService, AdminService,
#                MasterService, RegionServerStatusService, ...)
#
# Default family assignment for newly mined methods is BACKGROUND or
# UNKNOWN — the script does NOT guess upgrade-criticality. Operators
# review and re-tag specific entries by editing the generated YAML or by
# adding a per-system overrides file
# (profiles/family-maps/<system>-<version>-overrides.yaml).
#
# Usage:
#   bash scripts/generate_family_inventories.sh                # all systems
#   bash scripts/generate_family_inventories.sh hdfs           # only HDFS
#   bash scripts/generate_family_inventories.sh hdfs hadoop-3.3.6
#   bash scripts/generate_family_inventories.sh cassandra
#   bash scripts/generate_family_inventories.sh hbase
#
# Inputs:
#   nettrace-shuai/rupfuzz-nettrace/prebuild/<system>/<version>-src.tar.gz
#
# Outputs:
#   nettrace-shuai/rupfuzz-nettrace/profiles/family-maps/<system>-<version>-family-map.yaml
#
# Idempotent: re-running on the same prebuild reproduces byte-identical YAML
# (entries are sorted by rpcService then rpcMethod).

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROFILES_DIR="$ROOT/profiles/family-maps"
PREBUILD_DIR="$ROOT/prebuild"
TMPROOT="${TMPDIR:-/tmp}/rupfuzz-family-inv-$$"

mkdir -p "$PROFILES_DIR"
trap 'rm -rf "$TMPROOT"' EXIT

log() {
    printf '[%s] %s\n' "$(date '+%F %T')" "$*"
}

usage() {
    grep '^# ' "$0" | sed 's/^# \{0,1\}//'
    exit 1
}

# ---------------------------------------------------------------------------
# Family default rules (system-specific). Each rule is a regex matched against
# the lowercased rpcMethod (or, for Cassandra, the verb name); the family
# associated with the first matching rule wins. Fallthrough is BACKGROUND or
# UNKNOWN per system.
# ---------------------------------------------------------------------------

classify_hdfs_method() {
    local svc="$1" m="$2"
    local lm
    lm="$(printf '%s' "$m" | tr 'A-Z' 'a-z')"
    case "$svc" in
        ClientNamenodeProtocolService|ClientNamenodeProtocol)
            case "$lm" in
                create|mkdirs|rename|rename2|delete|append|truncate|concat\
                |setreplication|setstoragepolicy|unsetstoragepolicy\
                |seterasurecodingpolicy|unseterasurecodingpolicy\
                |setowner|setpermission|settimes|setxattr|removexattr\
                |setacl|modifyaclentries|removeaclentries|removeacl\
                |removedefaultacl|createsymlink|createencryptionzone\
                |reencryptencryptionzone|allowsnapshot|disallowsnapshot\
                |createsnapshot|deletesnapshot|renamesnapshot\
                |addcachedirective|modifycachedirective|removecachedirective\
                |addcachepool|modifycachepool|removecachepool|setquota\
                |setquotabystoragetype|enableerasurecodingpolicy\
                |disableerasurecodingpolicy|adderasurecodingpolicies\
                |removeerasurecodingpolicy|satisfystoragepolicy)
                    echo HDFS_CLIENT_NAMESPACE_MUTATION ;;
                addblock|abandonblock|complete|getadditionaldatanode\
                |updateblockforpipeline|updatepipeline|getblocklocations\
                |reportbadblocks|getdataencryptionkey)
                    echo HDFS_CLIENT_BLOCK_PIPELINE ;;
                heartbeat|sendheartbeat|gettransactionid|getservicestatus\
                |monitorhealth|isalive|geteditslogmanifest|registerdatanode)
                    echo BACKGROUND ;;
                *) echo UNKNOWN ;;
            esac
            ;;
        DatanodeProtocolService|DatanodeProtocol)
            case "$lm" in
                blockreport|blockreceivedanddeleted|cachereport\
                |slowdiskreport|slowpeerreport|errorreport)
                    echo HDFS_CLIENT_BLOCK_PIPELINE ;;
                heartbeat|registerdatanode|versionrequest|sendlifeline)
                    echo BACKGROUND ;;
                commitblocksynchronization)
                    echo HDFS_CLIENT_BLOCK_PIPELINE ;;
                *) echo UNKNOWN ;;
            esac
            ;;
        QJournalProtocolService|QJournalProtocol)
            echo HDFS_JOURNAL_QJM ;;
        HAServiceProtocolService|HAServiceProtocol|ZKFCProtocolService|ZKFCProtocol)
            echo HDFS_HA_COORDINATION ;;
        *) echo UNKNOWN ;;
    esac
}

classify_hbase_method() {
    local svc="$1" m="$2"
    local lm
    lm="$(printf '%s' "$m" | tr 'A-Z' 'a-z')"
    case "$svc" in
        ClientService)
            case "$lm" in
                mutate|multi|bulkloadhfile)
                    echo HBASE_CLIENT_MUTATION_OR_MULTI ;;
                get|scan)
                    echo BACKGROUND ;;
                *) echo UNKNOWN ;;
            esac
            ;;
        AdminService)
            case "$lm" in
                openregion|closeregion|splitregion|mergeregions\
                |compactregion|flushregion|warmupregion|stopserver\
                |replicatewalentry|replayrequests|replicatelogentries\
                |updatefavorednodes)
                    echo HBASE_REGION_ADMIN_LIFECYCLE ;;
                *) echo BACKGROUND ;;
            esac
            ;;
        MasterService)
            case "$lm" in
                createtable|deletetable|truncatetable|enabletable\
                |disabletable|modifytable|createnamespace|deletenamespace\
                |modifynamespace|assignregion|unassignregion|moveregion\
                |splitregion|mergeregions)
                    echo HBASE_MASTER_SCHEMA_DDL ;;
                *) echo UNKNOWN ;;
            esac
            ;;
        RegionServerStatusService)
            case "$lm" in
                regionserverreport|regionserverstartup\
                |reportregionstatechange|reportregionservertransition\
                |getlastflushedsequenceid|isalive)
                    echo BACKGROUND ;;
                *) echo UNKNOWN ;;
            esac
            ;;
        LockService|BootstrapNodeService)
            echo BACKGROUND ;;
        *) echo UNKNOWN ;;
    esac
}

classify_cassandra_verb() {
    local v="$1"
    local uv
    uv="$(printf '%s' "$v" | tr 'a-z' 'A-Z')"
    case "$uv" in
        SCHEMA_*|MIGRATION_*|DEFINITIONS_*) echo CASSANDRA_SCHEMA_SYNC ;;
        PAXOS*|PAXOS2_*) echo CASSANDRA_PAXOS ;;
        REPAIR*|VALIDATION*|SYNC_*|STREAM_*|SNAPSHOT*|PREPARE_CONSISTENT_*\
        |FINALIZE_*|FAILED_SESSION_*|STATUS_*|CLEANUP_*|ANTICOMPACTION_*)
            echo CASSANDRA_REPAIR_OR_STREAM ;;
        READ_REPAIR*|HINT*|HINTED_HANDOFF|BATCH_*) echo CASSANDRA_READ_REPAIR_OR_HINT ;;
        GOSSIP_*|ECHO*|PING*|_TRACE|_TEST_*) echo BACKGROUND ;;
        REQUEST_RESPONSE|INTERNAL_RESPONSE) echo BACKGROUND ;;
        *) echo UNKNOWN ;;
    esac
}

# ---------------------------------------------------------------------------
# Per-system extractors.
# Each extractor produces TSV rows of "rpcService<TAB>rpcMethod" to stdout.
# ---------------------------------------------------------------------------

extract_hdfs() {
    local extracted="$1"
    # Mine .proto files for "service ServiceName { rpc methodName ... }"
    # Output: <serviceName>\t<methodName>
    find "$extracted" -name '*.proto' -path '*/hadoop-*' -print 2>/dev/null \
        | while read -r f; do
            awk '
                /^[[:space:]]*service[[:space:]]+[A-Za-z0-9_]+/ {
                    sub(/[\{\r]/, "", $2);
                    svc = $2; next;
                }
                /^[[:space:]]*rpc[[:space:]]+[A-Za-z0-9_]+/ {
                    if (svc != "") {
                        m = $2; sub(/\(.*/, "", m);
                        if (m != "") printf "%s\t%s\n", svc, m;
                    }
                }
            ' "$f"
        done | sort -u
}

extract_hbase() {
    local extracted="$1"
    find "$extracted" -name '*.proto' -path '*/hbase-*' -print 2>/dev/null \
        | while read -r f; do
            awk '
                /^[[:space:]]*service[[:space:]]+[A-Za-z0-9_]+/ {
                    sub(/[\{\r]/, "", $2);
                    svc = $2; next;
                }
                /^[[:space:]]*rpc[[:space:]]+[A-Za-z0-9_]+/ {
                    if (svc != "") {
                        m = $2; sub(/\(.*/, "", m);
                        if (m != "") printf "%s\t%s\n", svc, m;
                    }
                }
            ' "$f"
        done | sort -u
}

extract_cassandra() {
    local extracted="$1"
    # Walk Verb.java / MessagingService.java for verb enum entries.
    # Output one verb name per line. The awk strips trailing
    # "(<args>" so the line "GOSSIP_DIGEST_SYN(1, ...)" becomes
    # "GOSSIP_DIGEST_SYN" (otherwise the YAML would key on
    # "GOSSIP_DIGEST_SYN(1," which never matches a runtime
    # messageType).
    {
        find "$extracted" -path '*/org/apache/cassandra/net/Verb.java' \
                -print 2>/dev/null \
            | while read -r f; do
                grep -E '^[[:space:]]+[A-Z_][A-Z0-9_]*[[:space:]]*\(' "$f" \
                    | awk '{ s = $1; sub(/[\(,;].*$/, "", s); if (s != "") print s }' | sort -u
            done
        find "$extracted" -path '*/cassandra/net/MessagingService.java' \
                -print 2>/dev/null \
            | while read -r f; do
                # Verb enum nested inside MessagingService.Verb (3.x)
                awk '
                    /enum[[:space:]]+Verb[[:space:]]*[\{]?/ { in_enum = 1; next; }
                    in_enum && /^[[:space:]]+[A-Z_][A-Z0-9_]*[[:space:]]*[,;\(]/ {
                        s = $1; sub(/[,;\(].*/, "", s);
                        if (s != "" && s ~ /^[A-Z_]/) print s;
                    }
                    in_enum && /;[[:space:]]*$/ { in_enum = 0; }
                ' "$f"
            done
    } | sort -u
}

emit_yaml_header() {
    local profile_id="$1" system="$2" desc="$3"
    cat <<HEAD
# Auto-generated by scripts/generate_family_inventories.sh
# Do not edit by hand: re-run the generator and apply targeted
# overrides via a sibling <profile-id>-overrides.yaml file.
id: $profile_id
description: $desc
system: $system
familyMap:
HEAD
}

emit_yaml_entry_method() {
    local svc="$1" m="$2" fam="$3"
    cat <<ROW
  - rpcService: $svc
    rpcMethod: $m
    family: $fam
ROW
}

emit_yaml_entry_verb() {
    local v="$1" fam="$2"
    # Cassandra entries are messageType-keyed because the live runtime
    # populates SendMeta.messageType with the verb name and leaves
    # rpcService / rpcMethod null. The YAML loader looks them up via
    # the messageType index so the override actually fires.
    cat <<ROW
  - messageType: $v
    family: $fam
ROW
}

extract_archive() {
    local archive="$1"
    local target="$2"
    mkdir -p "$target"
    case "$archive" in
        *.tar.gz|*.tgz) tar -xzf "$archive" -C "$target" ;;
        *.tar)          tar -xf  "$archive" -C "$target" ;;
        *) echo "unsupported archive: $archive" >&2; return 1 ;;
    esac
}

derive_version_label() {
    local archive="$1"
    local base
    base="$(basename "$archive")"
    base="${base%-src-instrumented.tar.gz}"
    base="${base%-src.tar.gz}"
    base="${base%-src.tgz}"
    base="${base%.tar.gz}"
    base="${base%.tgz}"
    echo "$base"
}

generate_one_hdfs() {
    local archive="$1"
    local version
    version="$(derive_version_label "$archive")"
    local profile_id="hdfs-${version}-family-map"
    local out="$PROFILES_DIR/${profile_id}.yaml"
    local extract_dir="$TMPROOT/$version"
    log "Generating $profile_id from $(basename "$archive")"
    extract_archive "$archive" "$extract_dir"
    local rows
    rows="$(extract_hdfs "$extract_dir" || true)"
    if [[ -z "$rows" ]]; then
        log "WARNING: no .proto rpc methods found in $archive; emitting empty profile sentinel"
    fi
    {
        emit_yaml_header "$profile_id" "hdfs" \
            "HDFS $version long-tail (rpcService, rpcMethod) -> ProtocolFamily map mined from .proto sources"
        printf '%s\n' "$rows" | while IFS=$'\t' read -r svc m; do
            [[ -n "$svc" && -n "$m" ]] || continue
            fam="$(classify_hdfs_method "$svc" "$m")"
            emit_yaml_entry_method "$svc" "$m" "$fam"
        done
    } > "$out"
    log "wrote $out"
}

generate_one_hbase() {
    local archive="$1"
    local version
    version="$(derive_version_label "$archive")"
    local profile_id="hbase-${version}-family-map"
    local out="$PROFILES_DIR/${profile_id}.yaml"
    local extract_dir="$TMPROOT/$version"
    log "Generating $profile_id from $(basename "$archive")"
    extract_archive "$archive" "$extract_dir"
    local rows
    rows="$(extract_hbase "$extract_dir" || true)"
    if [[ -z "$rows" ]]; then
        log "WARNING: no .proto rpc methods found in $archive; emitting empty profile sentinel"
    fi
    {
        emit_yaml_header "$profile_id" "hbase" \
            "HBase $version long-tail (rpcService, rpcMethod) -> ProtocolFamily map mined from .proto sources"
        printf '%s\n' "$rows" | while IFS=$'\t' read -r svc m; do
            [[ -n "$svc" && -n "$m" ]] || continue
            fam="$(classify_hbase_method "$svc" "$m")"
            emit_yaml_entry_method "$svc" "$m" "$fam"
        done
    } > "$out"
    log "wrote $out"
}

generate_one_cassandra() {
    local archive="$1"
    local version
    version="$(derive_version_label "$archive")"
    local profile_id="cassandra-${version}-family-map"
    local out="$PROFILES_DIR/${profile_id}.yaml"
    local extract_dir="$TMPROOT/$version"
    log "Generating $profile_id from $(basename "$archive")"
    extract_archive "$archive" "$extract_dir"
    local rows
    rows="$(extract_cassandra "$extract_dir" || true)"
    if [[ -z "$rows" ]]; then
        log "WARNING: no Cassandra Verb entries found in $archive; emitting empty profile sentinel"
    fi
    {
        emit_yaml_header "$profile_id" "cassandra" \
            "Cassandra $version long-tail Verb -> ProtocolFamily map mined from MessagingService/Verb.java"
        printf '%s\n' "$rows" | while read -r v; do
            [[ -n "$v" ]] || continue
            fam="$(classify_cassandra_verb "$v")"
            emit_yaml_entry_verb "$v" "$fam"
        done
    } > "$out"
    log "wrote $out"
}

run_for_system() {
    local sys="$1"
    local pin_version="${2:-}"
    case "$sys" in
        hdfs)
            shopt -s nullglob
            for archive in "$PREBUILD_DIR/hdfs"/hadoop-*-src-instrumented.tar.gz \
                            "$PREBUILD_DIR/hdfs"/hadoop-*-src.tar.gz; do
                if [[ -n "$pin_version" ]]; then
                    case "$archive" in *"$pin_version"-src*) ;; *) continue;; esac
                fi
                generate_one_hdfs "$archive"
            done
            shopt -u nullglob
            ;;
        hbase)
            shopt -s nullglob
            for archive in "$PREBUILD_DIR/hbase"/hbase-*-src-instrumented.tar.gz \
                            "$PREBUILD_DIR/hbase"/hbase-*-src.tar.gz; do
                if [[ -n "$pin_version" ]]; then
                    case "$archive" in *"$pin_version"-src*) ;; *) continue;; esac
                fi
                generate_one_hbase "$archive"
            done
            shopt -u nullglob
            ;;
        cassandra)
            shopt -s nullglob
            for archive in "$PREBUILD_DIR/cassandra"/apache-cassandra-*-src-instrumented.tar.gz \
                            "$PREBUILD_DIR/cassandra"/apache-cassandra-*-src.tar.gz; do
                if [[ -n "$pin_version" ]]; then
                    case "$archive" in *"$pin_version"-src*) ;; *) continue;; esac
                fi
                generate_one_cassandra "$archive"
            done
            shopt -u nullglob
            ;;
        *)
            echo "unsupported system: $sys" >&2
            return 1
            ;;
    esac
}

main() {
    if [[ $# -eq 0 ]]; then
        run_for_system hdfs
        run_for_system cassandra
        run_for_system hbase
    elif [[ $# -eq 1 ]]; then
        run_for_system "$1"
    else
        run_for_system "$1" "$2"
    fi
}

main "$@"
