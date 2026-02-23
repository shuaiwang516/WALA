package org.zlab.nettrace.analysis;

public final class Phase2Diagnostics {
  private final int resolvedSend;
  private final int resolvedRecv;
  private final int fallbackSend;
  private final int fallbackRecv;

  public Phase2Diagnostics(int resolvedSend, int resolvedRecv, int fallbackSend, int fallbackRecv) {
    this.resolvedSend = resolvedSend;
    this.resolvedRecv = resolvedRecv;
    this.fallbackSend = fallbackSend;
    this.fallbackRecv = fallbackRecv;
  }

  public int resolvedSend() {
    return resolvedSend;
  }

  public int resolvedRecv() {
    return resolvedRecv;
  }

  public int fallbackSend() {
    return fallbackSend;
  }

  public int fallbackRecv() {
    return fallbackRecv;
  }
}
