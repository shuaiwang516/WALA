package org.zlab.nettrace.analysis;

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

public final class EntrypointConfigLoader {
  private EntrypointConfigLoader() {}

  public static List<String> loadMainClasses(Path path) throws IOException {
    String fileName = path.getFileName().toString().toLowerCase();
    if (fileName.endsWith(".yaml") || fileName.endsWith(".yml")) {
      return loadFromYaml(path);
    }
    return loadFromText(path);
  }

  private static List<String> loadFromText(Path path) throws IOException {
    List<String> result = new ArrayList<>();
    for (String line : Files.readAllLines(path)) {
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) {
        continue;
      }
      result.add(trimmed);
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private static List<String> loadFromYaml(Path path) throws IOException {
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    Yaml yaml = new Yaml(new SafeConstructor(options));
    try (Reader reader = Files.newBufferedReader(path)) {
      Object loaded = yaml.load(reader);
      if (loaded instanceof List<?>) {
        List<String> entries = new ArrayList<>();
        for (Object value : (List<Object>) loaded) {
          if (value != null) {
            entries.add(String.valueOf(value).trim());
          }
        }
        return entries;
      }
      if (!(loaded instanceof Map<?, ?>)) {
        throw new IllegalArgumentException("Entrypoint YAML must be a list or a mapping");
      }
      Map<Object, Object> map = (Map<Object, Object>) loaded;
      Object value = map.get("mainClasses");
      if (!(value instanceof List<?>)) {
        throw new IllegalArgumentException("Entrypoint YAML must contain mainClasses list");
      }
      List<String> classes = new ArrayList<>();
      for (Object item : (List<Object>) value) {
        if (item != null) {
          classes.add(String.valueOf(item).trim());
        }
      }
      return classes;
    }
  }
}
