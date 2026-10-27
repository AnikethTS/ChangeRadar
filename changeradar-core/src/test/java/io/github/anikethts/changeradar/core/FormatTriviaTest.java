package io.github.anikethts.changeradar.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FormatTriviaTest {

  @Test
  void ecmaIgnoresCommentsAndSpaces() {
    assertTrue(
        FormatTrivia.sameIgnoringTrivia(
            FormatTrivia.Dialect.C_LIKE,
            "export function n() { return 1; }",
            "export function n() {  /* pad */ return 1; }"));
  }

  @Test
  void ecmaTokenChangeIsNotFormatOnly() {
    assertFalse(
        FormatTrivia.sameIgnoringTrivia(
            FormatTrivia.Dialect.C_LIKE,
            "export function n() { return 1; }",
            "export function n() { return 2; }"));
  }

  @Test
  void pythonKeepsBlockStructure() {
    assertTrue(
        FormatTrivia.sameIgnoringTrivia(
            FormatTrivia.Dialect.PYTHON,
            "def n():\n    return 1\n",
            "def n():\n    return  1  # pad\n"));
    assertFalse(
        FormatTrivia.sameIgnoringTrivia(
            FormatTrivia.Dialect.PYTHON, "if a:\n    b()\n    c()\n", "if a:\n    b()\nc()\n"));
  }

  @Test
  void phpIgnoresHashComments() {
    assertTrue(
        FormatTrivia.sameIgnoringTrivia(
            FormatTrivia.Dialect.PHP,
            "<?php function n() { return 1; }",
            "<?php function n() { return 1; } # pad"));
  }

  @Test
  void sqlIgnoresCommentsAndKeepsStatementEdits() {
    assertTrue(
        FormatTrivia.sameIgnoringTrivia(
            FormatTrivia.Dialect.SQL,
            "create table accounts (id int);",
            "create table accounts (id int); -- pad\n/* block */"));
    assertFalse(
        FormatTrivia.sameIgnoringTrivia(
            FormatTrivia.Dialect.SQL,
            "create table accounts (id int);",
            "create table accounts (id text);"));
  }

  @Test
  void yamlIgnoresCommentsAndKeepsKeyEdits() {
    assertTrue(
        FormatTrivia.sameIgnoringTrivia(
            FormatTrivia.Dialect.YAML,
            "jobs:\n  build:\n    runs-on: ubuntu-latest\n",
            "jobs:\n  build:\n    runs-on: ubuntu-latest # pin\n"));
    assertFalse(
        FormatTrivia.sameIgnoringTrivia(
            FormatTrivia.Dialect.YAML,
            "jobs:\n  build:\n    runs-on: ubuntu-latest\n",
            "jobs:\n  build:\n    runs-on: ubuntu-24.04\n"));
  }

  @Test
  void shellIgnoresCommentsAndKeepsCommandEdits() {
    assertTrue(
        FormatTrivia.sameIgnoringTrivia(
            FormatTrivia.Dialect.SHELL,
            "build() {\n  echo old\n}\n",
            "build() {\n  echo old\n}\n# note\n"));
    assertFalse(
        FormatTrivia.sameIgnoringTrivia(
            FormatTrivia.Dialect.SHELL,
            "build() {\n  echo old\n}\n",
            "build() {\n  echo new\n}\n"));
  }
}
