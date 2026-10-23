package io.github.anikethts.changeradar.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Compares source as tokens, skipping comments and layout whitespace. Import reorder and identifier
 * edits are not equal. Python indent/dedent is kept so block structure is not ignored.
 */
public final class FormatTrivia {

  public enum Dialect {
    C_LIKE,
    PHP,
    PYTHON,
    KOTLIN
  }

  private FormatTrivia() {}

  public static boolean sameIgnoringTrivia(Dialect dialect, String base, String head) {
    if (base == null || head == null) {
      return false;
    }
    List<String> left = tokens(dialect, base);
    List<String> right = tokens(dialect, head);
    return left != null && left.equals(right);
  }

  static List<String> tokens(Dialect dialect, String source) {
    try {
      return dialect == Dialect.PYTHON ? pythonTokens(source) : cLikeTokens(dialect, source);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private static List<String> cLikeTokens(Dialect dialect, String source) {
    List<String> out = new ArrayList<>();
    int i = 0;
    int n = source.length();
    while (i < n) {
      char c = source.charAt(i);
      if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
        i++;
        continue;
      }
      char next = i + 1 < n ? source.charAt(i + 1) : '\0';
      if (c == '/' && next == '/') {
        i = skipLine(source, i + 2);
        continue;
      }
      if (dialect == Dialect.PHP && c == '#' && next != '[') {
        i = skipLine(source, i + 1);
        continue;
      }
      if (c == '/' && next == '*') {
        i = skipBlockComment(source, i + 2);
        continue;
      }
      if (dialect == Dialect.KOTLIN
          && c == '"'
          && next == '"'
          && i + 2 < n
          && source.charAt(i + 2) == '"') {
        i = skipRawString(source, i + 3, out);
        continue;
      }
      if (c == '\'' || c == '"' || (dialect == Dialect.C_LIKE && c == '`')) {
        i = skipQuoted(source, i, c, out);
        continue;
      }
      if (isIdentStart(c)) {
        int start = i;
        i++;
        while (i < n && isIdentPart(source.charAt(i))) {
          i++;
        }
        out.add(source.substring(start, i));
        continue;
      }
      out.add(String.valueOf(c));
      i++;
    }
    return out;
  }

  private static List<String> pythonTokens(String source) {
    List<String> out = new ArrayList<>();
    List<Integer> indents = new ArrayList<>();
    indents.add(0);
    int i = 0;
    int n = source.length();
    while (i < n) {
      int indent = 0;
      while (i < n) {
        char c = source.charAt(i);
        if (c == ' ') {
          indent++;
          i++;
        } else if (c == '\t') {
          indent += 8;
          i++;
        } else {
          break;
        }
      }
      if (i >= n) {
        break;
      }
      char c = source.charAt(i);
      if (c == '\n' || c == '\r') {
        i++;
        continue;
      }
      if (c == '#') {
        i = skipLine(source, i + 1);
        continue;
      }
      int top = indents.get(indents.size() - 1);
      if (indent > top) {
        indents.add(indent);
        out.add("INDENT");
      } else {
        while (indent < indents.get(indents.size() - 1)) {
          indents.remove(indents.size() - 1);
          out.add("DEDENT");
        }
        if (indent != indents.get(indents.size() - 1)) {
          throw new IllegalArgumentException("inconsistent indent");
        }
      }
      while (i < n) {
        c = source.charAt(i);
        if (c == '\n' || c == '\r') {
          i++;
          break;
        }
        if (c == ' ' || c == '\t') {
          i++;
          continue;
        }
        if (c == '#') {
          i = skipLine(source, i + 1);
          break;
        }
        char next = i + 1 < n ? source.charAt(i + 1) : '\0';
        char third = i + 2 < n ? source.charAt(i + 2) : '\0';
        if ((c == '\'' || c == '"') && next == c && third == c) {
          i = skipTriple(source, i + 3, c, out);
          continue;
        }
        if (c == '\'' || c == '"') {
          i = skipQuoted(source, i, c, out);
          continue;
        }
        if (isIdentStart(c)) {
          int start = i;
          i++;
          while (i < n && isIdentPart(source.charAt(i))) {
            i++;
          }
          out.add(source.substring(start, i));
          continue;
        }
        out.add(String.valueOf(c));
        i++;
      }
    }
    while (indents.size() > 1) {
      indents.remove(indents.size() - 1);
      out.add("DEDENT");
    }
    return out;
  }

  private static int skipLine(String source, int i) {
    while (i < source.length() && source.charAt(i) != '\n') {
      i++;
    }
    return i;
  }

  private static int skipBlockComment(String source, int i) {
    while (i + 1 < source.length()) {
      if (source.charAt(i) == '*' && source.charAt(i + 1) == '/') {
        return i + 2;
      }
      i++;
    }
    throw new IllegalArgumentException("unclosed block comment");
  }

  private static int skipQuoted(String source, int i, char quote, List<String> out) {
    int start = i;
    i++;
    while (i < source.length()) {
      char c = source.charAt(i);
      if (c == '\\') {
        i += 2;
        continue;
      }
      if (c == quote) {
        out.add(source.substring(start, i + 1));
        return i + 1;
      }
      i++;
    }
    throw new IllegalArgumentException("unclosed string");
  }

  private static int skipRawString(String source, int i, List<String> out) {
    int start = i - 3;
    while (i + 2 < source.length()) {
      if (source.charAt(i) == '"' && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"') {
        out.add(source.substring(start, i + 3));
        return i + 3;
      }
      i++;
    }
    throw new IllegalArgumentException("unclosed raw string");
  }

  private static int skipTriple(String source, int i, char quote, List<String> out) {
    int start = i - 3;
    while (i + 2 < source.length()) {
      if (source.charAt(i) == quote
          && source.charAt(i + 1) == quote
          && source.charAt(i + 2) == quote) {
        out.add(source.substring(start, i + 3));
        return i + 3;
      }
      i++;
    }
    throw new IllegalArgumentException("unclosed triple string");
  }

  private static boolean isIdentStart(char c) {
    return Character.isLetter(c) || c == '_' || c == '$';
  }

  private static boolean isIdentPart(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '$';
  }
}
