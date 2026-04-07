package org.zlab.nettrace.analysis;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IBytecodeMethod;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.Language;
import com.ibm.wala.core.util.config.AnalysisScopeReader;
import com.ibm.wala.core.util.strings.Atom;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.CallGraphBuilder;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.impl.AllApplicationEntrypoints;
import com.ibm.wala.ipa.callgraph.impl.DefaultEntrypoint;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.shrike.shrikeCT.InvalidClassFileException;
import com.ibm.wala.types.Descriptor;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeName;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.config.PatternsFilter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.zlab.nettrace.anchors.AnchorRule;
import org.zlab.nettrace.anchors.AnchorRuleMatcher;
import org.zlab.nettrace.anchors.GenericAnchorMatcher;
import org.zlab.nettrace.profiles.NettraceProfile;

public final class WalaAnalysisBuilder {
  private static final int MAX_SEEDED_ENTRYPOINTS =
      Integer.getInteger("nettrace.entrypoint.maxSeededMethods", 2000);
  private static final GenericAnchorMatcher GENERIC_MATCHER = new GenericAnchorMatcher();

  public WalaArtifacts build(AnalysisConfig config, NettraceProfile profile, AnalysisStats stats)
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

    List<Entrypoint> entrypoints =
        buildEntrypoints(scope, cha, mainClasses, config, profile, stats);
    stats.entrypointCount = entrypoints.size();
    AnalysisOptions options = new AnalysisOptions(scope, entrypoints);

    long cgStart = System.currentTimeMillis();
    CallGraphBuilder<InstanceKey> builder = makeBuilder(config, options, cha);
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
      AnalysisScope scope,
      IClassHierarchy cha,
      List<String> mainClasses,
      AnalysisConfig config,
      NettraceProfile profile,
      AnalysisStats stats) {
    List<String> resolvableMainClasses = resolveMainClasses(scope, cha, mainClasses, stats);
    EntrypointMode mode = config.entrypointMode();

    if (mode == EntrypointMode.ALL_APPLICATION) {
      return buildAllApplicationEntrypoints(scope, cha, stats, "entrypoint-mode=all-application");
    }

    if (mode == EntrypointMode.MAIN) {
      if (resolvableMainClasses.isEmpty()) {
        throw new IllegalArgumentException(
            "No entrypoints resolved from configured main classes: "
                + String.join(", ", mainClasses));
      }
      return buildMainEntrypoints(cha, resolvableMainClasses);
    }

    // PROFILE_SEEDED mode.
    Map<String, Entrypoint> entrypoints = new LinkedHashMap<>();
    int mainCount = addMainEntrypoints(entrypoints, cha, resolvableMainClasses);
    int seededCount = addProfileSeedEntrypoints(entrypoints, scope, cha, config, profile, stats);

    if (entrypoints.isEmpty()) {
      String reason =
          profile == null
              ? "entrypoint-mode=profile-seeded and no profile provided"
              : "entrypoint-mode=profile-seeded but no seed methods resolved";
      return buildAllApplicationEntrypoints(scope, cha, stats, reason);
    }

    stats.addNote(
        "Entrypoint selection: mode=profile-seeded main=" + mainCount + " seeded=" + seededCount);
    return new ArrayList<>(entrypoints.values());
  }

  private static List<String> resolveMainClasses(
      AnalysisScope scope, IClassHierarchy cha, List<String> mainClasses, AnalysisStats stats) {
    List<String> normalized =
        mainClasses.stream()
            .map(WalaAnalysisBuilder::normalizeMainClass)
            .distinct()
            .collect(Collectors.toList());

    List<String> resolvable = new ArrayList<>();
    for (String mainClass : normalized) {
      if (hasMainMethod(scope, cha, mainClass)) {
        resolvable.add(mainClass);
      } else {
        stats.addNote("Skipping unresolved main class: " + mainClass);
      }
    }
    return resolvable;
  }

  private static List<Entrypoint> buildMainEntrypoints(
      IClassHierarchy cha, List<String> mainClasses) {
    List<Entrypoint> result = new ArrayList<>();
    for (Entrypoint entrypoint :
        Util.makeMainEntrypoints(cha, mainClasses.toArray(new String[0]))) {
      result.add(entrypoint);
    }
    if (result.isEmpty()) {
      throw new IllegalArgumentException(
          "No entrypoints resolved from configured main classes: "
              + String.join(", ", mainClasses));
    }
    return result;
  }

  private static int addMainEntrypoints(
      Map<String, Entrypoint> entrypoints, IClassHierarchy cha, List<String> mainClasses) {
    if (mainClasses.isEmpty()) {
      return 0;
    }
    int before = entrypoints.size();
    for (Entrypoint entrypoint : buildMainEntrypoints(cha, mainClasses)) {
      String key = entrypoint.getMethod().getReference().toString();
      entrypoints.putIfAbsent(key, entrypoint);
    }
    return entrypoints.size() - before;
  }

  private static List<Entrypoint> buildAllApplicationEntrypoints(
      AnalysisScope scope, IClassHierarchy cha, AnalysisStats stats, String reason) {
    AllApplicationEntrypoints fallback = new AllApplicationEntrypoints(scope, cha);
    stats.addNote("Using all application entrypoints (" + reason + ").");
    return new ArrayList<>(fallback);
  }

  private static int addProfileSeedEntrypoints(
      Map<String, Entrypoint> entrypoints,
      AnalysisScope scope,
      IClassHierarchy cha,
      AnalysisConfig config,
      NettraceProfile profile,
      AnalysisStats stats) {
    if (profile == null) {
      stats.addNote("Profile-seeded mode active but no profile was provided.");
      return 0;
    }

    List<AnchorRule> rules = new ArrayList<>();
    rules.addAll(profile.sendRules());
    rules.addAll(profile.recvRules());
    if (rules.isEmpty()) {
      stats.addNote("Profile-seeded mode active but profile has no send/recv rules.");
      return 0;
    }

    List<String> targetPrefixes = mergedTargetPrefixes(config, profile);
    int before = entrypoints.size();
    int seeded = 0;
    int seededByMethodMatch = 0;
    int seededByCallerMatch = 0;

    for (IClass klass : cha) {
      if (!klass.getClassLoader().getReference().equals(scope.getApplicationLoader())) {
        continue;
      }
      String ownerInternal = klass.getName().toString();
      String ownerClass = AnchorRuleMatcher.internalToDotted(ownerInternal);
      if (!isInTargetPrefix(ownerClass, targetPrefixes)) {
        continue;
      }

      Set<String> hierarchyNames = collectHierarchyNames(klass);
      for (IMethod method : klass.getDeclaredMethods()) {
        if (!isSeedCandidateMethod(method)) {
          continue;
        }

        String methodName = method.getName().toString();
        String descriptor = method.getDescriptor().toString();
        boolean profileMatch = matchesAnyRule(rules, ownerInternal, methodName, descriptor);
        boolean genericMatch =
            GENERIC_MATCHER.match(ownerInternal, methodName, hierarchyNames) != null;
        if (profileMatch || genericMatch) {
          if (tryAddSeedEntrypoint(entrypoints, method, cha)) {
            seeded++;
            seededByMethodMatch++;
            if (seeded >= MAX_SEEDED_ENTRYPOINTS) {
              stats.addNote(
                  "Seeded entrypoints reached cap "
                      + MAX_SEEDED_ENTRYPOINTS
                      + " (property nettrace.entrypoint.maxSeededMethods).");
              stats.addNote(
                  "Seeded by method signature="
                      + seededByMethodMatch
                      + ", by caller callsite="
                      + seededByCallerMatch);
              return entrypoints.size() - before;
            }
          }
        }

        if (!(method instanceof IBytecodeMethod<?>)) {
          continue;
        }

        IBytecodeMethod<?> bytecodeMethod = (IBytecodeMethod<?>) method;
        Collection<CallSiteReference> callSites;
        try {
          callSites = bytecodeMethod.getCallSites();
        } catch (InvalidClassFileException ignored) {
          continue;
        }

        for (CallSiteReference callSite : callSites) {
          MethodReference target = callSite.getDeclaredTarget();
          String targetOwnerInternal = target.getDeclaringClass().getName().toString();
          String targetMethodName = target.getName().toString();
          String targetDescriptor = target.getDescriptor().toString();

          boolean targetProfileMatch =
              matchesAnyRule(rules, targetOwnerInternal, targetMethodName, targetDescriptor);
          boolean targetGenericMatch =
              GENERIC_MATCHER.match(
                      targetOwnerInternal, targetMethodName, collectHierarchyNames(cha, target))
                  != null;
          if (!targetProfileMatch && !targetGenericMatch) {
            continue;
          }

          if (tryAddSeedEntrypoint(entrypoints, method, cha)) {
            seeded++;
            seededByCallerMatch++;
            if (seeded >= MAX_SEEDED_ENTRYPOINTS) {
              stats.addNote(
                  "Seeded entrypoints reached cap "
                      + MAX_SEEDED_ENTRYPOINTS
                      + " (property nettrace.entrypoint.maxSeededMethods).");
              stats.addNote(
                  "Seeded by method signature="
                      + seededByMethodMatch
                      + ", by caller callsite="
                      + seededByCallerMatch);
              return entrypoints.size() - before;
            }
          }
          break;
        }
      }
    }

    stats.addNote(
        "Seeded by method signature="
            + seededByMethodMatch
            + ", by caller callsite="
            + seededByCallerMatch);
    return entrypoints.size() - before;
  }

  private static boolean matchesAnyRule(
      List<AnchorRule> rules, String ownerInternal, String methodName, String descriptor) {
    for (AnchorRule rule : rules) {
      if (AnchorRuleMatcher.matches(rule, ownerInternal, methodName, descriptor)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isSeedCandidateMethod(IMethod method) {
    if (method.isAbstract() || method.isNative()) {
      return false;
    }
    String name = method.getName().toString();
    return !name.startsWith("<");
  }

  private static List<String> mergedTargetPrefixes(AnalysisConfig config, NettraceProfile profile) {
    List<String> merged = new ArrayList<>(config.targetPrefixes());
    if (profile != null) {
      merged.addAll(profile.targetPrefixes());
    }
    return merged.stream()
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .distinct()
        .collect(Collectors.toList());
  }

  private static boolean isInTargetPrefix(String className, List<String> targetPrefixes) {
    if (targetPrefixes.isEmpty()) {
      return true;
    }
    for (String prefix : targetPrefixes) {
      if (className.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  private static Set<String> collectHierarchyNames(IClass klass) {
    java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
    for (IClass current = klass; current != null; current = current.getSuperclass()) {
      names.add(current.getName().toString());
    }
    for (IClass iface : klass.getAllImplementedInterfaces()) {
      names.add(iface.getName().toString());
    }
    return names;
  }

  private static Set<String> collectHierarchyNames(IClassHierarchy cha, MethodReference target) {
    IMethod resolved = cha.resolveMethod(target);
    if (resolved != null) {
      return collectHierarchyNames(resolved.getDeclaringClass());
    }

    IClass targetClass = cha.lookupClass(target.getDeclaringClass());
    if (targetClass != null) {
      return collectHierarchyNames(targetClass);
    }

    return new LinkedHashSet<>();
  }

  private static boolean tryAddSeedEntrypoint(
      Map<String, Entrypoint> entrypoints, IMethod method, IClassHierarchy cha) {
    String key = method.getReference().toString();
    if (entrypoints.containsKey(key)) {
      return false;
    }
    entrypoints.put(key, new DefaultEntrypoint(method, cha));
    return true;
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

  private static boolean hasMainMethod(AnalysisScope scope, IClassHierarchy cha, String className) {
    TypeReference typeReference =
        TypeReference.findOrCreate(
            scope.getApplicationLoader(), TypeName.string2TypeName(className));
    com.ibm.wala.classLoader.IClass klass = cha.lookupClass(typeReference);
    if (klass == null) {
      return false;
    }

    MethodReference mainRef =
        MethodReference.findOrCreate(
            typeReference,
            Atom.findOrCreateAsciiAtom("main"),
            Descriptor.findOrCreateUTF8("([Ljava/lang/String;)V"));
    return klass.getMethod(mainRef.getSelector()) != null;
  }

  private static CallGraphBuilder<InstanceKey> makeBuilder(
      AnalysisConfig config, AnalysisOptions options, IClassHierarchy cha) {
    AnalysisCacheImpl cache = new AnalysisCacheImpl();
    PrecisionMode mode = config.precisionMode();
    if (mode == PrecisionMode.RTA) {
      return Util.makeRTABuilder(options, cache, cha);
    }
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
    throw new IllegalArgumentException(
        "Unhandled precision mode: " + mode.name().toLowerCase(Locale.ROOT));
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
