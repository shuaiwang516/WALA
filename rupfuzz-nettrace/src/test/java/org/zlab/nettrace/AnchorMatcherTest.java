package org.zlab.nettrace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.zlab.nettrace.anchors.AnchorRole;
import org.zlab.nettrace.anchors.AnchorRule;
import org.zlab.nettrace.anchors.AnchorRuleMatcher;
import org.zlab.nettrace.anchors.GenericAnchorMatcher;
import org.zlab.nettrace.anchors.GenericMatch;

class AnchorMatcherTest {
  @Test
  void profileRuleWildcardMatchingWorks() {
    AnchorRule rule =
        new AnchorRule("org.apache.hadoop.ipc.Client*", "call*", "*", "hadoop-client-call");

    boolean matches =
        AnchorRuleMatcher.matches(
            rule,
            "Lorg/apache/hadoop/ipc/Client$Connection",
            "callInternal",
            "(Ljava/lang/Object;)V");

    assertThat(matches).isTrue();
  }

  @Test
  void genericMatcherClassifiesSendAndRecv() {
    GenericAnchorMatcher matcher = new GenericAnchorMatcher();

    GenericMatch send =
        matcher.match(
            "Ljava/io/ByteArrayOutputStream",
            "write",
            List.of("Ljava/io/OutputStream", "Ljava/lang/Object"));

    GenericMatch recv =
        matcher.match(
            "Ljava/io/ByteArrayInputStream",
            "read",
            List.of("Ljava/io/InputStream", "Ljava/lang/Object"));

    assertThat(send).isNotNull();
    assertThat(send.role()).isEqualTo(AnchorRole.SEND);
    assertThat(recv).isNotNull();
    assertThat(recv.role()).isEqualTo(AnchorRole.RECV);
  }
}
