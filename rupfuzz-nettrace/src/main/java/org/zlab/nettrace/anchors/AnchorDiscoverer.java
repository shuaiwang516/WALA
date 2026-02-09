package org.zlab.nettrace.anchors;

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
    Map<String, ResolvedAnchor> sendAnchors = new LinkedHashMap<>();
    Map<String, ResolvedAnchor> recvAnchors = new LinkedHashMap<>();

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
                node,
                index,
                lineNumber,
                ownerClass,
                ownerInternal,
                methodName,
                descriptor,
                hierarchyNames);
        if (genericAnchor != null) {
          addAnchor(genericAnchor, node, index, sendAnchors, recvAnchors);
        }

        if (profile != null) {
          RawAnchor profileAnchor =
              createProfileAnchor(
                  profile,
                  callerMethod,
                  node,
                  index,
                  lineNumber,
                  ownerClass,
                  ownerInternal,
                  methodName,
                  descriptor);
          if (profileAnchor != null) {
            addAnchor(profileAnchor, node, index, sendAnchors, recvAnchors);
          }
        }
      }
    }

    return new AnchorDiscoveryResult(
        sendAnchors.values().stream()
            .map(ResolvedAnchor::rawAnchor)
            .collect(Collectors.toList()),
        recvAnchors.values().stream()
            .map(ResolvedAnchor::rawAnchor)
            .collect(Collectors.toList()),
        new ArrayList<>(sendAnchors.values()),
        new ArrayList<>(recvAnchors.values()));
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
      CGNode node,
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
        node,
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
      CGNode node,
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
          node,
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
          node,
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
      CGNode node,
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
    String callerClass = AnchorRuleMatcher.internalToDotted(callerMethod.getDeclaringClass().getName().toString());
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
            descriptor,
            Integer.toString(node.getGraphNodeId()));
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

  private static void addAnchor(
      RawAnchor anchor,
      CGNode node,
      int instructionIndex,
      Map<String, ResolvedAnchor> sendAnchors,
      Map<String, ResolvedAnchor> recvAnchors) {
    String dedupKey = dedupKey(anchor);
    ResolvedAnchor resolved = new ResolvedAnchor(anchor, node, instructionIndex);
    if (anchor.role() == AnchorRole.SEND) {
      sendAnchors.putIfAbsent(dedupKey, resolved);
    } else {
      recvAnchors.putIfAbsent(dedupKey, resolved);
    }
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
      return names;
    }

    IClass declaring = resolved.getDeclaringClass();
    for (IClass current = declaring; current != null; current = current.getSuperclass()) {
      names.add(current.getName().toString());
    }
    for (IClass iface : declaring.getAllImplementedInterfaces()) {
      names.add(iface.getName().toString());
    }
    return names;
  }
}
