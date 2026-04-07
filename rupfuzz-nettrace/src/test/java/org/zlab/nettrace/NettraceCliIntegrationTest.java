package org.zlab.nettrace;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.zlab.nettrace.cli.NettraceCli;

class NettraceCliIntegrationTest {
  @Test
  void cliProducesExpectedOutputFiles() throws Exception {
    Path srcDir = Files.createTempDirectory("nettrace-src");
    Path classesDir = Files.createTempDirectory("nettrace-classes");
    Path outputDir = Files.createTempDirectory("nettrace-output");

    Path javaFile = srcDir.resolve("tiny/TinyNetMain.java");
    Files.createDirectories(javaFile.getParent());
    Files.writeString(
        javaFile,
        "package tiny;\n"
            + "\n"
            + "import java.io.ByteArrayInputStream;\n"
            + "import java.io.ByteArrayOutputStream;\n"
            + "\n"
            + "public class TinyNetMain {\n"
            + "  public static void main(String[] args) throws Exception {\n"
            + "    ByteArrayOutputStream out = new ByteArrayOutputStream();\n"
            + "    out.write(7);\n"
            + "    ByteArrayInputStream in = new ByteArrayInputStream(new byte[] {7});\n"
            + "    in.read();\n"
            + "  }\n"
            + "}\n",
        StandardCharsets.UTF_8);

    compileJava(javaFile, classesDir);

    int exit =
        NettraceCli.run(
            new String[] {
              "--app-classpath",
              classesDir.toString(),
              "--main-class",
              "tiny.TinyNetMain",
              "--target-prefix",
              "tiny",
              "--precision",
              "zero-cfa",
              "--disable-phase2-draft",
              "--output-dir",
              outputDir.toString()
            });

    assertThat(exit).isEqualTo(0);
    assertFile(outputDir.resolve("netAnalysisReport.md"));
    assertFile(outputDir.resolve("rawSendAnchors.json"));
    assertFile(outputDir.resolve("rawRecvAnchors.json"));

    JsonArray sendAnchors = readArray(outputDir.resolve("rawSendAnchors.json"));
    JsonArray recvAnchors = readArray(outputDir.resolve("rawRecvAnchors.json"));
    assertThat(sendAnchors).isNotEmpty();
    assertThat(recvAnchors).isNotEmpty();
  }

  private static void compileJava(Path javaFile, Path classesDir) {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new IllegalStateException("No system Java compiler available");
    }

    int code = compiler.run(null, null, null, "-d", classesDir.toString(), javaFile.toString());

    if (code != 0) {
      throw new IllegalStateException("javac failed with exit code " + code);
    }
  }

  private static void assertFile(Path path) {
    assertThat(path).exists();
    assertThat(path).isRegularFile();
  }

  private static JsonArray readArray(Path file) throws IOException {
    String payload = Files.readString(file, StandardCharsets.UTF_8);
    JsonElement parsed = JsonParser.parseString(payload);
    return parsed.getAsJsonArray();
  }
}
