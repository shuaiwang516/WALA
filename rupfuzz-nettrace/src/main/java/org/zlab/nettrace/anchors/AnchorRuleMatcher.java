package org.zlab.nettrace.anchors;

import java.util.Locale;
import java.util.regex.Pattern;

public final class AnchorRuleMatcher {
  private AnchorRuleMatcher() {}

  public static boolean matches(
      AnchorRule rule, String ownerInternalName, String methodName, String descriptor) {
    String ownerDotted = internalToDotted(ownerInternalName);

    return wildcardMatch(ownerDotted, rule.ownerPattern())
        && wildcardMatch(methodName, rule.methodPattern())
        && wildcardMatch(descriptor, rule.descriptorPattern());
  }

  public static String internalToDotted(String ownerInternalName) {
    String normalized = ownerInternalName;
    if (normalized.startsWith("L")) {
      normalized = normalized.substring(1);
    }
    if (normalized.endsWith(";")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized.replace('/', '.');
  }

  private static boolean wildcardMatch(String value, String wildcardPattern) {
    if (wildcardPattern == null || wildcardPattern.isBlank() || "*".equals(wildcardPattern)) {
      return true;
    }

    String regex = wildcardToRegex(wildcardPattern.toLowerCase(Locale.ROOT));

    return value.toLowerCase(Locale.ROOT).matches(regex);
  }

  private static String wildcardToRegex(String wildcardPattern) {
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < wildcardPattern.length(); i++) {
      char ch = wildcardPattern.charAt(i);
      if (ch == '*') {
        builder.append(".*");
      } else if (ch == '?') {
        builder.append('.');
      } else {
        builder.append(Pattern.quote(String.valueOf(ch)));
      }
    }
    return builder.toString();
  }
}
