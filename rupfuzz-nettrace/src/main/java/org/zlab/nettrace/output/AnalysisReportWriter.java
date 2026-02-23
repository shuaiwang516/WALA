package org.zlab.nettrace.output;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.zlab.nettrace.analysis.AnalysisConfig;
import org.zlab.nettrace.analysis.AnalysisStats;

public final class AnalysisReportWriter {
  private AnalysisReportWriter() {}

  public static void write(Path reportPath, AnalysisConfig config, AnalysisStats stats)
      throws IOException {
    Path parent = reportPath.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }

    StringBuilder builder = new StringBuilder();
    builder.append("# Nettrace Analysis Report\n\n");
    builder.append("- Generated: ").append(Instant.now()).append("\n");
    builder.append("- Precision mode: `").append(stats.precision).append("`\n");
    builder.append("- n-CFA level: `").append(stats.nCfaLevel).append("`\n");
    builder.append("- Entrypoint mode: `").append(stats.entrypointMode).append("`\n");
    builder.append("- Output dir: `").append(config.outputDir()).append("`\n");
    builder.append("- Profile: `").append(config.profile() == null ? "none" : config.profile()).append("`\n");
    builder.append("\n");

    builder.append("## Scope and Graph Stats\n\n");
    builder.append("- CHA classes: ").append(stats.classHierarchyClassCount).append("\n");
    builder.append("- Application classes: ").append(stats.applicationClassCount).append("\n");
    builder.append("- Entrypoints: ").append(stats.entrypointCount).append("\n");
    builder.append("- Call graph nodes: ").append(stats.callGraphNodeCount).append("\n");
    builder.append("- Application call graph nodes: ").append(stats.applicationCallGraphNodeCount).append("\n");
    builder.append("- Pointer keys: ").append(stats.pointerKeyCount).append("\n");
    builder.append("- Instance keys: ").append(stats.instanceKeyCount).append("\n");
    builder.append("\n");

    builder.append("## Anchor Stats\n\n");
    builder.append("- Raw send anchors: ").append(stats.rawSendAnchorCount).append("\n");
    builder.append("- Raw recv anchors: ").append(stats.rawRecvAnchorCount).append("\n");
    builder.append("- Phase2 resolved send: ").append(stats.phase2ResolvedSendCount).append("\n");
    builder.append("- Phase2 resolved recv: ").append(stats.phase2ResolvedRecvCount).append("\n");
    builder.append("- Phase2 fallback send: ").append(stats.phase2FallbackSendCount).append("\n");
    builder.append("- Phase2 fallback recv: ").append(stats.phase2FallbackRecvCount).append("\n");
    builder.append("\n");

    builder.append("## Timings\n\n");
    builder.append("- Scope build ms: ").append(stats.scopeBuildMillis).append("\n");
    builder.append("- CHA build ms: ").append(stats.chaBuildMillis).append("\n");
    builder.append("- Call graph build ms: ").append(stats.callGraphBuildMillis).append("\n");
    builder.append("- Total ms: ").append(stats.totalMillis).append("\n");

    List<String> notes = stats.notes();
    if (!notes.isEmpty()) {
      builder.append("\n## Notes\n\n");
      for (String note : notes) {
        builder.append("- ").append(note).append("\n");
      }
    }

    Files.writeString(reportPath, builder.toString(), StandardCharsets.UTF_8);
  }
}
