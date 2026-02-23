# NetTrace Instrumentation Practicality Notes

## Scope

This note summarizes practical limitations observed while automating source instrumentation for:
- Cassandra (3.11.19, 4.1.10, 5.0.6)
- HDFS/Hadoop (2.10.2, 3.3.6, 3.4.2)
- HBase (2.5.13, 2.6.4, 3.0.0-beta-1)

## Current Limitations

1. Source patching is literal-anchor based.
- The automation currently patches exact source snippets.
- It is robust for the tested versions, but can break on upstream formatting/refactoring even if semantics are unchanged.

2. Runtime hook coverage is intentionally minimal.
- Each system currently uses a small number of stable send/receive hook points.
- This is good for cross-version portability, but does not exhaustively instrument every profile rule candidate.

3. Static-profile-to-runtime mapping is not yet one-to-one.
- Profiles are used to select version and family-specific instrumentation run configuration.
- The injected hooks are representative high-value points, not a full direct translation of every profile rule entry into instrumentation calls.

4. Verification here is build-level, not runtime-traffic-level for all 9.
- The matrix validates instrument+compile+package for all versions.
- Runtime distributed validation (actual Send/Receive/Use trace checks) is still done per-system integration tests and should be expanded.

## Recommended Next Improvements

1. Move from literal source patching to bytecode instrumentation driven by analysis outputs.
- Use `netSendPoints.json/netRecvBeginPoints.json/netRecvEndPoints.json` directly.
- Reduce source-fragility and improve scalability for new versions.

2. Add a deterministic mapping layer from profile rules to instrumentation IDs.
- Persist mapping artifacts for reproducibility and debugging.

3. Add runtime smoke tests for each family/version in CI-like matrix.
- At least one RPC exchange per version to verify emitted `NETTRACE SEND/RECV_BEGIN/RECV_END` events.

4. Add fallback-aware patching mode.
- If exact anchors fail, support structured parsing (AST or Javaparser-style) to patch semantically instead of textually.
