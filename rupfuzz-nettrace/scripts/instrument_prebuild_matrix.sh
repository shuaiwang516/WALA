#!/usr/bin/env bash
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
NETTRACE_DIR="$ROOT/rupfuzz-nettrace"
PREBUILD_DIR="$NETTRACE_DIR/prebuild"
PROFILE_DIR="$NETTRACE_DIR/profiles"
WORK_BASE="$NETTRACE_DIR/instrumented/source-work"
LOG_BASE="$NETTRACE_DIR/instrumented/source-logs"
SUMMARY_CSV="$NETTRACE_DIR/instrumented/source-summary.csv"

mkdir -p "$WORK_BASE" "$LOG_BASE"

resolve_java_home() {
  local distro_name="$1"
  local fallback="$2"
  local alt_path
  alt_path="$(
    update-java-alternatives --list 2>/dev/null \
      | awk -v n="$distro_name" '$1 == n { print $3; exit }'
  )"
  if [[ -n "$alt_path" ]]; then
    echo "$alt_path"
    return
  fi
  echo "$fallback"
}

JAVA8="${JAVA8:-$(resolve_java_home "java-1.8.0-openjdk-amd64" "/usr/lib/jvm/java-8-openjdk-amd64")}"
JAVA11="${JAVA11:-$(resolve_java_home "java-1.11.0-openjdk-amd64" "/usr/lib/jvm/java-11-openjdk-amd64")}"
JAVA17="${JAVA17:-$(resolve_java_home "java-1.17.0-openjdk-amd64" "/usr/lib/jvm/java-17-openjdk-amd64")}"

replace_literal() {
  local file="$1"
  python3 -c '
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
payload = sys.stdin.read()
separator = "\n__NETTRACE_SEP__\n"
if separator not in payload:
    print(f"replace_literal: missing separator for {path}", file=sys.stderr)
    sys.exit(2)
old, new = payload.split(separator, 1)
text = path.read_text()
count = text.count(old)
if count != 1:
    print(f"replace_literal: expected 1 match in {path}, got {count}", file=sys.stderr)
    sys.exit(3)
path.write_text(text.replace(old, new, 1))
' "$file"
}

write_bridge() {
  local package_name="$1"
  local out_file="$2"
  mkdir -p "$(dirname "$out_file")"
  cat > "$out_file" <<EOF
package $package_name;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;

/**
 * Reflection bridge so target builds stay decoupled from optional net-trace runtime classes.
 */
public final class NetTraceRuntimeBridge {
    private static final Object[] EMPTY_CONTEXT = new Object[0];

    private static final Class<?> runtimeClass = loadClass("org.zlab.net.tracker.Runtime");
    private static final Class<?> sendMetaClass = loadClass("org.zlab.net.tracker.SendMeta");
    private static final Class<?> recvMetaClass = loadClass("org.zlab.net.tracker.RecvMeta");

    private static final Method initMethod = findRuntimeMethod("init", 0);
    private static final Method hitMethod = findRuntimeMethod("hit", 1);
    private static final Method recordSendMethod = findRuntimeMethod("recordSend", 5);
    private static final Method recordLegacyMethod = findRuntimeMethod("record", 3);
    private static final Method beginReceiveMethod = findRuntimeMethod("beginReceive", 5);
    private static final Method endReceiveMethod = findRuntimeMethod("endReceive", 1);

    private static volatile boolean disabled;
    private static volatile boolean initAttempted;

    private NetTraceRuntimeBridge() {
    }

    public static void init() {
        ensureInitialized();
    }

    public static void hit(int branchId) {
        ensureInitialized();
        invokeVoid(hitMethod, branchId);
    }

    public static void recordSend(String name, int id, Object message, Object... contextArgs) {
        ensureInitialized();
        if (!isEnabled()) {
            return;
        }
        Object[] normalized = normalizeContext(contextArgs);
        if (recordSendMethod != null) {
            Object sendMeta = buildSendMeta(message, normalized);
            invokeRuntimeVoid(recordSendMethod, name, id, message, sendMeta, normalized);
            return;
        }
        invokeRuntimeVoid(recordLegacyMethod, name, id, normalized);
    }

    public static long beginReceive(String name, int id, Object message, Object... contextArgs) {
        ensureInitialized();
        if (!isEnabled() || beginReceiveMethod == null) {
            return -1L;
        }
        Object[] normalized = normalizeContext(contextArgs);
        try {
            Object recvMeta = buildRecvMeta(message, normalized);
            Object ret = beginReceiveMethod.invoke(null, name, id, message, recvMeta, normalized);
            if (ret instanceof Number) {
                return ((Number) ret).longValue();
            }
        } catch (Throwable t) {
            disable(t);
        }
        return -1L;
    }

    public static void endReceive(long token) {
        ensureInitialized();
        if (token <= 0) {
            return;
        }
        invokeRuntimeVoid(endReceiveMethod, token);
    }

    private static void ensureInitialized() {
        if (initAttempted) {
            return;
        }
        synchronized (NetTraceRuntimeBridge.class) {
            if (initAttempted) {
                return;
            }
            initAttempted = true;
            invokeRuntimeVoid(initMethod);
        }
    }

    private static void invokeRuntimeVoid(Method method, Object... args) {
        if (!isEnabled() || method == null) {
            return;
        }
        try {
            method.invoke(null, args);
        } catch (Throwable t) {
            disable(t);
        }
    }

    private static Object[] normalizeContext(Object[] contextArgs) {
        if (contextArgs == null || contextArgs.length == 0) {
            return EMPTY_CONTEXT;
        }
        return Arrays.copyOf(contextArgs, contextArgs.length);
    }

    private static boolean isEnabled() {
        return !disabled && runtimeClass != null;
    }

    private static Method findRuntimeMethod(String name, int parameterCount) {
        if (runtimeClass == null) {
            return null;
        }
        for (Method method : runtimeClass.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                return method;
            }
        }
        return null;
    }

    private static Class<?> loadClass(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object buildSendMeta(Object message, Object[] contextArgs) {
        Object builder = newMetaBuilder(sendMetaClass);
        if (builder == null) {
            return null;
        }
        String nodeId = traceNodeId();
        String peer = detectPeer(message, contextArgs);
        String messageType = detectMessageType(message, contextArgs);
        String messageVersion = detectMessageVersion(message, contextArgs);
        String logicalMessageId = detectLogicalMessageId(message, contextArgs);
        String deliveryId = detectDeliveryId(message, contextArgs, logicalMessageId, peer);
        String channel = detectChannel(contextArgs);
        String protocol = detectProtocol(message, contextArgs);
        String fanoutType = detectFanoutType(contextArgs);
        int targetCount = detectTargetCount(contextArgs);

        setBuilderString(builder, "nodeId", nodeId);
        setBuilderString(builder, "peerId", peer);
        setBuilderString(builder, "channel", channel);
        setBuilderString(builder, "protocol", protocol);
        setBuilderString(builder, "messageType", messageType);
        setBuilderString(builder, "messageVersion", messageVersion);
        setBuilderString(builder, "logicalMessageId", logicalMessageId);
        setBuilderString(builder, "deliveryId", deliveryId);
        setBuilderString(builder, "fanoutType", fanoutType);
        setBuilderInt(builder, "targetCount", targetCount);
        return finishMetaBuilder(builder);
    }

    private static Object buildRecvMeta(Object message, Object[] contextArgs) {
        Object builder = newMetaBuilder(recvMetaClass);
        if (builder == null) {
            return null;
        }
        String nodeId = traceNodeId();
        String peer = detectPeer(message, contextArgs);
        String messageType = detectMessageType(message, contextArgs);
        String messageVersion = detectMessageVersion(message, contextArgs);
        String logicalMessageId = detectLogicalMessageId(message, contextArgs);
        String deliveryId = detectDeliveryId(message, contextArgs, logicalMessageId, peer);
        String channel = detectChannel(contextArgs);
        String protocol = detectProtocol(message, contextArgs);

        setBuilderString(builder, "nodeId", nodeId);
        setBuilderString(builder, "peerId", peer);
        setBuilderString(builder, "channel", channel);
        setBuilderString(builder, "protocol", protocol);
        setBuilderString(builder, "messageType", messageType);
        setBuilderString(builder, "messageVersion", messageVersion);
        setBuilderString(builder, "logicalMessageId", logicalMessageId);
        setBuilderString(builder, "deliveryId", deliveryId);
        return finishMetaBuilder(builder);
    }

    private static Object newMetaBuilder(Class<?> metaClass) {
        if (metaClass == null) {
            return null;
        }
        try {
            Method builderFactory = metaClass.getMethod("builder");
            return builderFactory.invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object finishMetaBuilder(Object builder) {
        if (builder == null) {
            return null;
        }
        try {
            Method build = builder.getClass().getMethod("build");
            return build.invoke(builder);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void setBuilderString(Object builder, String methodName, String value) {
        if (builder == null || value == null || value.isEmpty()) {
            return;
        }
        try {
            Method setter = builder.getClass().getMethod(methodName, String.class);
            setter.invoke(builder, value);
        } catch (Throwable ignored) {
        }
    }

    private static void setBuilderInt(Object builder, String methodName, int value) {
        if (builder == null || value < 0) {
            return;
        }
        try {
            Method setter = builder.getClass().getMethod(methodName, int.class);
            setter.invoke(builder, value);
        } catch (Throwable ignored) {
        }
    }

    private static String traceNodeId() {
        String configured = trimToNull(System.getenv("NET_TRACE_NODE_ID"));
        if (configured != null) {
            return configured;
        }
        String host = trimToNull(System.getenv("HOSTNAME"));
        if (host != null) {
            return host;
        }
        return "unknown";
    }

    private static String detectPeer(Object message, Object[] contextArgs) {
        for (Object arg : contextArgs) {
            String candidate = stringFromAccessor(arg, "getHostAddress", "getHostName",
                    "getAddress", "address", "getRemoteAddress", "remoteAddress", "getPeer",
                    "peer");
            if (candidate != null) {
                return candidate;
            }
            String className = arg.getClass().getName();
            if (className.contains("InetAddress") || className.contains("Address")
                    || className.contains("Endpoint")) {
                String text = trimToNull(String.valueOf(arg));
                if (text != null) {
                    return text;
                }
            }
        }
        String fromMessage = stringFromAccessor(message, "getPeer", "peer", "getTo", "getAddress");
        if (fromMessage != null) {
            return fromMessage;
        }
        return null;
    }

    private static String detectMessageType(Object message, Object[] contextArgs) {
        String fromMessage = stringFromAccessor(message, "verb", "getVerb", "type", "getType",
                "opcode", "getOpcode", "kind", "getKind");
        if (fromMessage != null) {
            return fromMessage;
        }
        for (Object arg : contextArgs) {
            String candidate = stringFromAccessor(arg, "verb", "getVerb", "type", "getType");
            if (candidate != null) {
                return candidate;
            }
        }
        return message != null ? message.getClass().getSimpleName() : null;
    }

    private static String detectMessageVersion(Object message, Object[] contextArgs) {
        String fromMessage = stringFromAccessor(message, "version", "getVersion",
                "protocolVersion", "getProtocolVersion");
        if (fromMessage != null) {
            return fromMessage;
        }
        for (Object arg : contextArgs) {
            String candidate = stringFromAccessor(arg, "version", "getVersion",
                    "protocolVersion", "getProtocolVersion");
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private static String detectLogicalMessageId(Object message, Object[] contextArgs) {
        String fromMessage = stringFromAccessor(message, "id", "getId", "messageId",
                "getMessageId", "streamId", "getStreamId", "sessionId", "getSessionId",
                "tracingId", "getTracingId");
        if (fromMessage != null) {
            return fromMessage;
        }
        for (Object arg : contextArgs) {
            String candidate = stringFromAccessor(arg, "id", "getId", "messageId", "getMessageId",
                    "streamId", "getStreamId", "sessionId", "getSessionId");
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private static String detectDeliveryId(Object message, Object[] contextArgs,
            String logicalMessageId, String peer) {
        String fromMessage = stringFromAccessor(message, "deliveryId", "getDeliveryId", "requestId",
                "getRequestId");
        if (fromMessage != null) {
            return fromMessage;
        }
        for (Object arg : contextArgs) {
            String candidate = stringFromAccessor(arg, "deliveryId", "getDeliveryId", "requestId",
                    "getRequestId");
            if (candidate != null) {
                return candidate;
            }
        }
        if (logicalMessageId != null && peer != null) {
            return logicalMessageId + "@" + peer;
        }
        return logicalMessageId;
    }

    private static String detectChannel(Object[] contextArgs) {
        for (Object arg : contextArgs) {
            String className = arg.getClass().getName();
            if (className.contains("ConnectionType") || className.contains("Channel")
                    || className.contains("Connection")) {
                return arg.getClass().getSimpleName();
            }
        }
        return null;
    }

    private static String detectProtocol(Object message, Object[] contextArgs) {
        String className = message != null ? message.getClass().getName() : "";
        if (className.startsWith("org.apache.cassandra.")) {
            return "cassandra";
        }
        if (className.startsWith("org.apache.hadoop.hbase.")) {
            return "hbase";
        }
        if (className.startsWith("org.apache.hadoop.ipc.")
                || className.startsWith("org.apache.hadoop.hdfs.")) {
            return "hdfs-rpc";
        }
        for (Object arg : contextArgs) {
            String argClass = arg.getClass().getName();
            if (argClass.startsWith("org.apache.cassandra.")) {
                return "cassandra";
            }
            if (argClass.startsWith("org.apache.hadoop.hbase.")) {
                return "hbase";
            }
            if (argClass.startsWith("org.apache.hadoop.ipc.")
                    || argClass.startsWith("org.apache.hadoop.hdfs.")) {
                return "hdfs-rpc";
            }
        }
        return null;
    }

    private static String detectFanoutType(Object[] contextArgs) {
        int targetCount = detectTargetCount(contextArgs);
        if (targetCount > 1) {
            return "MULTICAST";
        }
        if (targetCount == 1) {
            return "UNICAST";
        }
        return "UNKNOWN";
    }

    private static int detectTargetCount(Object[] contextArgs) {
        for (Object arg : contextArgs) {
            if (arg == null) {
                continue;
            }
            if (arg.getClass().isArray()) {
                return Math.max(0, java.lang.reflect.Array.getLength(arg));
            }
            if (arg instanceof Collection<?>) {
                return ((Collection<?>) arg).size();
            }
        }
        return 1;
    }

    private static String stringFromAccessor(Object target, String... methodNames) {
        if (target == null || methodNames == null) {
            return null;
        }
        for (String methodName : methodNames) {
            try {
                Method method = target.getClass().getMethod(methodName);
                Object value = method.invoke(target);
                String text = valueToString(value);
                if (text != null) {
                    return text;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String valueToString(Object value) {
        if (value == null) {
            return null;
        }
        String text;
        if (value instanceof CharSequence || value instanceof Number
                || value instanceof Boolean || value instanceof Enum<?>) {
            text = value.toString();
        } else {
            text = value.getClass().getSimpleName();
        }
        return trimToNull(text);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed;
    }

    private static void disable(Throwable t) {
        disabled = true;
        if (runtimeClass != null) {
            System.err.println("NetTraceRuntimeBridge disabled after reflective invocation failure: " + t);
        }
    }
}
EOF
}

instrument_cassandra() {
  local version="$1"
  local src_root="$2"

  write_bridge \
    "org.apache.cassandra.net" \
    "$src_root/src/java/org/apache/cassandra/net/NetTraceRuntimeBridge.java"

  local messaging_file="$src_root/src/java/org/apache/cassandra/net/MessagingService.java"
  if [[ "$version" == 3.* ]]; then
    replace_literal "$messaging_file" <<'EOF'
        connection.enqueue(message, id);
__NETTRACE_SEP__
        NetTraceRuntimeBridge.hit(3110101);
        NetTraceRuntimeBridge.recordSend("MessagingService.sendOneWay", 3110001, message, message, to, id);
        connection.enqueue(message, id);
EOF

    local incoming_file="$src_root/src/java/org/apache/cassandra/net/IncomingTcpConnection.java"
    replace_literal "$incoming_file" <<'EOF'
            MessagingService.instance().receive(message, id);
__NETTRACE_SEP__
            long netTraceToken = NetTraceRuntimeBridge.beginReceive(
                "IncomingTcpConnection.receiveMessage", 3210001, message, message, from, id, version);
            try
            {
                MessagingService.instance().receive(message, id);
            }
            finally
            {
                NetTraceRuntimeBridge.endReceive(netTraceToken);
            }
EOF
  else
    replace_literal "$messaging_file" <<'EOF'
                connections.enqueue(message, specifyConnection);
__NETTRACE_SEP__
                NetTraceRuntimeBridge.hit(4110101);
                connections.enqueue(message, specifyConnection);
                NetTraceRuntimeBridge.recordSend("MessagingService.doSend", 4110001, message, message, to, specifyConnection);
EOF

    local inbound_file="$src_root/src/java/org/apache/cassandra/net/InboundMessageHandler.java"
    replace_literal "$inbound_file" <<'EOF'
                    consumer.accept(message);
__NETTRACE_SEP__
                    long netTraceToken = NetTraceRuntimeBridge.beginReceive(
                        "InboundMessageHandler.ProcessMessage.run", 4210001, message, message, header, peer, type);
                    try
                    {
                        consumer.accept(message);
                    }
                    finally
                    {
                        NetTraceRuntimeBridge.endReceive(netTraceToken);
                    }
EOF
  fi
}

instrument_hdfs() {
  local _version="$1"
  local src_root="$2"

  write_bridge \
    "org.apache.hadoop.ipc" \
    "$src_root/hadoop-common-project/hadoop-common/src/main/java/org/apache/hadoop/ipc/NetTraceRuntimeBridge.java"

  local client_file="$src_root/hadoop-common-project/hadoop-common/src/main/java/org/apache/hadoop/ipc/Client.java"
  replace_literal "$client_file" <<'EOF'
        connection.sendRpcRequest(call);                 // send the rpc request
__NETTRACE_SEP__
        NetTraceRuntimeBridge.recordSend("Client.call", 1310001, rpcRequest, rpcRequest, remoteId, call);
        connection.sendRpcRequest(call);                 // send the rpc request
        NetTraceRuntimeBridge.hit(1310101);
EOF

  local server_file="$src_root/hadoop-common-project/hadoop-common/src/main/java/org/apache/hadoop/ipc/Server.java"
  replace_literal "$server_file" <<'EOF'
      try {
        internalQueueCall(call);
      } catch (RpcServerException rse) {
        throw rse;
      } catch (IOException ioe) {
        throw new FatalRpcServerException(
            RpcErrorCodeProto.ERROR_RPC_SERVER, ioe);
      }
__NETTRACE_SEP__
      long netTraceToken = NetTraceRuntimeBridge.beginReceive(
          "Server.Connection.processRpcRequest", 1320001, rpcRequest, header, this);
      try {
        try {
          internalQueueCall(call);
        } catch (RpcServerException rse) {
          throw rse;
        } catch (IOException ioe) {
          throw new FatalRpcServerException(
              RpcErrorCodeProto.ERROR_RPC_SERVER, ioe);
        }
      } finally {
        NetTraceRuntimeBridge.endReceive(netTraceToken);
      }
EOF
}

instrument_hbase() {
  local _version="$1"
  local src_root="$2"

  write_bridge \
    "org.apache.hadoop.hbase.ipc" \
    "$src_root/hbase-client/src/main/java/org/apache/hadoop/hbase/ipc/NetTraceRuntimeBridge.java"

  local client_file="$src_root/hbase-client/src/main/java/org/apache/hadoop/hbase/ipc/AbstractRpcClient.java"
  replace_literal "$client_file" <<'EOF'
        connection.sendRequest(call, hrc);
__NETTRACE_SEP__
        NetTraceRuntimeBridge.recordSend("AbstractRpcClient.callMethod", 2310001, param, param, md, addr);
        connection.sendRequest(call, hrc);
        NetTraceRuntimeBridge.hit(2310101);
EOF

  local server_file="$src_root/hbase-server/src/main/java/org/apache/hadoop/hbase/ipc/ServerRpcConnection.java"
  replace_literal "$server_file" <<'EOF'
      if (this.rpcServer.scheduler.dispatch(new CallRunner(this.rpcServer, call))) {
        // unset span do that it's not closed in the finally block
        span = null;
      } else {
        this.rpcServer.callQueueSizeInBytes.add(-1 * call.getSize());
        this.rpcServer.metrics.exception(RpcServer.CALL_QUEUE_TOO_BIG_EXCEPTION);
        call.setResponse(null, null, RpcServer.CALL_QUEUE_TOO_BIG_EXCEPTION,
          "Call queue is full on " + this.rpcServer.server.getServerName()
            + ", too many items queued ?");
        TraceUtil.setError(span, RpcServer.CALL_QUEUE_TOO_BIG_EXCEPTION);
        call.sendResponseIfReady();
      }
__NETTRACE_SEP__
      long netTraceToken = NetTraceRuntimeBridge.beginReceive(
        "ServerRpcConnection.processRequest", 2320001, param, header, this);
      try {
        if (this.rpcServer.scheduler.dispatch(new CallRunner(this.rpcServer, call))) {
          // unset span do that it's not closed in the finally block
          span = null;
        } else {
          this.rpcServer.callQueueSizeInBytes.add(-1 * call.getSize());
          this.rpcServer.metrics.exception(RpcServer.CALL_QUEUE_TOO_BIG_EXCEPTION);
          call.setResponse(null, null, RpcServer.CALL_QUEUE_TOO_BIG_EXCEPTION,
            "Call queue is full on " + this.rpcServer.server.getServerName()
              + ", too many items queued ?");
          TraceUtil.setError(span, RpcServer.CALL_QUEUE_TOO_BIG_EXCEPTION);
          call.sendResponseIfReady();
        }
      } finally {
        NetTraceRuntimeBridge.endReceive(netTraceToken);
      }
EOF
}

build_cassandra() {
  local version="$1"
  local src_root="$2"
  local java_home="$3"
  local log_file="$4"
  if [[ "$version" == 3.* ]]; then
    (cd "$src_root" && JAVA_HOME="$java_home" PATH="$java_home/bin:$PATH" \
      ant -DskipTests=true -Dskip.test=true -Drat.skip=true jar) >"$log_file" 2>&1
  elif [[ "$version" == 4.* ]]; then
    (cd "$src_root" && JAVA_HOME="$java_home" PATH="$java_home/bin:$PATH" \
      ant -Duse.jdk11=true -DskipTests=true -Dskip.test=true -Drat.skip=true _main-jar) >"$log_file" 2>&1
  else
    (cd "$src_root" && JAVA_HOME="$java_home" PATH="$java_home/bin:$PATH" \
      ant -DskipTests=true -Dskip.test=true -Drat.skip=true _main-jar) >"$log_file" 2>&1
  fi
}

build_hdfs() {
  local _version="$1"
  local src_root="$2"
  local java_home="$3"
  local log_file="$4"
  (cd "$src_root" && JAVA_HOME="$java_home" PATH="$java_home/bin:$PATH" \
    mvn -q -DskipTests -pl hadoop-common-project/hadoop-common,hadoop-hdfs-project/hadoop-hdfs,hadoop-hdfs-project/hadoop-hdfs-client -am compile) >"$log_file" 2>&1
}

build_hbase() {
  local version="$1"
  local src_root="$2"
  local java_home="$3"
  local log_file="$4"
  local modules="hbase-common,hbase-client,hbase-server,hbase-protocol-shaded,hbase-protocol"
  local -a mvn_flags=("-q" "-DskipTests" "-Denforcer.skip=true")
  if [[ "$version" == 3.* ]]; then
    modules="hbase-common,hbase-client,hbase-server,hbase-protocol-shaded"
  else
    mvn_flags+=("-Dhadoop.profile=3.0")
  fi
  (cd "$src_root" && JAVA_HOME="$java_home" PATH="$java_home/bin:$PATH" \
    mvn "${mvn_flags[@]}" -pl "$modules" -am compile) >"$log_file" 2>&1
}

cleanup_build_artifacts() {
  local project="$1"
  local src_root="$2"
  if [[ "$project" == "cassandra" ]]; then
    rm -rf "$src_root/build"
  else
    find "$src_root" -type d -name target -prune -exec rm -rf {} +
  fi
}

append_summary() {
  local project="$1"
  local version="$2"
  local profile="$3"
  local java_home="$4"
  local instrument_status="$5"
  local build_status="$6"
  local package_status="$7"
  local output_tar="$8"
  local note="$9"
  printf '%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
    "$project" "$version" "$profile" "$java_home" "$instrument_status" "$build_status" "$package_status" "$output_tar" "$note" \
    >> "$SUMMARY_CSV"
}

run_target() {
  local project="$1"
  local version="$2"
  local src_tar="$3"
  local profile="$4"
  local java_home="$5"

  local target_key="${project}-${version}"
  local unpack_log="$LOG_BASE/${target_key}-unpack.log"
  local inst_log="$LOG_BASE/${target_key}-instrument.log"
  local build_log="$LOG_BASE/${target_key}-build.log"
  local package_log="$LOG_BASE/${target_key}-package.log"
  local work_dir="$WORK_BASE/${target_key}"
  local instrument_status="ok"
  local build_status="ok"
  local package_status="ok"
  local note=""

  rm -rf "$work_dir"
  mkdir -p "$work_dir"

  if [[ ! -f "$src_tar" ]]; then
    append_summary "$project" "$version" "$profile" "$java_home" "fail" "skip" "skip" "" "missing source tar"
    return 1
  fi
  if [[ ! -f "$profile" ]]; then
    append_summary "$project" "$version" "$profile" "$java_home" "fail" "skip" "skip" "" "missing profile"
    return 1
  fi

  if ! tar -xzf "$src_tar" -C "$work_dir" >"$unpack_log" 2>&1; then
    append_summary "$project" "$version" "$profile" "$java_home" "fail" "skip" "skip" "" "failed to unpack"
    return 1
  fi

  local root_name
  root_name="$(tar -tzf "$src_tar" | head -n1 | cut -d/ -f1)"
  local src_root="$work_dir/$root_name"
  if [[ ! -d "$src_root" ]]; then
    append_summary "$project" "$version" "$profile" "$java_home" "fail" "skip" "skip" "" "unpacked root not found"
    return 1
  fi

  case "$project" in
    cassandra)
      if ! instrument_cassandra "$version" "$src_root" >"$inst_log" 2>&1; then
        instrument_status="fail"
      fi
      ;;
    hdfs)
      if ! instrument_hdfs "$version" "$src_root" >"$inst_log" 2>&1; then
        instrument_status="fail"
      fi
      ;;
    hbase)
      if ! instrument_hbase "$version" "$src_root" >"$inst_log" 2>&1; then
        instrument_status="fail"
      fi
      ;;
    *)
      instrument_status="fail"
      ;;
  esac

  if [[ "$instrument_status" == "ok" ]]; then
    case "$project" in
      cassandra)
        if ! build_cassandra "$version" "$src_root" "$java_home" "$build_log"; then
          build_status="fail"
        fi
        ;;
      hdfs)
        if ! build_hdfs "$version" "$src_root" "$java_home" "$build_log"; then
          build_status="fail"
        fi
        ;;
      hbase)
        if ! build_hbase "$version" "$src_root" "$java_home" "$build_log"; then
          build_status="fail"
        fi
        ;;
      *)
        build_status="fail"
        ;;
    esac
  else
    build_status="skip"
  fi

  cleanup_build_artifacts "$project" "$src_root"

  local out_tar="${src_tar%.tar.gz}-instrumented.tar.gz"
  rm -f "$out_tar"
  if ! tar -czf "$out_tar" -C "$work_dir" "$root_name" >"$package_log" 2>&1; then
    package_status="fail"
  fi

  if [[ "$instrument_status" != "ok" ]]; then
    note="instrumentation failed (see $inst_log)"
  elif [[ "$build_status" != "ok" ]]; then
    note="build failed (see $build_log)"
  elif [[ "$package_status" != "ok" ]]; then
    note="packaging failed (see $package_log)"
  fi

  append_summary \
    "$project" "$version" "$profile" "$java_home" \
    "$instrument_status" "$build_status" "$package_status" "$out_tar" "$note"

  if [[ "$instrument_status" != "ok" || "$build_status" != "ok" || "$package_status" != "ok" ]]; then
    return 1
  fi
  return 0
}

main() {
  echo "project,version,profile,java_home,instrument_status,build_status,package_status,output_tar,note" > "$SUMMARY_CSV"

  local failures=0
  local selected="${NETTRACE_TARGETS:-all}"

  should_run() {
    local key="$1"
    if [[ "$selected" == "all" ]]; then
      return 0
    fi
    case ",$selected," in
      *",$key,"*) return 0 ;;
      *) return 1 ;;
    esac
  }

  if should_run "cassandra-3.11.19"; then
    run_target cassandra 3.11.19 \
      "$PREBUILD_DIR/cassandra/apache-cassandra-3.11.19-src.tar.gz" \
      "$PROFILE_DIR/cassandra-3.11.19.yaml" \
      "$JAVA8" || failures=$((failures + 1))
  fi

  if should_run "cassandra-4.1.10"; then
    run_target cassandra 4.1.10 \
      "$PREBUILD_DIR/cassandra/apache-cassandra-4.1.10-src.tar.gz" \
      "$PROFILE_DIR/cassandra-4.1.10.yaml" \
      "$JAVA11" || failures=$((failures + 1))
  fi

  if should_run "cassandra-5.0.6"; then
    run_target cassandra 5.0.6 \
      "$PREBUILD_DIR/cassandra/apache-cassandra-5.0.6-src.tar.gz" \
      "$PROFILE_DIR/cassandra-5.0.6.yaml" \
      "$JAVA17" || failures=$((failures + 1))
  fi

  if should_run "hdfs-2.10.2"; then
    run_target hdfs 2.10.2 \
      "$PREBUILD_DIR/hdfs/hadoop-2.10.2-src.tar.gz" \
      "$PROFILE_DIR/hadoop-2.10.2.yaml" \
      "$JAVA8" || failures=$((failures + 1))
  fi

  if should_run "hdfs-3.3.6"; then
    run_target hdfs 3.3.6 \
      "$PREBUILD_DIR/hdfs/hadoop-3.3.6-src.tar.gz" \
      "$PROFILE_DIR/hadoop-3.3.6.yaml" \
      "$JAVA11" || failures=$((failures + 1))
  fi

  if should_run "hdfs-3.4.2"; then
    run_target hdfs 3.4.2 \
      "$PREBUILD_DIR/hdfs/hadoop-3.4.2-src.tar.gz" \
      "$PROFILE_DIR/hadoop-3.4.2.yaml" \
      "$JAVA11" || failures=$((failures + 1))
  fi

  if should_run "hbase-2.5.13"; then
    run_target hbase 2.5.13 \
      "$PREBUILD_DIR/hbase/hbase-2.5.13-src.tar.gz" \
      "$PROFILE_DIR/hbase-2.5.13.yaml" \
      "$JAVA11" || failures=$((failures + 1))
  fi

  if should_run "hbase-2.6.4"; then
    run_target hbase 2.6.4 \
      "$PREBUILD_DIR/hbase/hbase-2.6.4-src.tar.gz" \
      "$PROFILE_DIR/hbase-2.6.4.yaml" \
      "$JAVA11" || failures=$((failures + 1))
  fi

  if should_run "hbase-3.0.0-beta-1"; then
    run_target hbase 3.0.0-beta-1 \
      "$PREBUILD_DIR/hbase/hbase-3.0.0-beta-1-src.tar.gz" \
      "$PROFILE_DIR/hbase-3.0.0-beta-1.yaml" \
      "$JAVA17" || failures=$((failures + 1))
  fi

  cat "$SUMMARY_CSV"

  if [[ "$failures" -gt 0 ]]; then
    return 1
  fi
  return 0
}

main "$@"
