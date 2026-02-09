package org.zlab.nettrace.analysis;

public final class RecvEndPoint {
  private final String beginPointId;
  private final String endPointId;
  private final String className;
  private final String method;
  private final int line;
  private final String timeoutPolicy;

  public RecvEndPoint(
      String beginPointId,
      String endPointId,
      String className,
      String method,
      int line,
      String timeoutPolicy) {
    this.beginPointId = beginPointId;
    this.endPointId = endPointId;
    this.className = className;
    this.method = method;
    this.line = line;
    this.timeoutPolicy = timeoutPolicy;
  }

  public String beginPointId() {
    return beginPointId;
  }

  public String endPointId() {
    return endPointId;
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

  public String timeoutPolicy() {
    return timeoutPolicy;
  }
}
