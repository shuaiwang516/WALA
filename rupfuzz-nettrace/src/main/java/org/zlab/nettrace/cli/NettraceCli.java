package org.zlab.nettrace.cli;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.zlab.nettrace.analysis.AnalysisConfig;
import org.zlab.nettrace.analysis.AnalysisStats;
import org.zlab.nettrace.analysis.EntrypointMode;
import org.zlab.nettrace.analysis.Phase2DraftAnalyzer;
import org.zlab.nettrace.analysis.Phase2DraftResult;
import org.zlab.nettrace.analysis.PrecisionMode;
import org.zlab.nettrace.analysis.WalaAnalysisBuilder;
import org.zlab.nettrace.analysis.WalaArtifacts;
import org.zlab.nettrace.anchors.AnchorDiscoverer;
import org.zlab.nettrace.anchors.AnchorDiscoveryResult;
import org.zlab.nettrace.output.AnalysisReportWriter;
import org.zlab.nettrace.output.JsonOutputWriter;
import org.zlab.nettrace.output.RawAnchorSchema;
import org.zlab.nettrace.profiles.NettraceProfile;
import org.zlab.nettrace.profiles.ProfileLoader;

public final class NettraceCli {
  private static final String CMD = "nettrace-cli";

  private NettraceCli() {}

  public static void main(String[] args) {
    int exitCode = run(args);
    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }

  public static int run(String[] args) {
    Options options = buildOptions();
    CommandLine commandLine;
    try {
      CommandLineParser parser = new DefaultParser();
      commandLine = parser.parse(options, args);
    } catch (ParseException e) {
      System.err.println("Argument parsing error: " + e.getMessage());
      printUsage(options);
      return 2;
    }

    if (commandLine.hasOption("help")) {
      printUsage(options);
      return 0;
    }

    try {
      AnalysisConfig initialConfig = toConfig(commandLine);
      NettraceProfile profile =
          ProfileLoader.load(initialConfig.profile(), initialConfig.profilesDir());
      AnalysisConfig config = mergeProfileConfig(initialConfig, profile);

      Files.createDirectories(config.outputDir());

      AnalysisStats stats = new AnalysisStats();
      stats.precision = config.precisionMode().cliName();
      stats.nCfaLevel = config.nCfaLevel();
      stats.entrypointMode = config.entrypointMode().cliName();
      if (profile != null) {
        stats.addNote("Loaded profile: " + profile.id());
      }

      WalaArtifacts artifacts = new WalaAnalysisBuilder().build(config, profile, stats);

      AnchorDiscoveryResult anchorResult =
          new AnchorDiscoverer().discover(artifacts, config, profile);
      stats.rawSendAnchorCount = anchorResult.rawSendAnchors().size();
      stats.rawRecvAnchorCount = anchorResult.rawRecvAnchors().size();

      JsonOutputWriter.write(
          config.outputDir().resolve("rawSendAnchors.json"), anchorResult.rawSendAnchors());
      JsonOutputWriter.write(
          config.outputDir().resolve("rawRecvAnchors.json"), anchorResult.rawRecvAnchors());
      Files.writeString(
          config.outputDir().resolve("rawAnchorSchema.json"),
          RawAnchorSchema.JSON_SCHEMA,
          StandardCharsets.UTF_8);

      if (config.phase2Draft()) {
        Phase2DraftResult phase2 =
            new Phase2DraftAnalyzer()
                .analyze(
                    artifacts,
                    anchorResult.rawSendAnchors(),
                    anchorResult.rawRecvAnchors(),
                    anchorResult.resolvedSendAnchors(),
                    anchorResult.resolvedRecvAnchors());

        JsonOutputWriter.write(
            config.outputDir().resolve("netSendPoints.json"), phase2.sendPoints());
        JsonOutputWriter.write(
            config.outputDir().resolve("netRecvBeginPoints.json"), phase2.recvBeginPoints());
        JsonOutputWriter.write(
            config.outputDir().resolve("netRecvEndPoints.json"), phase2.recvEndPoints());
        JsonOutputWriter.write(
            config.outputDir().resolve("netPhase2Diagnostics.json"), phase2.diagnostics());
        stats.phase2ResolvedSendCount = phase2.diagnostics().resolvedSend();
        stats.phase2ResolvedRecvCount = phase2.diagnostics().resolvedRecv();
        stats.phase2FallbackSendCount = phase2.diagnostics().fallbackSend();
        stats.phase2FallbackRecvCount = phase2.diagnostics().fallbackRecv();
      }

      AnalysisReportWriter.write(config.outputDir().resolve("netAnalysisReport.md"), config, stats);

      System.out.println("Nettrace analysis complete.");
      System.out.println("  output: " + config.outputDir());
      System.out.println("  rawSendAnchors: " + stats.rawSendAnchorCount);
      System.out.println("  rawRecvAnchors: " + stats.rawRecvAnchorCount);
      System.out.println("  callGraphNodes: " + stats.callGraphNodeCount);
      return 0;
    } catch (Exception e) {
      System.err.println("Nettrace analysis failed: " + e.getMessage());
      e.printStackTrace(System.err);
      return 1;
    }
  }

  private static Options buildOptions() {
    Options options = new Options();

    options.addOption(Option.builder().longOpt("help").desc("Show usage").build());
    options.addOption(
        Option.builder()
            .longOpt("app-classpath")
            .hasArg()
            .desc("Application classpath (path-separated)")
            .build());
    options.addOption(
        Option.builder()
            .longOpt("input-jar")
            .hasArgs()
            .desc("Application jar(s) to include in analysis scope")
            .build());
    options.addOption(
        Option.builder().longOpt("main-class").hasArgs().desc("Main class(es)").build());
    options.addOption(
        Option.builder()
            .longOpt("entrypoint-config")
            .hasArg()
            .desc("Entrypoint config file (txt/yaml)")
            .build());
    options.addOption(
        Option.builder()
            .longOpt("target-prefix")
            .hasArgs()
            .desc("Restrict anchor extraction to class prefix(es)")
            .build());
    options.addOption(
        Option.builder()
            .longOpt("exclude-prefix")
            .hasArgs()
            .desc("Exclude package prefix(es) from analysis scope")
            .build());
    options.addOption(
        Option.builder().longOpt("exclusions-file").hasArg().desc("WALA exclusions file").build());
    options.addOption(
        Option.builder()
            .longOpt("output-dir")
            .hasArg()
            .desc("Output directory (default: output)")
            .build());
    options.addOption(
        Option.builder()
            .longOpt("precision")
            .hasArg()
            .desc("Precision mode: rta|zero-cfa|zero-one-cfa|zero-one-container-cfa|n-cfa")
            .build());
    options.addOption(
        Option.builder()
            .longOpt("n-cfa-level")
            .hasArg()
            .desc("Context depth for n-cfa mode")
            .build());
    options.addOption(
        Option.builder()
            .longOpt("entrypoint-mode")
            .hasArg()
            .desc("Entrypoint mode: main|all-application|profile-seeded")
            .build());
    options.addOption(
        Option.builder().longOpt("profile").hasArg().desc("Profile name or YAML path").build());
    options.addOption(
        Option.builder()
            .longOpt("profiles-dir")
            .hasArg()
            .desc("Directory for profile files (default: profiles)")
            .build());
    options.addOption(
        Option.builder()
            .longOpt("disable-phase2-draft")
            .desc("Disable draft Phase 2 slicing outputs")
            .build());

    return options;
  }

  private static AnalysisConfig toConfig(CommandLine commandLine) {
    String appClasspath = commandLine.getOptionValue("app-classpath", "");
    List<Path> inputJars = toPathList(commandLine.getOptionValues("input-jar"));
    List<String> mainClasses = toStringList(commandLine.getOptionValues("main-class"));
    Path entrypointConfig = toPath(commandLine.getOptionValue("entrypoint-config"));
    List<String> targetPrefixes = toStringList(commandLine.getOptionValues("target-prefix"));
    List<String> excludePrefixes = toStringList(commandLine.getOptionValues("exclude-prefix"));
    Path exclusionsFile = toPath(commandLine.getOptionValue("exclusions-file"));
    Path outputDir = Path.of(commandLine.getOptionValue("output-dir", "output"));

    PrecisionMode precisionMode =
        PrecisionMode.parse(commandLine.getOptionValue("precision", "zero-one-cfa"));
    int nCfaLevel = Integer.parseInt(commandLine.getOptionValue("n-cfa-level", "1"));
    EntrypointMode entrypointMode =
        EntrypointMode.parse(commandLine.getOptionValue("entrypoint-mode", "profile-seeded"));

    String profile = commandLine.getOptionValue("profile", "");
    Path profilesDir = Path.of(commandLine.getOptionValue("profiles-dir", "profiles"));
    boolean phase2Draft = !commandLine.hasOption("disable-phase2-draft");

    return new AnalysisConfig(
        appClasspath,
        inputJars,
        mainClasses,
        entrypointConfig,
        targetPrefixes,
        excludePrefixes,
        exclusionsFile,
        outputDir,
        precisionMode,
        nCfaLevel,
        entrypointMode,
        profile,
        profilesDir,
        phase2Draft);
  }

  private static AnalysisConfig mergeProfileConfig(AnalysisConfig config, NettraceProfile profile) {
    if (profile == null) {
      return config;
    }

    Set<String> mergedTargetPrefixes = new LinkedHashSet<>(config.targetPrefixes());
    mergedTargetPrefixes.addAll(profile.targetPrefixes());

    Set<String> mergedExcludePrefixes = new LinkedHashSet<>(config.excludePrefixes());
    mergedExcludePrefixes.addAll(profile.excludePrefixes());

    return new AnalysisConfig(
        config.appClasspath(),
        config.inputJars(),
        config.mainClasses(),
        config.entrypointConfig(),
        List.copyOf(mergedTargetPrefixes),
        List.copyOf(mergedExcludePrefixes),
        config.exclusionsFile(),
        config.outputDir(),
        config.precisionMode(),
        config.nCfaLevel(),
        config.entrypointMode(),
        config.profile(),
        config.profilesDir(),
        config.phase2Draft());
  }

  private static Path toPath(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return Path.of(value);
  }

  private static List<Path> toPathList(String[] values) {
    List<Path> paths = new ArrayList<>();
    if (values == null) {
      return paths;
    }
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        paths.add(Path.of(value));
      }
    }
    return paths;
  }

  private static List<String> toStringList(String[] values) {
    List<String> list = new ArrayList<>();
    if (values == null) {
      return list;
    }
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        list.add(value.trim());
      }
    }
    return list;
  }

  private static void printUsage(Options options) {
    HelpFormatter formatter = new HelpFormatter();
    formatter.printHelp(CMD, options, true);
  }
}
