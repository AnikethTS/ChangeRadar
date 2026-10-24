package io.github.anikethts.changeradar.analyzer.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.anikethts.changeradar.core.AnalysisResult;
import io.github.anikethts.changeradar.core.ChangeType;
import io.github.anikethts.changeradar.core.ChangedFile;
import io.github.anikethts.changeradar.core.Confidence;
import io.github.anikethts.changeradar.core.LineRange;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SqlLanguageAnalyzerTest {

  private final SqlLanguageAnalyzer analyzer = new SqlLanguageAnalyzer();

  @Test
  void mapsChangedTableAndFindsSqlReferenceAndTest() {
    String table =
        """
        create table accounts (
          id int
        );
        """;
    String report =
        """
        select id
        from accounts;
        """;
    String test =
        """
        -- exercises accounts
        select * from accounts;
        """;
    AnalysisResult result =
        analyzer.analyze(
            List.of(
                new ChangedFile(
                    "db/accounts.sql", ChangeType.MODIFY, null, List.of(new LineRange(1, 3)))),
            Map.of(
                "db/accounts.sql",
                table,
                "reports/monthly.sql",
                report,
                "tests/accounts_test.sql",
                test),
            Map.of());
    assertTrue(
        result.changedSymbols().stream()
            .anyMatch(symbol -> "accounts".equals(symbol.name()) && "table".equals(symbol.kind())));
    assertTrue(
        result.findings().stream()
            .anyMatch(
                finding ->
                    "CALLER".equals(finding.type())
                        && finding.confidence() == Confidence.DIRECT
                        && finding.location().path().equals("reports/monthly.sql")));
    assertTrue(
        result.tests().stream()
            .anyMatch(recommendation -> recommendation.path().equals("tests/accounts_test.sql")));
    assertTrue(
        result.limitations().stream().anyMatch(item -> "SQL_REFERENCES".equals(item.code())));
  }

  @Test
  void duplicateNamesAreHeuristic() {
    String changed =
        """
        create table accounts (id int);
        """;
    String other =
        """
        create table accounts (email text);
        """;
    String reader =
        """
        select * from accounts;
        """;
    AnalysisResult result =
        analyzer.analyze(
            List.of(
                new ChangedFile("db/a.sql", ChangeType.MODIFY, null, List.of(new LineRange(1, 1)))),
            Map.of("db/a.sql", changed, "db/b.sql", other, "db/read.sql", reader),
            Map.of());
    assertTrue(
        result.findings().stream()
            .anyMatch(
                finding ->
                    finding.location().path().equals("db/read.sql")
                        && finding.confidence() == Confidence.HEURISTIC));
  }

  @Test
  void stringLiteralIsNotAReference() {
    String table =
        """
        create table accounts (id int);
        """;
    String other =
        """
        select 'accounts' as label;
        """;
    AnalysisResult result =
        analyzer.analyze(
            List.of(
                new ChangedFile(
                    "db/accounts.sql", ChangeType.MODIFY, null, List.of(new LineRange(1, 1)))),
            Map.of("db/accounts.sql", table, "db/other.sql", other),
            Map.of());
    assertTrue(
        result.findings().stream()
            .noneMatch(finding -> finding.location().path().equals("db/other.sql")));
  }

  @Test
  void commentOnlyChangeIsFormatOnly() {
    String base = "create table accounts (id int);\n";
    String head = "create table accounts (id int); -- note\n";
    AnalysisResult result =
        analyzer.analyze(
            List.of(
                new ChangedFile(
                    "db/accounts.sql", ChangeType.MODIFY, null, List.of(new LineRange(1, 1)))),
            Map.of("db/accounts.sql", head),
            Map.of("db/accounts.sql", base));
    assertTrue(result.changedSymbols().isEmpty());
    assertTrue(
        result.limitations().stream().anyMatch(item -> "FORMAT_ONLY_CHANGES".equals(item.code())));
  }

  @Test
  void removedViewIsADelete() {
    String base =
        """
        create view open_accounts as select * from accounts;
        create view closed_accounts as select * from accounts;
        """;
    String head =
        """
        create view closed_accounts as select * from accounts;
        """;
    AnalysisResult result =
        analyzer.analyze(
            List.of(
                new ChangedFile(
                    "db/views.sql", ChangeType.MODIFY, null, List.of(new LineRange(1, 1)))),
            Map.of("db/views.sql", head),
            Map.of("db/views.sql", base));
    assertEquals(
        1,
        result.changedSymbols().stream()
            .filter(
                symbol ->
                    "open_accounts".equals(symbol.name())
                        && symbol.changeType() == ChangeType.DELETE)
            .count());
  }

  @Test
  void differentSchemaIsNotTheSameReference() {
    String table =
        """
        create table billing.accounts (id int);
        """;
    String other =
        """
        select * from audit.accounts;
        """;
    AnalysisResult result =
        analyzer.analyze(
            List.of(
                new ChangedFile(
                    "db/accounts.sql", ChangeType.MODIFY, null, List.of(new LineRange(1, 1)))),
            Map.of("db/accounts.sql", table, "db/other.sql", other),
            Map.of());
    assertFalse(
        result.findings().stream()
            .anyMatch(finding -> finding.location().path().equals("db/other.sql")));
  }
}
