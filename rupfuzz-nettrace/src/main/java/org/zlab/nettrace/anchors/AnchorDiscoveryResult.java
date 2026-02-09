package org.zlab.nettrace.anchors;

import java.util.List;

public final class AnchorDiscoveryResult {
  private final List<RawAnchor> rawSendAnchors;
  private final List<RawAnchor> rawRecvAnchors;
  private final List<ResolvedAnchor> resolvedSendAnchors;
  private final List<ResolvedAnchor> resolvedRecvAnchors;

  public AnchorDiscoveryResult(
      List<RawAnchor> rawSendAnchors,
      List<RawAnchor> rawRecvAnchors,
      List<ResolvedAnchor> resolvedSendAnchors,
      List<ResolvedAnchor> resolvedRecvAnchors) {
    this.rawSendAnchors = List.copyOf(rawSendAnchors);
    this.rawRecvAnchors = List.copyOf(rawRecvAnchors);
    this.resolvedSendAnchors = List.copyOf(resolvedSendAnchors);
    this.resolvedRecvAnchors = List.copyOf(resolvedRecvAnchors);
  }

  public List<RawAnchor> rawSendAnchors() {
    return rawSendAnchors;
  }

  public List<RawAnchor> rawRecvAnchors() {
    return rawRecvAnchors;
  }

  public List<ResolvedAnchor> resolvedSendAnchors() {
    return resolvedSendAnchors;
  }

  public List<ResolvedAnchor> resolvedRecvAnchors() {
    return resolvedRecvAnchors;
  }
}
