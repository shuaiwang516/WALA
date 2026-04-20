#!/usr/bin/env bash
#
# Phase 2 bridge-path smoke test: verifies that the generated
# NetTraceRuntimeBridge.detectLogicalMessageId picks `scannerId` over
# `id`/`callId` when a target object carries both — the case that
# matters for HBase scan traffic. The test is small enough to run
# without extracting any source tarball; it only needs the generator
# function from instrument_prebuild_matrix.sh plus a JDK to compile a
# synthetic target class.
#
# Exit code is 0 on success, non-zero on failure.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NETTRACE_SCRIPTS="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
JAVA11_HOME="${JAVA11_HOME:-/usr/lib/jvm/java-1.11.0-openjdk-amd64}"
JAVAC="$JAVA11_HOME/bin/javac"
JAVA="$JAVA11_HOME/bin/java"

SSG_JAR="$ROOT/ssg-runtime-shuai/build/libs/ssgFatJar.jar"
if [[ ! -f "$SSG_JAR" ]]; then
  echo "[FAIL] ssgFatJar.jar not found at $SSG_JAR — run 'cd ssg-runtime-shuai && ./gradlew fatJar' first" >&2
  exit 2
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
GEN="$WORK/gen"
OUT="$WORK/out"
mkdir -p "$GEN/org/zlab/smoke" "$OUT"

python3 - "$NETTRACE_SCRIPTS/instrument_prebuild_matrix.sh" "$WORK/write_bridge.func" <<'PY'
import re, sys, pathlib
src = pathlib.Path(sys.argv[1]).read_text()
start = src.index("write_bridge() {")
rest = src[start:]
match = re.search(r"\n(?!\})[A-Za-z_][A-Za-z0-9_]*\(\)\s*\{",
                  rest[len("write_bridge() {"):])
if match is None:
    raise SystemExit("could not locate next top-level function after write_bridge")
end_offset = match.start() + len("write_bridge() {")
pathlib.Path(sys.argv[2]).write_text(rest[:end_offset].rstrip() + "\n")
PY

# shellcheck disable=SC1090
source "$WORK/write_bridge.func"
write_bridge "org.zlab.smoke" "$GEN/org/zlab/smoke/NetTraceRuntimeBridge.java"

# Synthetic scan-like target: both scannerId and id are populated. The
# bridge must surface the scannerId via detectLogicalMessageId.
cat > "$GEN/org/zlab/smoke/ScanLikeTarget.java" <<'JAVA'
package org.zlab.smoke;

public final class ScanLikeTarget {
    public long getScannerId() { return 101L; }
    public long getId()        { return 77L; }
    public long getCallId()    { return 55L; }
}

class CallLikeTarget {
    public long getId() { return 42L; }
}
JAVA

# Driver that pokes the package-private helper via reflection so we do
# not have to expose it. The helper is package-private by design —
# production use flows through buildSendMeta / buildRecvMeta.
cat > "$GEN/org/zlab/smoke/BridgeSmoke.java" <<'JAVA'
package org.zlab.smoke;

import java.lang.reflect.Method;

public final class BridgeSmoke {
    public static void main(String[] args) throws Exception {
        Method m = NetTraceRuntimeBridge.class.getDeclaredMethod(
                "detectLogicalMessageId", Object.class, Object[].class);
        m.setAccessible(true);

        Object scanTarget = new ScanLikeTarget();
        String scanResult = (String) m.invoke(null, scanTarget, new Object[] {});
        if (!"101".equals(scanResult)) {
            System.err.println("FAIL scannerId priority: expected 101, got " + scanResult);
            System.exit(3);
        }

        Object callTarget = new CallLikeTarget();
        String callResult = (String) m.invoke(null, callTarget, new Object[] {});
        if (!"42".equals(callResult)) {
            System.err.println("FAIL non-scan fallback: expected 42, got " + callResult);
            System.exit(4);
        }

        // Also verify a null-target case does not crash.
        String nullResult = (String) m.invoke(null, null, new Object[] {});
        if (nullResult != null) {
            System.err.println("FAIL null target: expected null, got " + nullResult);
            System.exit(5);
        }

        System.out.println("OK scannerId wins on mixed-target: " + scanResult);
        System.out.println("OK id fallback on non-scan:        " + callResult);
    }
}
JAVA

"$JAVAC" -cp "$SSG_JAR" -d "$OUT" \
    "$GEN/org/zlab/smoke/NetTraceRuntimeBridge.java" \
    "$GEN/org/zlab/smoke/ScanLikeTarget.java" \
    "$GEN/org/zlab/smoke/BridgeSmoke.java"

"$JAVA" -cp "$OUT:$SSG_JAR" org.zlab.smoke.BridgeSmoke
