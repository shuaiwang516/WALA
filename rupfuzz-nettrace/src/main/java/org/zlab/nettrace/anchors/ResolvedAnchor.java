package org.zlab.nettrace.anchors;

import com.ibm.wala.ipa.callgraph.CGNode;

public final class ResolvedAnchor {
  private final RawAnchor rawAnchor;
  private final CGNode node;
  private final int instructionIndex;

  public ResolvedAnchor(RawAnchor rawAnchor, CGNode node, int instructionIndex) {
    this.rawAnchor = rawAnchor;
    this.node = node;
    this.instructionIndex = instructionIndex;
  }

  public RawAnchor rawAnchor() {
    return rawAnchor;
  }

  public CGNode node() {
    return node;
  }

  public int instructionIndex() {
    return instructionIndex;
  }
}
