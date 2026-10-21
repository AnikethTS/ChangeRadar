package io.github.anikethts.changeradar.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Test outcomes from JUnit XML (Surefire, Gradle, pytest --junitxml, jest-junit). */
public final class JunitResults {

  private final List<Case> cases = new ArrayList<>();
  private int filesLoaded;

  public static JunitResults load(List<Path> files, List<Limitation> limitations) {
    JunitResults results = new JunitResults();
    for (Path file : files) {
      try {
        String sample = Files.readString(file);
        if (!looksLikeJunit(sample)) {
          limitations.add(
              new Limitation("JUNIT_PARSE_FAILED", "Not JUnit XML: " + file + ". Ignored."));
          continue;
        }
        results.addDocument(file);
        results.filesLoaded++;
      } catch (Exception e) {
        limitations.add(
            new Limitation(
                "JUNIT_PARSE_FAILED",
                "Could not read JUnit report " + file + ": " + e.getMessage()));
      }
    }
    return results;
  }

  public boolean isEmpty() {
    return cases.isEmpty();
  }

  public int filesLoaded() {
    return filesLoaded;
  }

  public ExecutionStatus statusFor(TestRecommendation test) {
    ExecutionStatus best = ExecutionStatus.UNKNOWN;
    for (Case item : cases) {
      if (!item.matches(test)) {
        continue;
      }
      if (item.status == ExecutionStatus.FAILED || item.status == ExecutionStatus.ERROR) {
        return item.status;
      }
      if (item.status == ExecutionStatus.SKIPPED && best != ExecutionStatus.PASSED) {
        best = ExecutionStatus.SKIPPED;
      } else if (item.status == ExecutionStatus.PASSED) {
        best = ExecutionStatus.PASSED;
      }
    }
    return best;
  }

  public List<Case> failed() {
    List<Case> failed = new ArrayList<>();
    for (Case item : cases) {
      if (item.status == ExecutionStatus.FAILED || item.status == ExecutionStatus.ERROR) {
        failed.add(item);
      }
    }
    return failed;
  }

  private void addDocument(Path file) throws Exception {
    Document document = XmlSecure.factory().newDocumentBuilder().parse(file.toFile());
    NodeList nodes = document.getElementsByTagName("testcase");
    for (int i = 0; i < nodes.getLength(); i++) {
      Element testcase = (Element) nodes.item(i);
      String className = testcase.getAttribute("classname");
      String name = testcase.getAttribute("name");
      ExecutionStatus status = ExecutionStatus.PASSED;
      if (testcase.getElementsByTagName("failure").getLength() > 0) {
        status = ExecutionStatus.FAILED;
      } else if (testcase.getElementsByTagName("error").getLength() > 0) {
        status = ExecutionStatus.ERROR;
      } else if (testcase.getElementsByTagName("skipped").getLength() > 0) {
        status = ExecutionStatus.SKIPPED;
      }
      cases.add(new Case(className, name, status));
    }
  }

  private static boolean looksLikeJunit(String sample) {
    String head = sample.length() > 500 ? sample.substring(0, 500) : sample;
    return head.contains("<testsuite") || head.contains("<testcase");
  }

  public record Case(String className, String name, ExecutionStatus status) {

    boolean matches(TestRecommendation test) {
      String simple = simpleName(className);
      String recName = test.name();
      String recPath = test.path().replace('\\', '/').toLowerCase(Locale.ROOT);
      if (recName.equals(name) || recName.equals(simple) || recName.equals(className)) {
        return true;
      }
      if (!simple.isEmpty() && recPath.contains(simple.toLowerCase(Locale.ROOT))) {
        return true;
      }
      String file = recPath.substring(recPath.lastIndexOf('/') + 1);
      return !simple.isEmpty() && file.startsWith(simple.toLowerCase(Locale.ROOT));
    }

    boolean relatesTo(List<ChangedFile> changed) {
      Set<String> stems = CoverageIndex.productionStems(className.replace('.', '/') + ".java");
      stems.addAll(CoverageIndex.productionStems(name));
      for (ChangedFile file : changed) {
        String unix = file.path().replace('\\', '/');
        String fileName = unix.substring(unix.lastIndexOf('/') + 1);
        int dot = fileName.lastIndexOf('.');
        String stem = (dot < 0 ? fileName : fileName.substring(0, dot)).toLowerCase(Locale.ROOT);
        if (stems.contains(stem)) {
          return true;
        }
      }
      return false;
    }

    String displayName() {
      if (className == null || className.isBlank()) {
        return name;
      }
      String simple = simpleName(className);
      return name == null || name.isBlank() ? simple : simple + "#" + name;
    }

    String displayPath() {
      if (className == null || className.isBlank()) {
        return name;
      }
      return className.replace('.', '/') + ".java";
    }

    private static String simpleName(String className) {
      if (className == null || className.isBlank()) {
        return "";
      }
      int dot = className.lastIndexOf('.');
      return dot < 0 ? className : className.substring(dot + 1);
    }
  }
}
