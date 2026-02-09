package org.zlab.nettrace.anchors;

import java.util.Collection;
import java.util.Locale;

public final class GenericAnchorMatcher {
  private static final String[] SEND_METHOD_PREFIXES = {
    "write", "send", "flush", "writeandflush", "enqueue"
  };

  private static final String[] RECV_METHOD_PREFIXES = {
    "read", "recv", "receive", "channelread", "decode"
  };

  private static final String[] SEND_TYPE_HINTS = {
    "java/io/outputstream",
    "java/nio/channels/writablebytechannel",
    "java/nio/channels/socketchannel",
    "io/netty/channel/channel",
    "io/netty/channel/channeloutboundinvoker"
  };

  private static final String[] RECV_TYPE_HINTS = {
    "java/io/inputstream",
    "java/nio/channels/readablebytechannel",
    "java/nio/channels/socketchannel",
    "io/netty/channel/channelinboundhandler",
    "io/netty/handler/codec/bytetomessagedecoder"
  };

  public GenericMatch match(
      String ownerInternalName, String methodName, Collection<String> hierarchyInternalNames) {
    String owner = normalizeType(ownerInternalName);
    String lowerMethod = methodName.toLowerCase(Locale.ROOT);

    boolean sendMethod = hasPrefix(lowerMethod, SEND_METHOD_PREFIXES);
    boolean recvMethod = hasPrefix(lowerMethod, RECV_METHOD_PREFIXES);

    boolean sendType = hasTypeHint(owner, hierarchyInternalNames, SEND_TYPE_HINTS);
    boolean recvType = hasTypeHint(owner, hierarchyInternalNames, RECV_TYPE_HINTS);

    if (sendMethod && sendType && !(recvMethod && recvType)) {
      return new GenericMatch(AnchorRole.SEND, "generic-signature+hierarchy-send", 0.65);
    }
    if (recvMethod && recvType && !(sendMethod && sendType)) {
      return new GenericMatch(AnchorRole.RECV, "generic-signature+hierarchy-recv", 0.65);
    }

    if (sendMethod && owner.contains("outputstream")) {
      return new GenericMatch(AnchorRole.SEND, "generic-signature-send", 0.55);
    }
    if (recvMethod && owner.contains("inputstream")) {
      return new GenericMatch(AnchorRole.RECV, "generic-signature-recv", 0.55);
    }

    return null;
  }

  private static boolean hasTypeHint(
      String owner, Collection<String> hierarchyInternalNames, String[] typeHints) {
    for (String hint : typeHints) {
      if (owner.equals(hint)) {
        return true;
      }
    }

    for (String hierarchyType : hierarchyInternalNames) {
      String normalized = normalizeType(hierarchyType);
      for (String hint : typeHints) {
        if (normalized.equals(hint)) {
          return true;
        }
      }
    }

    for (String hint : typeHints) {
      String shortName = hint.substring(hint.lastIndexOf('/') + 1);
      if (owner.contains(shortName)) {
        return true;
      }
    }

    return false;
  }

  private static String normalizeType(String internalType) {
    String normalized = internalType;
    if (normalized.startsWith("L")) {
      normalized = normalized.substring(1);
    }
    if (normalized.endsWith(";")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized.toLowerCase(Locale.ROOT);
  }

  private static boolean hasPrefix(String value, String[] prefixes) {
    for (String prefix : prefixes) {
      if (value.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }
}
