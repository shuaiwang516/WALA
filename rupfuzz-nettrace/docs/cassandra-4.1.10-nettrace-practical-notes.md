# Cassandra 4.1.10 NetTrace Practical Notes

This note captures practical issues observed during the real 2-node Cassandra 4.1.10 integration test.

## 1) Java 17 startup compatibility is fragile

For this source build/run path, Cassandra 4.1.10 required runtime JVM option adjustments in container entrypoint:

- Disable CMS flags in `jvm11-server.options`, enable G1.
- Add module opens for reflective access used by Cassandra/Jamm:
  - `java.base/java.io`
  - `java.base/java.lang`
  - `java.base/java.nio`
  - `java.base/java.util`
  - `java.base/java.util.concurrent`
  - `java.base/java.util.concurrent.atomic`
  - `java.base/java.util.concurrent.locks`
  - `java.base/sun.nio.ch`
  - `java.base/sun.nio.fs`

Recommendation: keep these Java-17-specific adjustments centralized in entrypoint logic (or a dedicated Java-17 profile) instead of scattering ad-hoc patches.

## 2) Container image needs `python3-six` for `cqlsh`

`cqlsh` failed initially with `ModuleNotFoundError: No module named 'six'`.

Recommendation: include `python3-six` by default in Cassandra integration image dependencies.

## 3) Runtime context fields are not yet practical (`peer`, `msgType`)

Current logs show `peer=null` and `msgType=null` for send/receive events.

Recommendation: extend instrumentation bridge to extract and pass:

- peer endpoint (remote address / host ID / port),
- message verb/type,
- optionally request correlation metadata from Cassandra message objects.

This would significantly improve downstream usefulness of network traces.

## 4) Integration workflow is correct but expensive per run

`run_demo.sh` rebuilds Cassandra jar + Docker image each invocation.

Recommendation: add a fast path (cache stamp + `--no-build` option) for repeated validation loops once instrumentation code is unchanged.

## 5) Current test validates core signal, not full semantic fidelity

The passing criterion confirms non-zero and balanced counts for:

- `NETTRACE SEND`
- `NETTRACE RECV_BEGIN`
- `NETTRACE RECV_END`
- `afterLen > 0` ("Use")

Recommendation: add semantic assertions in a future iteration:

- pair send/receive by message metadata (once fields above are available),
- verify latency/timing windows,
- verify branch-context relevance for selected operation types.
