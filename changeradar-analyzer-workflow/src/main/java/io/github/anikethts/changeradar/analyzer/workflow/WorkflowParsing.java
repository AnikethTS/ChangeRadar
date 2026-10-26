package io.github.anikethts.changeradar.analyzer.workflow;

import io.github.anikethts.changeradar.core.Limitation;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class WorkflowParsing {

  private WorkflowParsing() {}

  static ParsedWorkflow parse(String path, String source, List<Limitation> limitations) {
    try {
      return parse(path, source);
    } catch (IllegalArgumentException ex) {
      limitations.add(
          new Limitation(
              "WORKFLOW_PARSE_FAILED",
              "Could not parse "
                  + path
                  + ": unclosed quote. Literal block scalars are not fully parsed."));
      return null;
    }
  }

  static ParsedWorkflow parse(String path, String source) {
    String unix = path.replace('\\', '/');
    boolean actionFile = isActionFile(unix);
    List<Job> jobs = new ArrayList<>();
    List<Ref> refs = new ArrayList<>();
    boolean inJobs = false;
    boolean inOn = false;
    int jobIndent = -1;
    Job current = null;
    boolean callable = false;
    int onStart = 0;
    int onEnd = 0;
    int needsIndent = -1;
    String needsJob = "";
    String[] lines = source.split("\n", -1);
    for (int n = 0; n < lines.length; n++) {
      int lineNo = n + 1;
      String raw = stripComment(lines[n]);
      if (raw.isBlank()) {
        continue;
      }
      int indent = indentOf(raw);
      String rest = raw.substring(contentStart(raw)).trim();
      boolean item = false;
      if (rest.startsWith("- ")) {
        item = true;
        rest = rest.substring(2).trim();
      } else if (rest.equals("-")) {
        item = true;
        rest = "";
      }
      KeyValue keyValue = keyValue(rest);
      if (indent == 0 && keyValue.key() != null && !item) {
        current = finish(current, jobs);
        inJobs = keyValue.key().equals("jobs");
        inOn = keyValue.key().equals("on");
        jobIndent = -1;
        needsIndent = -1;
        if (inOn) {
          onStart = lineNo;
          onEnd = lineNo;
          callable = callable || mentionsWorkflowCall(keyValue.value());
        }
        continue;
      }
      if (inOn) {
        onEnd = lineNo;
        if (keyValue.key() != null && keyValue.key().equals("workflow_call")) {
          callable = true;
        } else if (mentionsWorkflowCall(rest)) {
          callable = true;
        }
      }
      if (inJobs) {
        if (jobIndent < 0 && keyValue.key() != null) {
          jobIndent = indent;
        }
        if (jobIndent >= 0 && indent == jobIndent && keyValue.key() != null && !item) {
          current = finish(current, jobs);
          current = new Job(keyValue.key(), lineNo, lineNo);
          needsIndent = -1;
          continue;
        }
        if (current != null && indent > jobIndent) {
          current = new Job(current.name(), current.startLine(), lineNo);
        }
      }
      if (needsIndent >= 0 && indent <= needsIndent && !(item && indent >= needsIndent)) {
        needsIndent = -1;
      }
      String fromJob = current == null ? "" : current.name();
      if (keyValue.key() != null && keyValue.key().equals("needs")) {
        needsIndent = indent;
        needsJob = fromJob;
        for (String name : splitList(keyValue.value())) {
          refs.add(new Ref("needs", name, fromJob, lineNo));
        }
        continue;
      }
      if (needsIndent >= 0 && item && keyValue.key() == null && !rest.isEmpty()) {
        refs.add(new Ref("needs", unquote(rest), needsJob, lineNo));
        continue;
      }
      if (keyValue.key() != null && keyValue.key().equals("uses")) {
        String resolved = localPath(keyValue.value());
        if (resolved != null) {
          refs.add(new Ref("uses", resolved, fromJob, lineNo));
        }
      }
    }
    finish(current, jobs);
    return new ParsedWorkflow(unix, actionFile, callable, onStart, onEnd, jobs, refs);
  }

  private static Job finish(Job current, List<Job> jobs) {
    if (current != null) {
      jobs.add(current);
    }
    return null;
  }

  private static boolean isActionFile(String path) {
    String file = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    return file.equals("action.yml") || file.equals("action.yaml");
  }

  static String localPath(String uses) {
    String value = unquote(uses.trim());
    if (value.isEmpty()) {
      return null;
    }
    int space = value.indexOf(' ');
    if (space > 0) {
      value = value.substring(0, space);
    }
    if (!(value.startsWith("./") || value.startsWith("../"))) {
      return null;
    }
    int at = value.indexOf('@');
    if (at > 0) {
      value = value.substring(0, at);
    }
    String[] parts = value.split("/");
    List<String> stack = new ArrayList<>();
    for (String part : parts) {
      if (part.isEmpty() || part.equals(".")) {
        continue;
      }
      if (part.equals("..")) {
        if (stack.isEmpty()) {
          return null;
        }
        stack.remove(stack.size() - 1);
        continue;
      }
      stack.add(part);
    }
    if (stack.isEmpty()) {
      return null;
    }
    return String.join("/", stack);
  }

  private static List<String> splitList(String value) {
    List<String> names = new ArrayList<>();
    if (value == null || value.isBlank() || value.equals("|") || value.equals(">")) {
      return names;
    }
    String body = value.trim();
    if (body.startsWith("[") && body.endsWith("]")) {
      body = body.substring(1, body.length() - 1);
    }
    for (String part : body.split(",")) {
      String name = unquote(part.trim());
      if (!name.isEmpty()) {
        names.add(name);
      }
    }
    return names;
  }

  private static boolean mentionsWorkflowCall(String value) {
    return value != null && value.contains("workflow_call");
  }

  private static KeyValue keyValue(String rest) {
    boolean single = false;
    boolean doub = false;
    for (int i = 0; i < rest.length(); i++) {
      char c = rest.charAt(i);
      if (c == '\'' && !doub) {
        single = !single;
      } else if (c == '"' && !single) {
        doub = !doub;
      } else if (c == ':' && !single && !doub) {
        String key = rest.substring(0, i).trim();
        if (!isKey(key)) {
          return new KeyValue(null, rest);
        }
        return new KeyValue(key, rest.substring(i + 1).trim());
      }
    }
    return new KeyValue(null, rest);
  }

  private static boolean isKey(String key) {
    if (key.isEmpty()) {
      return false;
    }
    for (int i = 0; i < key.length(); i++) {
      char c = key.charAt(i);
      if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-')) {
        return false;
      }
    }
    return true;
  }

  private static String stripComment(String line) {
    boolean single = false;
    boolean doub = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '\'' && !doub) {
        single = !single;
      } else if (c == '"' && !single) {
        doub = !doub;
      } else if (c == '\\' && doub && i + 1 < line.length()) {
        i++;
      } else if (c == '#' && !single && !doub) {
        line = line.substring(0, i);
        break;
      }
    }
    if (single || doub) {
      throw new IllegalArgumentException("unclosed quote");
    }
    return line;
  }

  private static String unquote(String value) {
    if (value.length() >= 2) {
      char first = value.charAt(0);
      char last = value.charAt(value.length() - 1);
      if ((first == '"' || first == '\'') && last == first) {
        return value.substring(1, value.length() - 1);
      }
    }
    return value;
  }

  private static int indentOf(String line) {
    int indent = 0;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == ' ') {
        indent++;
      } else if (c == '\t') {
        indent += 2;
      } else {
        break;
      }
    }
    return indent;
  }

  private static int contentStart(String line) {
    int i = 0;
    while (i < line.length()) {
      char c = line.charAt(i);
      if (c != ' ' && c != '\t') {
        break;
      }
      i++;
    }
    return i;
  }

  private record KeyValue(String key, String value) {}
}
