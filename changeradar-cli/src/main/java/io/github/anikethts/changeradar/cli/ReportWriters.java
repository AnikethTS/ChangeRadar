package io.github.anikethts.changeradar.cli;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.anikethts.changeradar.core.ChangedFile;
import io.github.anikethts.changeradar.core.ChangedSymbol;
import io.github.anikethts.changeradar.core.ImpactFinding;
import io.github.anikethts.changeradar.core.Limitation;
import io.github.anikethts.changeradar.core.Report;
import io.github.anikethts.changeradar.core.TestRecommendation;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

final class ReportWriters {

  private final ObjectMapper mapper;

  ReportWriters() {
    this.mapper =
        new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
  }

  void writeTerminal(Report report, PrintStream out) {
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
      for (ImpactFinding finding : report.findings()) {
        out.println(
            "  ["
                + finding.confidence()
                + "] "
                + finding.type()
                + "  "
                + finding.summary()
                + "  "
                + finding.location().path()
                + ":"
                + finding.location().startLine());
      }
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
    out.println(
        "No blocking policy ran. Exit 0 means analysis completed, not that the change is safe.");
  }

  void writeMarkdown(Report report, Path file) throws IOException {
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
      md.append("| Confidence | Type | Summary | Location |\n|---|---|---|---|\n");
      for (ImpactFinding finding : report.findings()) {
        md.append("| `")
            .append(finding.confidence())
            .append("` | `")
            .append(finding.type())
            .append("` | ")
            .append(finding.summary())
            .append(" | `")
            .append(finding.location().path())
            .append(":")
            .append(finding.location().startLine())
            .append("` |\n");
      }
      md.append('\n');
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
    md.append(
        "Absence of a finding is not a guarantee of no impact. ChangeRadar does not deploy,"
            + " roll back, or skip the full test suite.\n");
    Files.writeString(file, md.toString());
  }

  void writeJson(Report report, Path file) throws IOException {
    JsonReport body =
        new JsonReport(
            report.schemaVersion(),
            report.toolVersion(),
            report.generatedAt(),
            report.request().repository().toString(),
            report.request().base(),
            report.request().head(),
            report.changedFiles(),
            report.changedSymbols(),
            report.findings(),
            report.tests(),
            report.limitations(),
            report.policy().blocking(),
            report.policy().violations());
    mapper.writeValue(file.toFile(), body);
  }

  private static String displayName(ChangedSymbol symbol) {
    if ("type".equals(symbol.kind()) || symbol.owner().isEmpty()) {
      return symbol.name();
    }
    return symbol.owner() + "#" + symbol.name();
  }

  record JsonReport(
      String schemaVersion,
      String toolVersion,
      Instant generatedAt,
      String repository,
      String base,
      String head,
      List<ChangedFile> changedFiles,
      List<ChangedSymbol> changedSymbols,
      List<ImpactFinding> findings,
      List<TestRecommendation> recommendedTests,
      List<Limitation> limitations,
      boolean policyBlocking,
      List<String> policyViolations) {}
}
