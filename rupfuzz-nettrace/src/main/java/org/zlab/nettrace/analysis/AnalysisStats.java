package org.zlab.nettrace.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AnalysisStats {
  public String precision;
  public int nCfaLevel;
  public String entrypointMode;
  public long scopeBuildMillis;
  public long chaBuildMillis;
  public long callGraphBuildMillis;
  public long totalMillis;

  public int classHierarchyClassCount;
  public int applicationClassCount;
  public int callGraphNodeCount;
  public int applicationCallGraphNodeCount;
  public int pointerKeyCount;
  public int instanceKeyCount;
  public int entrypointCount;

  public int rawSendAnchorCount;
  public int rawRecvAnchorCount;
  public int phase2ResolvedSendCount;
  public int phase2ResolvedRecvCount;
  public int phase2FallbackSendCount;
  public int phase2FallbackRecvCount;

  private final List<String> notes = new ArrayList<>();

  public void addNote(String note) {
    notes.add(note);
  }

  public List<String> notes() {
    return Collections.unmodifiableList(notes);
  }
}
