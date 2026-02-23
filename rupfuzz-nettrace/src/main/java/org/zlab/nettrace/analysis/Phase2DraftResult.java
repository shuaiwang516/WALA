package org.zlab.nettrace.analysis;

import java.util.List;

public final class Phase2DraftResult {
  private final List<SendPoint> sendPoints;
  private final List<RecvBeginPoint> recvBeginPoints;
  private final List<RecvEndPoint> recvEndPoints;
  private final Phase2Diagnostics diagnostics;

  public Phase2DraftResult(
      List<SendPoint> sendPoints,
      List<RecvBeginPoint> recvBeginPoints,
      List<RecvEndPoint> recvEndPoints,
      Phase2Diagnostics diagnostics) {
    this.sendPoints = List.copyOf(sendPoints);
    this.recvBeginPoints = List.copyOf(recvBeginPoints);
    this.recvEndPoints = List.copyOf(recvEndPoints);
    this.diagnostics = diagnostics;
  }

  public List<SendPoint> sendPoints() {
    return sendPoints;
  }

  public List<RecvBeginPoint> recvBeginPoints() {
    return recvBeginPoints;
  }

  public List<RecvEndPoint> recvEndPoints() {
    return recvEndPoints;
  }

  public Phase2Diagnostics diagnostics() {
    return diagnostics;
  }
}
