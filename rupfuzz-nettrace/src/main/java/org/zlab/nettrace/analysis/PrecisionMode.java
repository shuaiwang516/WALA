package org.zlab.nettrace.analysis;

public enum PrecisionMode {
  RTA("rta"),
  ZERO_CFA("zero-cfa"),
  ZERO_ONE_CFA("zero-one-cfa"),
  ZERO_ONE_CONTAINER_CFA("zero-one-container-cfa"),
  N_CFA("n-cfa");

  private final String cliName;

  PrecisionMode(String cliName) {
    this.cliName = cliName;
  }

  public String cliName() {
    return cliName;
  }

  public static PrecisionMode parse(String value) {
    for (PrecisionMode mode : values()) {
      if (mode.cliName.equalsIgnoreCase(value)) {
        return mode;
      }
    }
    throw new IllegalArgumentException("Unsupported precision mode: " + value);
  }
}
