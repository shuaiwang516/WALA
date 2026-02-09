package org.zlab.nettrace.analysis;

import com.ibm.wala.classLoader.IBytecodeMethod;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.slicer.NormalStatement;
import com.ibm.wala.ipa.slicer.Slicer;
import com.ibm.wala.ipa.slicer.Statement;
import com.ibm.wala.shrike.shrikeCT.InvalidClassFileException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.zlab.nettrace.anchors.ResolvedAnchor;

public final class Phase2DraftAnalyzer {
  public Phase2DraftResult analyze(WalaArtifacts artifacts, List<ResolvedAnchor> sends, List<ResolvedAnchor> recvs) {
    List<SendPoint> sendPoints = new ArrayList<>();
    List<RecvBeginPoint> recvBeginPoints = new ArrayList<>();
    List<RecvEndPoint> recvEndPoints = new ArrayList<>();

    for (ResolvedAnchor resolved : sends) {
      NormalStatement seed = new NormalStatement(resolved.node(), resolved.instructionIndex());
      int appSliceStatements = countApplicationSliceStatements(seed, artifacts, true);
      double confidence =
          Math.min(0.95, resolved.rawAnchor().confidence() + Math.min(20, appSliceStatements) / 200.0);

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
    }

    for (ResolvedAnchor resolved : recvs) {
      NormalStatement seed = new NormalStatement(resolved.node(), resolved.instructionIndex());
      int appSliceStatements = countApplicationSliceStatements(seed, artifacts, false);
      double beginConfidence =
          Math.min(0.95, resolved.rawAnchor().confidence() + Math.min(20, appSliceStatements) / 200.0);

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

      int endLine = findForwardEndLine(seed, artifacts, resolved.rawAnchor().lineNumber());
      recvEndPoints.add(
          new RecvEndPoint(
              beginPointId,
              "recv-end-" + resolved.rawAnchor().anchorId(),
              resolved.rawAnchor().callerClass(),
              resolved.rawAnchor().callerMethod(),
              endLine,
              "method-exit-fallback"));
    }

    return new Phase2DraftResult(sendPoints, recvBeginPoints, recvEndPoints);
  }

  private static int countApplicationSliceStatements(
      Statement seed, WalaArtifacts artifacts, boolean backward) {
    try {
      Collection<Statement> slice =
          backward
              ? Slicer.computeBackwardSlice(
                  seed,
                  artifacts.callGraph(),
                  artifacts.pointerAnalysis(),
                  Slicer.DataDependenceOptions.FULL,
                  Slicer.ControlDependenceOptions.NONE)
              : Slicer.computeForwardSlice(
                  seed,
                  artifacts.callGraph(),
                  artifacts.pointerAnalysis(),
                  Slicer.DataDependenceOptions.FULL,
                  Slicer.ControlDependenceOptions.NONE);

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

  private static int findForwardEndLine(Statement seed, WalaArtifacts artifacts, int fallbackLine) {
    int bestLine = fallbackLine;

    try {
      Collection<Statement> slice =
          Slicer.computeForwardSlice(
              seed,
              artifacts.callGraph(),
              artifacts.pointerAnalysis(),
              Slicer.DataDependenceOptions.FULL,
              Slicer.ControlDependenceOptions.NONE);

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
