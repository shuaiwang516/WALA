package org.zlab.nettrace.analysis;

public final class RecvBeginPoint {
  private final String pointId;
  private final String className;
  private final String method;
  private final int line;
  private final String unit;
  private final String messageVar;
  private final double confidence;
  private final String profileTag;

  public RecvBeginPoint(
      String pointId,
      String className,
      String method,
      int line,
      String unit,
      String messageVar,
      double confidence,
      String profileTag) {
    this.pointId = pointId;
    this.className = className;
    this.method = method;
    this.line = line;
    this.unit = unit;
    this.messageVar = messageVar;
    this.confidence = confidence;
    this.profileTag = profileTag;
  }

  public String pointId() {
    return pointId;
  }

  public String className() {
    return className;
  }

  public String method() {
    return method;
  }

  public int line() {
    return line;
  }

  public String unit() {
    return unit;
  }

  public String messageVar() {
    return messageVar;
  }

  public double confidence() {
    return confidence;
  }

  public String profileTag() {
    return profileTag;
  }
}
