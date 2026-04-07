package org.zlab.nettrace.analysis;

import com.ibm.wala.classLoader.IBytecodeMethod;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.modref.ModRef;
import com.ibm.wala.ipa.slicer.NormalStatement;
import com.ibm.wala.ipa.slicer.SDG;
import com.ibm.wala.ipa.slicer.Slicer;
import com.ibm.wala.ipa.slicer.Statement;
import com.ibm.wala.shrike.shrikeCT.InvalidClassFileException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.zlab.nettrace.anchors.AnchorSource;
import org.zlab.nettrace.anchors.RawAnchor;
import org.zlab.nettrace.anchors.ResolvedAnchor;

public final class Phase2DraftAnalyzer {
  private static final int MAX_SEND_SLICE_SEEDS =
      Integer.getInteger("nettrace.phase2.maxSendSliceSeeds", 1);
  private static final int MAX_RECV_SLICE_SEEDS =
      Integer.getInteger("nettrace.phase2.maxRecvSliceSeeds", 1);
  private static final boolean INCLUDE_GENERIC_FALLBACK =
      Boolean.getBoolean("nettrace.phase2.includeGenericFallback");
  private static final Slicer.DataDependenceOptions DATA_DEPENDENCE =
      Slicer.DataDependenceOptions.NO_BASE_NO_HEAP_NO_EXCEPTIONS;
  private static final Slicer.ControlDependenceOptions CONTROL_DEPENDENCE =
      Slicer.ControlDependenceOptions.NONE;

  public Phase2DraftResult analyze(
      WalaArtifacts artifacts,
      List<RawAnchor> rawSendAnchors,
      List<RawAnchor> rawRecvAnchors,
      List<ResolvedAnchor> sends,
      List<ResolvedAnchor> recvs) {
    List<SendPoint> sendPoints = new ArrayList<>();
    List<RecvBeginPoint> recvBeginPoints = new ArrayList<>();
    List<RecvEndPoint> recvEndPoints = new ArrayList<>();
    Set<String> resolvedSendIds = new HashSet<>();
    Set<String> resolvedRecvIds = new HashSet<>();
    SDG<?> sdg = buildSdg(artifacts);
    int slicedSends = 0;
    int slicedRecvs = 0;
    int resolvedSend = 0;
    int resolvedRecv = 0;
    int fallbackSend = 0;
    int fallbackRecv = 0;

    for (ResolvedAnchor resolved : sends) {
      resolvedSendIds.add(resolved.rawAnchor().anchorId());
      NormalStatement seed = new NormalStatement(resolved.node(), resolved.instructionIndex());
      int appSliceStatements = 0;
      if (sdg != null && slicedSends < MAX_SEND_SLICE_SEEDS) {
        appSliceStatements = countApplicationSliceStatements(seed, artifacts, sdg, true);
        slicedSends++;
      }
      double confidence =
          Math.min(
              0.95, resolved.rawAnchor().confidence() + Math.min(20, appSliceStatements) / 200.0);

      sendPoints.add(
          new SendPoint(
              "send-" + resolved.rawAnchor().anchorId(),
              resolved.rawAnchor().callerClass(),
              resolved.rawAnchor().callerMethod(),
              resolved.rawAnchor().lineNumber(),
              "invoke#" + resolved.rawAnchor().instructionIndex(),
              "v?",
              confidence,
              resolved.rawAnchor().profile()));
      resolvedSend++;
    }

    for (ResolvedAnchor resolved : recvs) {
      resolvedRecvIds.add(resolved.rawAnchor().anchorId());
      NormalStatement seed = new NormalStatement(resolved.node(), resolved.instructionIndex());
      boolean useSlice = sdg != null && slicedRecvs < MAX_RECV_SLICE_SEEDS;
      int appSliceStatements =
          useSlice ? countApplicationSliceStatements(seed, artifacts, sdg, false) : 0;
      double beginConfidence =
          Math.min(
              0.95, resolved.rawAnchor().confidence() + Math.min(20, appSliceStatements) / 200.0);

      String beginPointId = "recv-begin-" + resolved.rawAnchor().anchorId();
      recvBeginPoints.add(
          new RecvBeginPoint(
              beginPointId,
              resolved.rawAnchor().callerClass(),
              resolved.rawAnchor().callerMethod(),
              resolved.rawAnchor().lineNumber(),
              "invoke#" + resolved.rawAnchor().instructionIndex(),
              "v?",
              beginConfidence,
              resolved.rawAnchor().profile()));

      int endLine =
          useSlice
              ? findForwardEndLine(seed, sdg, resolved.rawAnchor().lineNumber())
              : resolved.rawAnchor().lineNumber();
      recvEndPoints.add(
          new RecvEndPoint(
              beginPointId,
              "recv-end-" + resolved.rawAnchor().anchorId(),
              resolved.rawAnchor().callerClass(),
              resolved.rawAnchor().callerMethod(),
              endLine,
              "method-exit-fallback"));
      if (useSlice) {
        slicedRecvs++;
      }
      resolvedRecv++;
    }

    // Fallback draft points for anchors not resolved into call-graph nodes.
    for (RawAnchor raw : rawSendAnchors) {
      if (resolvedSendIds.contains(raw.anchorId())) {
        continue;
      }
      if (!shouldEmitFallback(raw)) {
        continue;
      }
      sendPoints.add(
          new SendPoint(
              "send-" + raw.anchorId(),
              raw.callerClass(),
              raw.callerMethod(),
              raw.lineNumber(),
              "invoke#" + raw.instructionIndex(),
              "v?",
              Math.max(0.2, raw.confidence() * 0.7),
              raw.profile()));
      fallbackSend++;
    }

    for (RawAnchor raw : rawRecvAnchors) {
      if (resolvedRecvIds.contains(raw.anchorId())) {
        continue;
      }
      if (!shouldEmitFallback(raw)) {
        continue;
      }
      String beginPointId = "recv-begin-" + raw.anchorId();
      recvBeginPoints.add(
          new RecvBeginPoint(
              beginPointId,
              raw.callerClass(),
              raw.callerMethod(),
              raw.lineNumber(),
              "invoke#" + raw.instructionIndex(),
              "v?",
              Math.max(0.2, raw.confidence() * 0.7),
              raw.profile()));
      recvEndPoints.add(
          new RecvEndPoint(
              beginPointId,
              "recv-end-" + raw.anchorId(),
              raw.callerClass(),
              raw.callerMethod(),
              raw.lineNumber(),
              "method-exit-fallback-unresolved"));
      fallbackRecv++;
    }

    return new Phase2DraftResult(
        sendPoints,
        recvBeginPoints,
        recvEndPoints,
        new Phase2Diagnostics(resolvedSend, resolvedRecv, fallbackSend, fallbackRecv));
  }

  private static boolean shouldEmitFallback(RawAnchor raw) {
    return raw.source() == AnchorSource.PROFILE || INCLUDE_GENERIC_FALLBACK;
  }

  private static SDG<?> buildSdg(WalaArtifacts artifacts) {
    try {
      return new SDG<>(
          artifacts.callGraph(),
          artifacts.pointerAnalysis(),
          ModRef.<InstanceKey>make(),
          DATA_DEPENDENCE,
          CONTROL_DEPENDENCE);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static int countApplicationSliceStatements(
      Statement seed, WalaArtifacts artifacts, SDG<?> sdg, boolean backward) {
    try {
      Collection<Statement> slice =
          backward ? Slicer.computeBackwardSlice(sdg, seed) : Slicer.computeForwardSlice(sdg, seed);

      int count = 0;
      for (Statement statement : slice) {
        if (statement instanceof NormalStatement) {
          CGNode node = statement.getNode();
          if (node.getMethod()
              .getDeclaringClass()
              .getClassLoader()
              .getReference()
              .equals(artifacts.scope().getApplicationLoader())) {
            count++;
          }
        }
      }
      return count;
    } catch (Exception ignored) {
      return 0;
    }
  }

  private static int findForwardEndLine(Statement seed, SDG<?> sdg, int fallbackLine) {
    int bestLine = fallbackLine;

    try {
      Collection<Statement> slice = Slicer.computeForwardSlice(sdg, seed);

      for (Statement statement : slice) {
        if (!(statement instanceof NormalStatement)) {
          continue;
        }
        NormalStatement normal = (NormalStatement) statement;
        if (!statement.getNode().equals(seed.getNode())) {
          continue;
        }

        int line = lineForInstruction(statement.getNode(), normal.getInstructionIndex());
        if (line > bestLine) {
          bestLine = line;
        }
      }
    } catch (Exception ignored) {
      return fallbackLine;
    }

    return bestLine;
  }

  private static int lineForInstruction(CGNode node, int instructionIndex) {
    IMethod method = node.getMethod();
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
}
