# rupfuzz-nettrace

`rupfuzz-nettrace` is a WALA-based network-position analysis CLI for Phase 0/1 (with draft Phase 2 outputs).

## Build

```bash
./gradlew :rupfuzz-nettrace:build
```

## Run (generic)

```bash
./gradlew :rupfuzz-nettrace:run --args=' \
  --app-classpath /path/to/app/classes:/path/to/deps/* \
  --main-class com.example.Main \
  --target-prefix com.example \
  --precision zero-one-cfa \
  --output-dir output'
```

## Run with profile

```bash
./gradlew :rupfuzz-nettrace:run --args=' \
  --app-classpath /path/to/app.jar:/path/to/deps/* \
  --entrypoint-config /path/to/entrypoints.yaml \
  --profile cassandra-3.x \
  --profiles-dir rupfuzz-nettrace/profiles \
  --precision zero-one-container-cfa \
  --output-dir output'
```

## Outputs

The CLI writes under `output/` by default:

- `netAnalysisReport.md`
- `rawSendAnchors.json`
- `rawRecvAnchors.json`
- `rawAnchorSchema.json`
- `netSendPoints.json` (draft Phase 2)
- `netRecvBeginPoints.json` (draft Phase 2)
- `netRecvEndPoints.json` (draft Phase 2)
