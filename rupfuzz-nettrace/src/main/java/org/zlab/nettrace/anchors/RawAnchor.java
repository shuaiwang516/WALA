package org.zlab.nettrace.anchors;

public final class RawAnchor {
  private final String anchorId;
  private final AnchorRole role;
  private final AnchorSource source;
  private final String profile;
  private final String reason;
  private final String callerClass;
  private final String callerMethod;
  private final String callerDescriptor;
  private final int lineNumber;
  private final int instructionIndex;
  private final String invokedClass;
  private final String invokedMethod;
  private final String invokedDescriptor;
  private final double confidence;

  public RawAnchor(
      String anchorId,
      AnchorRole role,
      AnchorSource source,
      String profile,
      String reason,
      String callerClass,
      String callerMethod,
      String callerDescriptor,
      int lineNumber,
      int instructionIndex,
      String invokedClass,
      String invokedMethod,
      String invokedDescriptor,
      double confidence) {
    this.anchorId = anchorId;
    this.role = role;
    this.source = source;
    this.profile = profile;
    this.reason = reason;
    this.callerClass = callerClass;
    this.callerMethod = callerMethod;
    this.callerDescriptor = callerDescriptor;
    this.lineNumber = lineNumber;
    this.instructionIndex = instructionIndex;
    this.invokedClass = invokedClass;
    this.invokedMethod = invokedMethod;
    this.invokedDescriptor = invokedDescriptor;
    this.confidence = confidence;
  }

  public String anchorId() {
    return anchorId;
  }

  public AnchorRole role() {
    return role;
  }

  public AnchorSource source() {
    return source;
  }

  public String profile() {
    return profile;
  }

  public String reason() {
    return reason;
  }

  public String callerClass() {
    return callerClass;
  }

  public String callerMethod() {
    return callerMethod;
  }

  public String callerDescriptor() {
    return callerDescriptor;
  }

  public int lineNumber() {
    return lineNumber;
  }

  public int instructionIndex() {
    return instructionIndex;
  }

  public String invokedClass() {
    return invokedClass;
  }

  public String invokedMethod() {
    return invokedMethod;
  }

  public String invokedDescriptor() {
    return invokedDescriptor;
  }

  public double confidence() {
    return confidence;
  }
}
