package org.zlab.nettrace.anchors;

public final class GenericMatch {
  private final AnchorRole role;
  private final String reason;
  private final double confidence;

  public GenericMatch(AnchorRole role, String reason, double confidence) {
    this.role = role;
    this.reason = reason;
    this.confidence = confidence;
  }

  public AnchorRole role() {
    return role;
  }

  public String reason() {
    return reason;
  }

  public double confidence() {
    return confidence;
  }
}
