package org.zlab.nettrace.analysis;

import java.util.Locale;

public enum EntrypointMode {
  MAIN("main"),
  ALL_APPLICATION("all-application"),
  PROFILE_SEEDED("profile-seeded");

  private final String cliName;

  EntrypointMode(String cliName) {
    this.cliName = cliName;
  }

  public String cliName() {
    return cliName;
  }

  public static EntrypointMode parse(String value) {
    if (value == null || value.isBlank()) {
      return PROFILE_SEEDED;
    }

    String normalized = value.trim().toLowerCase(Locale.ROOT);
    for (EntrypointMode mode : values()) {
      if (mode.cliName.equals(normalized)) {
        return mode;
      }
    }

    throw new IllegalArgumentException(
        "Unsupported entrypoint mode: "
            + value
            + " (expected main|all-application|profile-seeded)");
  }
}
