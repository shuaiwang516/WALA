package org.zlab.nettrace.analysis;

import com.ibm.wala.classLoader.Language;
import com.ibm.wala.core.util.config.AnalysisScopeReader;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.impl.AllApplicationEntrypoints;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.ipa.callgraph.propagation.SSAPropagationCallGraphBuilder;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.config.PatternsFilter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class WalaAnalysisBuilder {
  public WalaArtifacts build(AnalysisConfig config, AnalysisStats stats)
      throws IOException, CancelException, ClassHierarchyException {
    long start = System.currentTimeMillis();

    long scopeStart = System.currentTimeMillis();
    AnalysisScope scope = buildScope(config);
    stats.scopeBuildMillis = System.currentTimeMillis() - scopeStart;

    long chaStart = System.currentTimeMillis();
    IClassHierarchy cha = ClassHierarchyFactory.make(scope);
    stats.chaBuildMillis = System.currentTimeMillis() - chaStart;

    List<String> mainClasses = new ArrayList<>(config.mainClasses());
    if (config.entrypointConfig() != null) {
      mainClasses.addAll(EntrypointConfigLoader.loadMainClasses(config.entrypointConfig()));
    }

    List<Entrypoint> entrypoints = buildEntrypoints(scope, cha, mainClasses, stats);
    stats.entrypointCount = entrypoints.size();
    AnalysisOptions options = new AnalysisOptions(scope, entrypoints);

    long cgStart = System.currentTimeMillis();
    SSAPropagationCallGraphBuilder builder = makeBuilder(config, options, cha);
    CallGraph cg = builder.makeCallGraph(options, null);
    PointerAnalysis<InstanceKey> pointerAnalysis = builder.getPointerAnalysis();
    stats.callGraphBuildMillis = System.currentTimeMillis() - cgStart;

    collectStats(scope, cha, cg, pointerAnalysis, stats);
    stats.totalMillis = System.currentTimeMillis() - start;

    return new WalaArtifacts(scope, cha, options, cg, pointerAnalysis);
  }

  private static AnalysisScope buildScope(AnalysisConfig config) throws IOException {
    String classpath = buildCombinedClasspath(config);
    AnalysisScope scope = AnalysisScopeReader.instance.makeJavaBinaryAnalysisScope(classpath, null);

    PatternsFilter exclusions = buildExclusions(config);
    if (exclusions != null) {
      scope.setExclusions(exclusions);
    }
    return scope;
  }

  private static String buildCombinedClasspath(AnalysisConfig config) {
    List<String> entries = new ArrayList<>();
    if (config.appClasspath() != null && !config.appClasspath().isBlank()) {
      entries.add(config.appClasspath());
    }
    entries.addAll(
        config.inputJars().stream()
            .map(path -> path.toAbsolutePath().toString())
            .collect(Collectors.toList()));
    if (entries.isEmpty()) {
      throw new IllegalArgumentException(
          "No application classpath provided. Use --app-classpath and/or --input-jar.");
    }
    return entries.stream().collect(Collectors.joining(java.io.File.pathSeparator));
  }

  private static PatternsFilter buildExclusions(AnalysisConfig config) throws IOException {
    PatternsFilter.Builder builder = PatternsFilter.builder();
    boolean hasAny = false;

    if (config.exclusionsFile() != null) {
      for (String line : Files.readAllLines(config.exclusionsFile())) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
          continue;
        }
        builder.add(trimmed);
        hasAny = true;
      }
    }

    for (String excludePrefix : config.excludePrefixes()) {
      String normalized = normalizePrefix(excludePrefix);
      builder.add(java.util.regex.Pattern.quote(normalized) + ".*");
      hasAny = true;
    }

    if (!hasAny) {
      return null;
    }
    return builder.build();
  }

  private static String normalizePrefix(String prefix) {
    String normalized = prefix.replace('.', '/');
    if (normalized.startsWith("L")) {
      normalized = normalized.substring(1);
    }
    if (normalized.endsWith(".*")) {
      normalized = normalized.substring(0, normalized.length() - 2);
    }
    return normalized;
  }

  private static List<Entrypoint> buildEntrypoints(
      AnalysisScope scope, IClassHierarchy cha, List<String> mainClasses, AnalysisStats stats) {
    if (mainClasses.isEmpty()) {
      AllApplicationEntrypoints fallback = new AllApplicationEntrypoints(scope, cha);
      stats.addNote("No explicit entrypoints configured; using all application entrypoints.");
      return new ArrayList<>(fallback);
    }

    String[] normalized =
        mainClasses.stream()
            .map(WalaAnalysisBuilder::normalizeMainClass)
            .distinct()
            .toArray(String[]::new);

    List<Entrypoint> result = new ArrayList<>();
    for (Entrypoint entrypoint : Util.makeMainEntrypoints(cha, normalized)) {
      result.add(entrypoint);
    }
    if (result.isEmpty()) {
      throw new IllegalArgumentException(
          "No entrypoints resolved from configured main classes: " + String.join(", ", mainClasses));
    }
    return result;
  }

  private static String normalizeMainClass(String value) {
    String normalized = value.trim();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException("Main class cannot be empty");
    }
    normalized = normalized.replace('.', '/');
    if (!normalized.startsWith("L")) {
      normalized = "L" + normalized;
    }
    return normalized;
  }

  private static SSAPropagationCallGraphBuilder makeBuilder(
      AnalysisConfig config, AnalysisOptions options, IClassHierarchy cha) {
    AnalysisCacheImpl cache = new AnalysisCacheImpl();
    PrecisionMode mode = config.precisionMode();
    if (mode == PrecisionMode.ZERO_CFA) {
      return Util.makeZeroCFABuilder(Language.JAVA, options, cache, cha);
    }
    if (mode == PrecisionMode.ZERO_ONE_CFA) {
      return Util.makeZeroOneCFABuilder(Language.JAVA, options, cache, cha);
    }
    if (mode == PrecisionMode.ZERO_ONE_CONTAINER_CFA) {
      return Util.makeZeroOneContainerCFABuilder(options, cache, cha);
    }
    if (mode == PrecisionMode.N_CFA) {
      return Util.makeNCFABuilder(config.nCfaLevel(), options, cache, cha);
    }
    throw new IllegalArgumentException("Unhandled precision mode: " + mode.name().toLowerCase(Locale.ROOT));
  }

  private static void collectStats(
      AnalysisScope scope,
      IClassHierarchy cha,
      CallGraph cg,
      PointerAnalysis<InstanceKey> pointerAnalysis,
      AnalysisStats stats) {
    stats.classHierarchyClassCount = 0;
    stats.applicationClassCount = 0;
    for (com.ibm.wala.classLoader.IClass klass : cha) {
      stats.classHierarchyClassCount++;
      if (klass.getClassLoader().getReference().equals(scope.getApplicationLoader())) {
        stats.applicationClassCount++;
      }
    }

    stats.callGraphNodeCount = 0;
    stats.applicationCallGraphNodeCount = 0;
    for (CGNode node : cg) {
      stats.callGraphNodeCount++;
      if (node.getMethod()
          .getDeclaringClass()
          .getClassLoader()
          .getReference()
          .equals(scope.getApplicationLoader())) {
        stats.applicationCallGraphNodeCount++;
      }
    }

    stats.pointerKeyCount = 0;
    for (PointerKey ignored : pointerAnalysis.getPointerKeys()) {
      stats.pointerKeyCount++;
    }
    stats.instanceKeyCount = pointerAnalysis.getInstanceKeys().size();
  }
}
