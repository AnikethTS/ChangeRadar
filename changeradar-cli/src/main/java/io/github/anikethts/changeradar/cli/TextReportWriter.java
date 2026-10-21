package io.github.anikethts.changeradar.cli;

import static io.github.anikethts.changeradar.cli.ReportSupport.displayName;
import static io.github.anikethts.changeradar.cli.ReportSupport.groupedFindings;

import io.github.anikethts.changeradar.core.ChangedFile;
import io.github.anikethts.changeradar.core.ChangedSymbol;
import io.github.anikethts.changeradar.core.Confidence;
import io.github.anikethts.changeradar.core.ImpactFinding;
import io.github.anikethts.changeradar.core.Limitation;
import io.github.anikethts.changeradar.core.PolicyResult;
import io.github.anikethts.changeradar.core.Report;
import io.github.anikethts.changeradar.core.TestRecommendation;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

final class TextReportWriter {

  private TextReportWriter() {}

  static void writeTerminal(Report report, PrintStream out) {
    out.println("ChangeRadar " + report.toolVersion());
    out.println("schema " + report.schemaVersion());
    out.println("base   " + report.request().base());
    out.println("head   " + report.request().head());
    out.println("repo   " + report.request().repository());
    out.println();
    out.println("Changed files (" + report.changedFiles().size() + ")");
    if (report.changedFiles().isEmpty()) {
      out.println("  (none)");
    } else {
      for (ChangedFile file : report.changedFiles()) {
        out.println("  " + file.changeType() + "  " + file.path());
      }
    }
    out.println();
    out.println("Changed symbols (" + report.changedSymbols().size() + ")");
    if (report.changedSymbols().isEmpty()) {
      out.println("  (none)");
    } else {
      for (ChangedSymbol symbol : report.changedSymbols()) {
        out.println(
            "  "
                + symbol.changeType()
                + "  "
                + symbol.kind()
                + "  "
                + displayName(symbol)
                + "  "
                + symbol.location().path()
                + ":"
                + symbol.location().startLine());
      }
    }
    out.println();
    out.println("Potentially affected (" + report.findings().size() + ")");
    if (report.findings().isEmpty()) {
      out.println("  (none)");
    } else {
      groupedFindings(report.findings())
          .forEach(
              (path, findings) -> {
                out.println("  " + path);
                for (ImpactFinding finding : findings) {
                  out.println(
                      "    ["
                          + finding.confidence()
                          + "] "
                          + finding.type()
                          + "  "
                          + finding.summary()
                          + "  :"
                          + finding.location().startLine()
                          + "  "
                          + finding.id());
                }
              });
    }
    out.println();
    out.println("Tests to consider (" + report.tests().size() + ")");
    if (report.tests().isEmpty()) {
      out.println("  (none)");
    } else {
      for (TestRecommendation test : report.tests()) {
        out.println("  [" + test.confidence() + "] " + test.name() + "  " + test.path());
        out.println("           " + test.reason());
      }
    }
    out.println();
    out.println("Limitations");
    for (Limitation limitation : report.limitations()) {
      out.println("  [" + limitation.code() + "] " + limitation.message());
    }
    out.println();
    writePolicyTerminal(report.policy(), out);
  }

  static void writeMarkdown(Report report, Path file) throws IOException {
    StringBuilder md = new StringBuilder();
    md.append("# ChangeRadar report\n\n");
    md.append("- Tool: `changeradar ").append(report.toolVersion()).append("`\n");
    md.append("- Schema: `").append(report.schemaVersion()).append("`\n");
    md.append("- Base: `").append(report.request().base()).append("`\n");
    md.append("- Head: `").append(report.request().head()).append("`\n");
    md.append("- Repository: `").append(report.request().repository()).append("`\n\n");
    md.append("## Changed files\n\n");
    if (report.changedFiles().isEmpty()) {
      md.append("_None._\n\n");
    } else {
      md.append("| Change | Path |\n|---|---|\n");
      for (ChangedFile changed : report.changedFiles()) {
        md.append("| `")
            .append(changed.changeType())
            .append("` | `")
            .append(changed.path())
            .append("` |\n");
      }
      md.append('\n');
    }
    md.append("## Changed symbols\n\n");
    if (report.changedSymbols().isEmpty()) {
      md.append("_None._\n\n");
    } else {
      md.append("| Change | Kind | Symbol | Location |\n|---|---|---|---|\n");
      for (ChangedSymbol symbol : report.changedSymbols()) {
        md.append("| `")
            .append(symbol.changeType())
            .append("` | `")
            .append(symbol.kind())
            .append("` | `")
            .append(displayName(symbol))
            .append("` | `")
            .append(symbol.location().path())
            .append(":")
            .append(symbol.location().startLine())
            .append("` |\n");
      }
      md.append('\n');
    }
    md.append("## Potentially affected\n\n");
    if (report.findings().isEmpty()) {
      md.append("_None resolved._ This is not proof of no impact.\n\n");
    } else {
      groupedFindings(report.findings())
          .forEach(
              (path, findings) -> {
                md.append("### `").append(path).append("`\n\n");
                md.append("| ID | Confidence | Type | Summary | Line |\n|---|---|---|---|---|\n");
                for (ImpactFinding finding : findings) {
                  md.append("| `")
                      .append(finding.id())
                      .append("` | `")
                      .append(finding.confidence())
                      .append("` | `")
                      .append(finding.type())
                      .append("` | ")
                      .append(finding.summary())
                      .append(" | ")
                      .append(finding.location().startLine())
                      .append(" |\n");
                }
                md.append('\n');
              });
    }
    md.append("## Tests to consider\n\n");
    if (report.tests().isEmpty()) {
      md.append("_None selected._\n\n");
    } else {
      for (TestRecommendation test : report.tests()) {
        md.append("- **")
            .append(test.name())
            .append("** (`")
            .append(test.path())
            .append("`) — ")
            .append(test.reason())
            .append('\n');
      }
      md.append('\n');
    }
    md.append(
        "A suggested test is evidence of relevance, not proof that the test passed or failed.\n\n");
    md.append("## Limitations\n\n");
    for (Limitation limitation : report.limitations()) {
      md.append("- **")
          .append(limitation.code())
          .append("** — ")
          .append(limitation.message())
          .append('\n');
    }
    md.append('\n');
    md.append("## Policy\n\n");
    if (report.policy().blocking()) {
      md.append("Blocking policy violated. Exit code **1**.\n\n");
      for (String violation : report.policy().violations()) {
        md.append("- ").append(violation).append('\n');
      }
      md.append('\n');
    } else if (report.policy().violations().isEmpty()) {
      md.append(
          "No blocking policy failed. Exit 0 means analysis completed, not that the change is"
              + " safe.\n\n");
    }
    md.append(
        "Absence of a finding is not a guarantee of no impact. ChangeRadar does not deploy,"
            + " roll back, or skip the full test suite.\n");
    Files.writeString(file, md.toString());
  }

  static void writeCompact(Report report, Path file) throws IOException {
    Files.writeString(file, compactMarkdown(report));
  }

  static String compactMarkdown(Report report) {
    StringBuilder md = new StringBuilder();
    md.append("## ChangeRadar\n\n");
    if (report.policy().blocking()) {
      md.append("> [!CAUTION]\n");
      md.append("> **Policy blocking** (exit 1)\n");
      for (String violation : report.policy().violations()) {
        md.append("> - ").append(violation).append('\n');
      }
      md.append('\n');
    } else {
      md.append("> [!NOTE]\n");
      md.append(
          "> **Policy not blocking.** Exit 0 means analysis finished, not that the change is"
              + " safe.\n\n");
    }
    md.append("| Files | Symbols | Findings | Tests |\n| ---: | ---: | ---: | ---: |\n");
    md.append("| ")
        .append(report.changedFiles().size())
        .append(" | ")
        .append(report.changedSymbols().size())
        .append(" | ")
        .append(report.findings().size())
        .append(" | ")
        .append(report.tests().size())
        .append(" |\n\n");
    List<ImpactFinding> direct =
        report.findings().stream()
            .filter(finding -> finding.confidence() == Confidence.DIRECT)
            .sorted(
                Comparator.comparing((ImpactFinding f) -> f.location().path())
                    .thenComparing(f -> f.location().startLine()))
            .limit(8)
            .toList();
    long indirect =
        report.findings().stream().filter(f -> f.confidence() == Confidence.INDIRECT).count();
    long heuristic =
        report.findings().stream().filter(f -> f.confidence() == Confidence.HEURISTIC).count();
    md.append("### Top DIRECT findings\n\n");
    if (direct.isEmpty()) {
      md.append("_None._\n\n");
    } else {
      for (ImpactFinding finding : direct) {
        md.append("- **")
            .append(finding.type())
            .append("** `")
            .append(finding.location().path())
            .append(":")
            .append(finding.location().startLine())
            .append("` — ")
            .append(finding.summary())
            .append('\n');
      }
      md.append('\n');
    }
    md.append("_Indirect: ")
        .append(indirect)
        .append(". Heuristic: ")
        .append(heuristic)
        .append("._\n\n");
    md.append("### Tests\n\n");
    if (report.tests().isEmpty()) {
      md.append("_None selected._\n\n");
    } else {
      report.tests().stream()
          .limit(5)
          .forEach(
              test ->
                  md.append("- **")
                      .append(test.name())
                      .append("** (`")
                      .append(test.path())
                      .append("`)\n"));
      md.append('\n');
    }
    md.append("_Absence of a finding is not proof of no impact._\n");
    return md.toString();
  }

  static void writePolicyTerminal(PolicyResult policy, PrintStream out) {
    if (policy.blocking()) {
      out.println("Policy  BLOCKING");
      for (String violation : policy.violations()) {
        out.println("  " + violation);
      }
      out.println();
      out.println("Exit 1: an enabled blocking policy was violated.");
      return;
    }
    out.println("Policy  none blocking");
    out.println();
    out.println("Exit 0 means analysis completed, not that the change is safe.");
  }
}
