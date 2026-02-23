package org.zlab.nettrace.analysis;

import java.nio.file.Path;
import java.util.List;

public final class AnalysisConfig {
  private final String appClasspath;
  private final List<Path> inputJars;
  private final List<String> mainClasses;
  private final Path entrypointConfig;
  private final List<String> targetPrefixes;
  private final List<String> excludePrefixes;
  private final Path exclusionsFile;
  private final Path outputDir;
  private final PrecisionMode precisionMode;
  private final int nCfaLevel;
  private final EntrypointMode entrypointMode;
  private final String profile;
  private final Path profilesDir;
  private final boolean phase2Draft;

  public AnalysisConfig(
      String appClasspath,
      List<Path> inputJars,
      List<String> mainClasses,
      Path entrypointConfig,
      List<String> targetPrefixes,
      List<String> excludePrefixes,
      Path exclusionsFile,
      Path outputDir,
      PrecisionMode precisionMode,
      int nCfaLevel,
      EntrypointMode entrypointMode,
      String profile,
      Path profilesDir,
      boolean phase2Draft) {
    this.appClasspath = appClasspath;
    this.inputJars = List.copyOf(inputJars);
    this.mainClasses = List.copyOf(mainClasses);
    this.entrypointConfig = entrypointConfig;
    this.targetPrefixes = List.copyOf(targetPrefixes);
    this.excludePrefixes = List.copyOf(excludePrefixes);
    this.exclusionsFile = exclusionsFile;
    this.outputDir = outputDir;
    this.precisionMode = precisionMode;
    this.nCfaLevel = nCfaLevel;
    this.entrypointMode = entrypointMode;
    this.profile = profile;
    this.profilesDir = profilesDir;
    this.phase2Draft = phase2Draft;
  }

  public String appClasspath() {
    return appClasspath;
  }

  public List<Path> inputJars() {
    return inputJars;
  }

  public List<String> mainClasses() {
    return mainClasses;
  }

  public Path entrypointConfig() {
    return entrypointConfig;
  }

  public List<String> targetPrefixes() {
    return targetPrefixes;
  }

  public List<String> excludePrefixes() {
    return excludePrefixes;
  }

  public Path exclusionsFile() {
    return exclusionsFile;
  }

  public Path outputDir() {
    return outputDir;
  }

  public PrecisionMode precisionMode() {
    return precisionMode;
  }

  public int nCfaLevel() {
    return nCfaLevel;
  }

  public EntrypointMode entrypointMode() {
    return entrypointMode;
  }

  public String profile() {
    return profile;
  }

  public Path profilesDir() {
    return profilesDir;
  }

  public boolean phase2Draft() {
    return phase2Draft;
  }
}
