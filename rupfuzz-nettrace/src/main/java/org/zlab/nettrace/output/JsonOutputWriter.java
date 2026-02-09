package org.zlab.nettrace.output;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class JsonOutputWriter {
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

  private JsonOutputWriter() {}

  public static void write(Path outputFile, Object payload) throws IOException {
    ensureParent(outputFile);
    Files.writeString(outputFile, GSON.toJson(payload), StandardCharsets.UTF_8);
  }

  private static void ensureParent(Path outputFile) throws IOException {
    Path parent = outputFile.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
  }
}
