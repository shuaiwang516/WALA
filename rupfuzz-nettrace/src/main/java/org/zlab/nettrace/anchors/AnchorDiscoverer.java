package org.zlab.nettrace.anchors;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IBytecodeMethod;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.shrike.shrikeCT.InvalidClassFileException;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.types.MethodReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.zlab.nettrace.analysis.AnalysisConfig;
import org.zlab.nettrace.analysis.WalaArtifacts;
import org.zlab.nettrace.profiles.NettraceProfile;

public final class AnchorDiscoverer {
  private final GenericAnchorMatcher genericMatcher = new GenericAnchorMatcher();

  public AnchorDiscoveryResult discover(
      WalaArtifacts artifacts, AnalysisConfig config, NettraceProfile profile) {
    List<String> targetPrefixes = mergedTargetPrefixes(config, profile);

    Map<String, RawAnchor> rawSendAnchors = new LinkedHashMap<>();
    Map<String, RawAnchor> rawRecvAnchors = new LinkedHashMap<>();
    Map<String, ResolvedAnchor> resolvedSendAnchors = new LinkedHashMap<>();
    Map<String, ResolvedAnchor> resolvedRecvAnchors = new LinkedHashMap<>();

    discoverFromCallGraph(
        artifacts,
        profile,
        targetPrefixes,
        rawSendAnchors,
        rawRecvAnchors,
        resolvedSendAnchors,
        resolvedRecvAnchors);

    discoverFromApplicationBytecode(
        artifacts.classHierarchy(), profile, targetPrefixes, rawSendAnchors, rawRecvAnchors);

    return new AnchorDiscoveryResult(
        new ArrayList<>(rawSendAnchors.values()),
        new ArrayList<>(rawRecvAnchors.values()),
        new ArrayList<>(resolvedSendAnchors.values()),
        new ArrayList<>(resolvedRecvAnchors.values()));
  }

  private void discoverFromCallGraph(
      WalaArtifacts artifacts,
      NettraceProfile profile,
      List<String> targetPrefixes,
      Map<String, RawAnchor> rawSendAnchors,
      Map<String, RawAnchor> rawRecvAnchors,
      Map<String, ResolvedAnchor> resolvedSendAnchors,
      Map<String, ResolvedAnchor> resolvedRecvAnchors) {
    IClassHierarchy cha = artifacts.classHierarchy();

    for (CGNode node : artifacts.callGraph()) {
      IMethod callerMethod = node.getMethod();
      IR ir = node.getIR();
      if (ir == null) {
        continue;
      }

      String callerClassInternal = callerMethod.getDeclaringClass().getName().toString();
      String callerClass = AnchorRuleMatcher.internalToDotted(callerClassInternal);
      if (!isInTargetPrefix(callerClass, targetPrefixes)) {
        continue;
      }

      SSAInstruction[] instructions = ir.getInstructions();
      for (int index = 0; index < instructions.length; index++) {
        SSAInstruction instruction = instructions[index];
        if (!(instruction instanceof SSAInvokeInstruction)) {
          continue;
        }

        SSAInvokeInstruction invoke = (SSAInvokeInstruction) instruction;
        MethodReference target = invoke.getDeclaredTarget();

        String ownerInternal = target.getDeclaringClass().getName().toString();
        String ownerClass = AnchorRuleMatcher.internalToDotted(ownerInternal);
        String methodName = target.getName().toString();
        String descriptor = target.getDescriptor().toString();
        Set<String> hierarchyNames = collectHierarchyNames(cha, target);

        int lineNumber = toLineNumber(callerMethod, index);
        RawAnchor genericAnchor =
            createGenericAnchor(
                callerMethod,
                index,
                lineNumber,
                ownerClass,
                ownerInternal,
                methodName,
                descriptor,
                hierarchyNames);
        if (genericAnchor != null) {
          addRawAnchor(genericAnchor, rawSendAnchors, rawRecvAnchors);
          addResolvedAnchor(genericAnchor, node, index, resolvedSendAnchors, resolvedRecvAnchors);
        }

        if (profile != null) {
          RawAnchor profileAnchor =
              createProfileAnchor(
                  profile,
                  callerMethod,
                  index,
                  lineNumber,
                  ownerClass,
                  ownerInternal,
                  methodName,
                  descriptor);
          if (profileAnchor != null) {
            addRawAnchor(profileAnchor, rawSendAnchors, rawRecvAnchors);
            addResolvedAnchor(
                profileAnchor, node, index, resolvedSendAnchors, resolvedRecvAnchors);
          }
        }
      }
    }
  }

  private void discoverFromApplicationBytecode(
      IClassHierarchy cha,
      NettraceProfile profile,
      List<String> targetPrefixes,
      Map<String, RawAnchor> rawSendAnchors,
      Map<String, RawAnchor> rawRecvAnchors) {
    for (IClass klass : cha) {
      if (!klass.getClassLoader().getReference().equals(cha.getScope().getApplicationLoader())) {
        continue;
      }

      String callerClass = AnchorRuleMatcher.internalToDotted(klass.getName().toString());
      if (!isInTargetPrefix(callerClass, targetPrefixes)) {
        continue;
      }

      for (IMethod method : klass.getDeclaredMethods()) {
        if (method.isAbstract() || method.isNative() || !(method instanceof IBytecodeMethod<?>)) {
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
          String ownerInternal = target.getDeclaringClass().getName().toString();
          String ownerClass = AnchorRuleMatcher.internalToDotted(ownerInternal);
          String methodName = target.getName().toString();
          String descriptor = target.getDescriptor().toString();
          Set<String> hierarchyNames = collectHierarchyNames(cha, target);

          int lineNumber = method.getLineNumber(callSite.getProgramCounter());
          int instructionIndex = toInstructionIndex(bytecodeMethod, callSite.getProgramCounter());

          RawAnchor genericAnchor =
              createGenericAnchor(
                  method,
                  instructionIndex,
                  lineNumber,
                  ownerClass,
                  ownerInternal,
                  methodName,
                  descriptor,
                  hierarchyNames);
          if (genericAnchor != null) {
            addRawAnchor(genericAnchor, rawSendAnchors, rawRecvAnchors);
          }

          if (profile != null) {
            RawAnchor profileAnchor =
                createProfileAnchor(
                    profile,
                    method,
                    instructionIndex,
                    lineNumber,
                    ownerClass,
                    ownerInternal,
                    methodName,
                    descriptor);
            if (profileAnchor != null) {
              addRawAnchor(profileAnchor, rawSendAnchors, rawRecvAnchors);
            }
          }
        }
      }
    }
  }

  private static List<String> mergedTargetPrefixes(AnalysisConfig config, NettraceProfile profile) {
    List<String> merged = new ArrayList<>(config.targetPrefixes());
    if (profile != null) {
      merged.addAll(profile.targetPrefixes());
    }
    return merged.stream().distinct().collect(Collectors.toList());
  }

  private static boolean isInTargetPrefix(String className, List<String> targetPrefixes) {
    if (targetPrefixes.isEmpty()) {
      return true;
    }
    for (String prefix : targetPrefixes) {
      String normalized = prefix.trim();
      if (!normalized.isEmpty() && className.startsWith(normalized)) {
        return true;
      }
    }
    return false;
  }

  private RawAnchor createGenericAnchor(
      IMethod callerMethod,
      int instructionIndex,
      int lineNumber,
      String ownerClass,
      String ownerInternal,
      String methodName,
      String descriptor,
      Collection<String> hierarchyNames) {
    GenericMatch match = genericMatcher.match(ownerInternal, methodName, hierarchyNames);
    if (match == null) {
      return null;
    }

    return buildRawAnchor(
        callerMethod,
        instructionIndex,
        lineNumber,
        ownerClass,
        methodName,
        descriptor,
        match.role(),
        AnchorSource.GENERIC,
        "",
        match.reason(),
        match.confidence());
  }

  private RawAnchor createProfileAnchor(
      NettraceProfile profile,
      IMethod callerMethod,
      int instructionIndex,
      int lineNumber,
      String ownerClass,
      String ownerInternal,
      String methodName,
      String descriptor) {
    String sendReason = matchProfileRules(profile.sendRules(), ownerInternal, methodName, descriptor);
    if (sendReason != null) {
      return buildRawAnchor(
          callerMethod,
          instructionIndex,
          lineNumber,
          ownerClass,
          methodName,
          descriptor,
          AnchorRole.SEND,
          AnchorSource.PROFILE,
          profile.id(),
          sendReason,
          0.9);
    }

    String recvReason = matchProfileRules(profile.recvRules(), ownerInternal, methodName, descriptor);
    if (recvReason != null) {
      return buildRawAnchor(
          callerMethod,
          instructionIndex,
          lineNumber,
          ownerClass,
          methodName,
          descriptor,
          AnchorRole.RECV,
          AnchorSource.PROFILE,
          profile.id(),
          recvReason,
          0.9);
    }

    return null;
  }

  private static String matchProfileRules(
      List<AnchorRule> rules, String ownerInternal, String methodName, String descriptor) {
    for (AnchorRule rule : rules) {
      if (AnchorRuleMatcher.matches(rule, ownerInternal, methodName, descriptor)) {
        return rule.reason();
      }
    }
    return null;
  }

  private static RawAnchor buildRawAnchor(
      IMethod callerMethod,
      int instructionIndex,
      int lineNumber,
      String ownerClass,
      String methodName,
      String descriptor,
      AnchorRole role,
      AnchorSource source,
      String profile,
      String reason,
      double confidence) {
    String callerClass =
        AnchorRuleMatcher.internalToDotted(callerMethod.getDeclaringClass().getName().toString());
    String callerMethodName = callerMethod.getName().toString();
    String callerDescriptor = callerMethod.getDescriptor().toString();

    String key =
        String.join(
            "|",
            role.name(),
            source.name(),
            profile,
            callerClass,
            callerMethodName,
            callerDescriptor,
            Integer.toString(lineNumber),
            Integer.toString(instructionIndex),
            ownerClass,
            methodName,
            descriptor);
    String anchorId =
        UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8))
            .toString()
            .toLowerCase(Locale.ROOT);

    return new RawAnchor(
        anchorId,
        role,
        source,
        profile,
        reason,
        callerClass,
        callerMethodName,
        callerDescriptor,
        lineNumber,
        instructionIndex,
        ownerClass,
        methodName,
        descriptor,
        confidence);
  }

  private static void addRawAnchor(
      RawAnchor anchor,
      Map<String, RawAnchor> rawSendAnchors,
      Map<String, RawAnchor> rawRecvAnchors) {
    String dedupKey = dedupKey(anchor);
    if (anchor.role() == AnchorRole.SEND) {
      replaceIfHigherPriority(rawSendAnchors, dedupKey, anchor);
    } else {
      replaceIfHigherPriority(rawRecvAnchors, dedupKey, anchor);
    }
  }

  private static void addResolvedAnchor(
      RawAnchor anchor,
      CGNode node,
      int instructionIndex,
      Map<String, ResolvedAnchor> resolvedSendAnchors,
      Map<String, ResolvedAnchor> resolvedRecvAnchors) {
    String dedupKey = dedupKey(anchor);
    ResolvedAnchor resolved = new ResolvedAnchor(anchor, node, instructionIndex);
    if (anchor.role() == AnchorRole.SEND) {
      replaceIfHigherPriority(resolvedSendAnchors, dedupKey, resolved);
    } else {
      replaceIfHigherPriority(resolvedRecvAnchors, dedupKey, resolved);
    }
  }

  private static void replaceIfHigherPriority(
      Map<String, RawAnchor> anchors, String dedupKey, RawAnchor candidate) {
    RawAnchor existing = anchors.get(dedupKey);
    if (existing == null || isHigherPriority(candidate, existing)) {
      anchors.put(dedupKey, candidate);
    }
  }

  private static void replaceIfHigherPriority(
      Map<String, ResolvedAnchor> anchors, String dedupKey, ResolvedAnchor candidate) {
    ResolvedAnchor existing = anchors.get(dedupKey);
    if (existing == null || isHigherPriority(candidate.rawAnchor(), existing.rawAnchor())) {
      anchors.put(dedupKey, candidate);
    }
  }

  private static boolean isHigherPriority(RawAnchor candidate, RawAnchor existing) {
    if (candidate.source() == existing.source()) {
      return candidate.confidence() > existing.confidence();
    }
    return candidate.source() == AnchorSource.PROFILE && existing.source() == AnchorSource.GENERIC;
  }

  private static String dedupKey(RawAnchor anchor) {
    return String.join(
        "|",
        anchor.role().name(),
        anchor.callerClass(),
        anchor.callerMethod(),
        Integer.toString(anchor.lineNumber()),
        Integer.toString(anchor.instructionIndex()),
        anchor.invokedClass(),
        anchor.invokedMethod(),
        anchor.invokedDescriptor());
  }

  private static int toInstructionIndex(IBytecodeMethod<?> method, int bytecodeIndex) {
    try {
      return method.getInstructionIndex(bytecodeIndex);
    } catch (InvalidClassFileException ignored) {
      return bytecodeIndex;
    }
  }

  private static int toLineNumber(IMethod method, int instructionIndex) {
    if (!(method instanceof IBytecodeMethod<?>)) {
      return -1;
    }
    IBytecodeMethod<?> bytecodeMethod = (IBytecodeMethod<?>) method;
    try {
      int bcIndex = bytecodeMethod.getBytecodeIndex(instructionIndex);
      return method.getLineNumber(bcIndex);
    } catch (InvalidClassFileException ignored) {
      return -1;
    }
  }

  private static Set<String> collectHierarchyNames(IClassHierarchy cha, MethodReference target) {
    Set<String> names = new LinkedHashSet<>();
    IMethod resolved = cha.resolveMethod(target);
    if (resolved == null) {
      IClass targetClass = cha.lookupClass(target.getDeclaringClass());
      if (targetClass == null) {
        return names;
      }
      collectHierarchyNames(targetClass, names);
      return names;
    }

    collectHierarchyNames(resolved.getDeclaringClass(), names);
    return names;
  }

  private static void collectHierarchyNames(IClass declaring, Set<String> names) {
    for (IClass current = declaring; current != null; current = current.getSuperclass()) {
      names.add(current.getName().toString());
    }
    for (IClass iface : declaring.getAllImplementedInterfaces()) {
      names.add(iface.getName().toString());
    }
  }
}
