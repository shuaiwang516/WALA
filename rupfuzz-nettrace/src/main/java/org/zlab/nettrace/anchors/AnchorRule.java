package org.zlab.nettrace.anchors;

public final class AnchorRule {
  private final String ownerPattern;
  private final String methodPattern;
  private final String descriptorPattern;
  private final String reason;

  public AnchorRule(
      String ownerPattern, String methodPattern, String descriptorPattern, String reason) {
    this.ownerPattern = ownerPattern;
    this.methodPattern = methodPattern;
    this.descriptorPattern = descriptorPattern;
    this.reason = reason;
  }

  public String ownerPattern() {
    return ownerPattern;
  }

  public String methodPattern() {
    return methodPattern;
  }

  public String descriptorPattern() {
    return descriptorPattern;
  }

  public String reason() {
    return reason;
  }
}
