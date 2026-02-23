package org.zlab.nettrace.profiles;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.zlab.nettrace.anchors.AnchorRule;

public final class ProfileLoader {
  private ProfileLoader() {}

  public static NettraceProfile load(String profileArg, Path profilesDir) throws IOException {
    if (profileArg == null || profileArg.isBlank()) {
      return null;
    }

    Path profilePath = resolveProfilePath(profileArg, profilesDir);
    return load(profilePath);
  }

  public static NettraceProfile load(Path path) throws IOException {
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    Yaml yaml = new Yaml(new SafeConstructor(options));
    try (Reader reader = Files.newBufferedReader(path)) {
      Object loaded = yaml.load(reader);
      if (!(loaded instanceof Map<?, ?>)) {
        throw new IllegalArgumentException("Profile YAML must be a mapping: " + path);
      }
      return parseProfile(path, castMap(loaded));
    }
  }

  private static Path resolveProfilePath(String profileArg, Path profilesDir) {
    Path direct = Path.of(profileArg);
    if (Files.exists(direct)) {
      return direct;
    }

    Path directAbsolute = direct.toAbsolutePath().normalize();
    if (Files.exists(directAbsolute)) {
      return directAbsolute;
    }

    String fileName = profileArg;
    if (!fileName.endsWith(".yaml") && !fileName.endsWith(".yml")) {
      fileName = fileName + ".yaml";
    }

    Path byDirectory = profilesDir.resolve(fileName);
    if (Files.exists(byDirectory)) {
      return byDirectory;
    }

    Path byDirectoryAbsolute = byDirectory.toAbsolutePath().normalize();
    if (Files.exists(byDirectoryAbsolute)) {
      return byDirectoryAbsolute;
    }

    Path moduleDefault = Path.of("rupfuzz-nettrace", "profiles", fileName);
    if (Files.exists(moduleDefault)) {
      return moduleDefault;
    }

    Path parentModuleDefault = Path.of("..", "rupfuzz-nettrace", "profiles", fileName).normalize();
    if (Files.exists(parentModuleDefault)) {
      return parentModuleDefault;
    }

    throw new IllegalArgumentException(
        "Profile not found: "
            + profileArg
            + " (checked "
            + direct
            + ", "
            + directAbsolute
            + ", "
            + byDirectory
            + ", "
            + byDirectoryAbsolute
            + ", "
            + moduleDefault
            + ", "
            + parentModuleDefault
            + ")");
  }

  private static NettraceProfile parseProfile(Path path, Map<String, Object> map) {
    String id = asString(map.getOrDefault("id", stripExtension(path.getFileName().toString())));
    String description = asString(map.getOrDefault("description", ""));

    List<String> targetPrefixes = asStringList(map.get("targetPrefixes"));
    List<String> excludePrefixes = asStringList(map.get("excludePrefixes"));
    List<AnchorRule> sendRules = asRules(map.get("sendRules"));
    List<AnchorRule> recvRules = asRules(map.get("recvRules"));

    return new NettraceProfile(
        id, description, targetPrefixes, excludePrefixes, sendRules, recvRules);
  }

  private static String stripExtension(String fileName) {
    int dot = fileName.lastIndexOf('.');
    return dot <= 0 ? fileName : fileName.substring(0, dot);
  }

  private static String asString(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castMap(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<String> asStringList(Object value) {
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?>)) {
      throw new IllegalArgumentException("Expected list but got: " + value);
    }
    List<String> result = new ArrayList<>();
    for (Object item : (List<Object>) value) {
      if (item != null) {
        result.add(String.valueOf(item).trim());
      }
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private static List<AnchorRule> asRules(Object value) {
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?>)) {
      throw new IllegalArgumentException("Expected rules list but got: " + value);
    }

    List<AnchorRule> rules = new ArrayList<>();
    for (Object item : (List<Object>) value) {
      if (!(item instanceof Map<?, ?>)) {
        throw new IllegalArgumentException("Rule must be a mapping: " + item);
      }
      Map<String, Object> map = castMap(item);
      String ownerPattern = asString(map.get("ownerPattern"));
      if (ownerPattern.isEmpty()) {
        throw new IllegalArgumentException("Rule is missing ownerPattern: " + item);
      }
      String methodPattern = asString(map.getOrDefault("methodPattern", "*"));
      String descriptorPattern = asString(map.getOrDefault("descriptorPattern", "*"));
      String reason = asString(map.getOrDefault("reason", "profile-rule"));
      rules.add(new AnchorRule(ownerPattern, methodPattern, descriptorPattern, reason));
    }

    return rules;
  }
}
