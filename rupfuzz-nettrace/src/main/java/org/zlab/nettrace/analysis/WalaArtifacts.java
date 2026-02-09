package org.zlab.nettrace.analysis;

import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
import com.ibm.wala.ipa.cha.IClassHierarchy;

public final class WalaArtifacts {
  private final AnalysisScope scope;
  private final IClassHierarchy classHierarchy;
  private final AnalysisOptions options;
  private final CallGraph callGraph;
  private final PointerAnalysis<InstanceKey> pointerAnalysis;

  public WalaArtifacts(
      AnalysisScope scope,
      IClassHierarchy classHierarchy,
      AnalysisOptions options,
      CallGraph callGraph,
      PointerAnalysis<InstanceKey> pointerAnalysis) {
    this.scope = scope;
    this.classHierarchy = classHierarchy;
    this.options = options;
    this.callGraph = callGraph;
    this.pointerAnalysis = pointerAnalysis;
  }

  public AnalysisScope scope() {
    return scope;
  }

  public IClassHierarchy classHierarchy() {
    return classHierarchy;
  }

  public AnalysisOptions options() {
    return options;
  }

  public CallGraph callGraph() {
    return callGraph;
  }

  public PointerAnalysis<InstanceKey> pointerAnalysis() {
    return pointerAnalysis;
  }
}
