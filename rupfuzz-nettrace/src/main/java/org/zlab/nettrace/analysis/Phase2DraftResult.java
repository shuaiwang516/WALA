package org.zlab.nettrace.analysis;

import java.util.List;

public final class Phase2DraftResult {
  private final List<SendPoint> sendPoints;
  private final List<RecvBeginPoint> recvBeginPoints;
  private final List<RecvEndPoint> recvEndPoints;

  public Phase2DraftResult(
      List<SendPoint> sendPoints,
      List<RecvBeginPoint> recvBeginPoints,
      List<RecvEndPoint> recvEndPoints) {
    this.sendPoints = List.copyOf(sendPoints);
    this.recvBeginPoints = List.copyOf(recvBeginPoints);
    this.recvEndPoints = List.copyOf(recvEndPoints);
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
}
