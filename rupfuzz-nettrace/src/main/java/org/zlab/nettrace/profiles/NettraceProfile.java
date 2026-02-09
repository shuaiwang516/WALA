package org.zlab.nettrace.profiles;

import java.util.List;
import org.zlab.nettrace.anchors.AnchorRule;

public final class NettraceProfile {
  private final String id;
  private final String description;
  private final List<String> targetPrefixes;
  private final List<String> excludePrefixes;
  private final List<AnchorRule> sendRules;
  private final List<AnchorRule> recvRules;

  public NettraceProfile(
      String id,
      String description,
      List<String> targetPrefixes,
      List<String> excludePrefixes,
      List<AnchorRule> sendRules,
      List<AnchorRule> recvRules) {
    this.id = id;
    this.description = description;
    this.targetPrefixes = List.copyOf(targetPrefixes);
    this.excludePrefixes = List.copyOf(excludePrefixes);
    this.sendRules = List.copyOf(sendRules);
    this.recvRules = List.copyOf(recvRules);
  }

  public String id() {
    return id;
  }

  public String description() {
    return description;
  }

  public List<String> targetPrefixes() {
    return targetPrefixes;
  }

  public List<String> excludePrefixes() {
    return excludePrefixes;
  }

  public List<AnchorRule> sendRules() {
    return sendRules;
  }

  public List<AnchorRule> recvRules() {
    return recvRules;
  }
}
