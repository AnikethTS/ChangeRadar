package io.github.anikethts.changeradar.cli;

import io.github.anikethts.changeradar.core.GitAccessException;
import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
    name = "review",
    description =
        "Open a local review page for an impact report. Binds to 127.0.0.1 only and does not"
            + " upload source.")
public final class ReviewCommand implements Callable<Integer> {

  @Option(
      names = "--base",
      description =
          "Base Git ref. With --head, analyze before serving. Omit both to serve an"
              + " existing report.")
  String base;

  @Option(names = "--head", description = "Head Git ref. Required when --base is set.")
  String head;

  @Option(
      names = "--repo",
      defaultValue = ".",
      description = "Path to the Git repository. Default: current directory.")
  Path repo;

  @Option(
      names = "--output",
      description = "Report directory. Default: build/changeradar under --repo.")
  Path output;

  @Option(
      names = "--format",
      split = ",",
      description =
          "Formats to write when --base and --head are set. html is always included. Default:"
              + " html.")
  List<String> formats;

  @Option(
      names = "--policy",
      split = ",",
      description = "Named blocking policies, passed through to analyze.")
  List<String> policies;

  @Option(names = "--policy-file", description = "Policy YAML, passed through to analyze.")
  Path policyFile;

  @Option(names = "--baseline", description = "Policy baseline, passed through to analyze.")
  Path baseline;

  @Option(names = "--write-baseline", description = "Write a policy baseline, then analyze.")
  Path writeBaseline;

  @Option(
      names = "--suppress-file",
      description = "Finding suppressions, passed through to" + " analyze.")
  Path suppressFile;

  @Option(
      names = "--write-suppressions",
      description = "Write finding suppressions, then" + " analyze.")
  Path writeSuppressions;

  @Option(names = "--classpath", description = "Already-built jars, passed through to analyze.")
  List<String> classpath;

  @Option(names = "--coverage", description = "JaCoCo or LCOV input, passed through to analyze.")
  List<String> coverage;

  @Option(names = "--junit", description = "JUnit XML input, passed through to analyze.")
  List<String> junit;

  @Option(names = "--full-index", description = "Parse every HEAD source file.")
  boolean fullIndex;

  @Option(
      names = "--port",
      defaultValue = "0",
      description = "Loopback port. Default: an open" + " port.")
  int port;

  @Option(names = "--open", description = "Ask the desktop to open the review URL.")
  boolean open;

  @Override
  public Integer call() throws Exception {
    if ((base == null) != (head == null)) {
      throw new GitAccessException(
          "Pass both --base and --head to analyze, or omit both to serve" + " an existing report.");
    }
    Path repository = repo.toAbsolutePath().normalize();
    if (!Files.isDirectory(repository)) {
      throw new GitAccessException("Repository path is not a directory: " + repository);
    }
    Path dest =
        output == null
            ? repository.resolve("build").resolve("changeradar")
            : output.toAbsolutePath().normalize();
    int code = 0;
    if (base != null) {
      code =
          AnalyzeCommand.create(
                  base,
                  head,
                  repository,
                  withHtml(formats),
                  dest,
                  policies,
                  policyFile,
                  baseline,
                  writeBaseline,
                  suppressFile,
                  writeSuppressions,
                  classpath,
                  coverage,
                  junit,
                  fullIndex)
              .call();
    }
    Path html = ensureHtml(dest);
    if (code != 0) {
      System.err.println("changeradar: analyze finished with exit " + code);
    }
    try (ReviewServer server = ReviewServer.start(repository, html, port)) {
      String url = "http://127.0.0.1:" + server.port() + "/";
      System.out.println(url);
      if (open) {
        open(url);
      }
      Thread.currentThread().join();
    }
    return code;
  }

  private static List<String> withHtml(List<String> requested) {
    List<String> selected = new ArrayList<>();
    if (requested != null) {
      selected.addAll(requested);
    }
    if (selected.isEmpty()) {
      selected.add("html");
    }
    boolean hasHtml = selected.stream().anyMatch(format -> "html".equalsIgnoreCase(format.trim()));
    if (!hasHtml) {
      selected.add("html");
    }
    return selected;
  }

  private static Path ensureHtml(Path dest) throws Exception {
    Path html = dest.resolve("report.html");
    if (Files.isRegularFile(html)) {
      return html;
    }
    Path json = dest.resolve("report.json");
    if (!Files.isRegularFile(json)) {
      throw new GitAccessException(
          "No report.html or report.json in " + dest + ". Run analyze --format html first.");
    }
    Files.createDirectories(dest);
    Files.writeString(html, HtmlReportWriter.render(Files.readString(json)));
    return html;
  }

  private static void open(String url) {
    try {
      if (Desktop.isDesktopSupported()) {
        Desktop.getDesktop().browse(URI.create(url));
      }
    } catch (Exception ignored) {
      // The URL is already printed.
    }
  }
}
