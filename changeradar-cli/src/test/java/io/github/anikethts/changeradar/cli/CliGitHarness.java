package io.github.anikethts.changeradar.cli;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

abstract class CliGitHarness {

  @TempDir Path tmp;

  static CommandLine command() {
    CommandLine cmd = new CommandLine(new ChangeRadarApp());
    cmd.setExecutionExceptionHandler(new ChangeRadarApp.FailureExceptionHandler());
    StringWriter err = new StringWriter();
    cmd.setErr(new PrintWriter(err, true));
    cmd.setOut(new PrintWriter(new StringWriter(), true));
    return cmd;
  }

  void seedCallerRepo() throws Exception {
    try (Git git = Git.init().setDirectory(tmp.toFile()).call()) {
      Path demo = tmp.resolve("src/main/java/demo/Demo.java");
      Path caller = tmp.resolve("src/main/java/demo/Caller.java");
      Files.createDirectories(demo.getParent());
      Files.writeString(
          demo,
          """
          package demo;
          public class Demo {
            public void n() {}
          }
          """);
      Files.writeString(
          caller,
          """
          package demo;
          public class Caller {
            void run() {
              Demo d = new Demo();
              d.n();
            }
          }
          """);
      git.add().addFilepattern(".").call();
      commit(git, "base");
      Files.writeString(
          demo,
          """
          package demo;
          public class Demo {
            public void n() {
              int x = 1;
            }
          }
          """);
      git.add().addFilepattern(".").call();
      commit(git, "head");
    }
  }

  void seedRepo() throws Exception {
    try (Git git = Git.init().setDirectory(tmp.toFile()).call()) {
      Path src = tmp.resolve("src/Demo.java");
      Files.createDirectories(src.getParent());
      Files.writeString(src, "class Demo { void n() {} }\n");
      git.add().addFilepattern(".").call();
      commit(git, "base");
      Files.writeString(src, "class Demo { void n() { int x = 1; } }\n");
      git.add().addFilepattern(".").call();
      commit(git, "head");
    }
  }

  void seedPublicMove() throws Exception {
    try (Git git = Git.init().setDirectory(tmp.toFile()).call()) {
      Path src = tmp.resolve("src/Api.java");
      Files.createDirectories(src.getParent());
      Files.writeString(
          src,
          """
          public class Api {
            public void n() {}
          }
          """);
      git.add().addFilepattern(".").call();
      commit(git, "base");
      Path dest = tmp.resolve("src/moved/Api.java");
      Files.createDirectories(dest.getParent());
      Files.writeString(
          dest,
          """
          public class Api {
            public void n() {}
          }
          """);
      git.rm().addFilepattern("src/Api.java").call();
      git.add().addFilepattern("src/moved").call();
      commit(git, "head");
    }
  }

  void seedPublicSignatureChange() throws Exception {
    try (Git git = Git.init().setDirectory(tmp.toFile()).call()) {
      Path src = tmp.resolve("src/Api.java");
      Files.createDirectories(src.getParent());
      Files.writeString(
          src,
          """
          public class Api {
            public void n() {}
          }
          """);
      git.add().addFilepattern(".").call();
      commit(git, "base");
      Files.writeString(
          src,
          """
          public class Api {
            public void n(int x) {}
          }
          """);
      git.add().addFilepattern(".").call();
      commit(git, "head");
    }
  }

  void seedJavaFormatOnly() throws Exception {
    try (Git git = Git.init().setDirectory(tmp.toFile()).call()) {
      Path src = tmp.resolve("src/Api.java");
      Files.createDirectories(src.getParent());
      Files.writeString(
          src,
          """
          public class Api {
            public void n() {}
          }
          """);
      git.add().addFilepattern(".").call();
      commit(git, "base");
      Files.writeString(
          src,
          """
          public class Api {
              public void n() {  }
          }
          """);
      git.add().addFilepattern(".").call();
      commit(git, "head");
    }
  }

  void seedPublicReturnTypeChange() throws Exception {
    try (Git git = Git.init().setDirectory(tmp.toFile()).call()) {
      Path src = tmp.resolve("src/Api.java");
      Files.createDirectories(src.getParent());
      Files.writeString(
          src,
          """
          public class Api {
            public int n() { return 1; }
          }
          """);
      git.add().addFilepattern(".").call();
      commit(git, "base");
      Files.writeString(
          src,
          """
          public class Api {
            public String n() { return "1"; }
          }
          """);
      git.add().addFilepattern(".").call();
      commit(git, "head");
    }
  }

  void seedPublicThrowsChange() throws Exception {
    try (Git git = Git.init().setDirectory(tmp.toFile()).call()) {
      Path src = tmp.resolve("src/Api.java");
      Files.createDirectories(src.getParent());
      Files.writeString(
          src,
          """
          public class Api {
            public void n() {}
          }
          """);
      git.add().addFilepattern(".").call();
      commit(git, "base");
      Files.writeString(
          src,
          """
          public class Api {
            public void n() throws java.io.IOException {}
          }
          """);
      git.add().addFilepattern(".").call();
      commit(git, "head");
    }
  }

  void seedPublicFieldTypeChange() throws Exception {
    try (Git git = Git.init().setDirectory(tmp.toFile()).call()) {
      Path src = tmp.resolve("src/Api.java");
      Files.createDirectories(src.getParent());
      Files.writeString(
          src,
          """
          public class Api {
            public int x;
          }
          """);
      git.add().addFilepattern(".").call();
      commit(git, "base");
      Files.writeString(
          src,
          """
          public class Api {
            public String x;
          }
          """);
      git.add().addFilepattern(".").call();
      commit(git, "head");
    }
  }

  void seedPublicOverloadRemoval() throws Exception {
    try (Git git = Git.init().setDirectory(tmp.toFile()).call()) {
      Path src = tmp.resolve("src/Api.java");
      Files.createDirectories(src.getParent());
      Files.writeString(
          src,
          """
          public class Api {
            public void n() {}
            public void n(int x) {}
          }
          """);
      git.add().addFilepattern(".").call();
      commit(git, "base");
      Files.writeString(
          src,
          """
          public class Api {
            public void n() {}
          }
          """);
      git.add().addFilepattern(".").call();
      commit(git, "head");
    }
  }

  void seedPublicRemoval() throws Exception {
    try (Git git = Git.init().setDirectory(tmp.toFile()).call()) {
      Path src = tmp.resolve("src/Api.java");
      Files.createDirectories(src.getParent());
      Files.writeString(
          src,
          """
          public class Api {
            public void gone() {}
            public void stays() {}
          }
          """);
      git.add().addFilepattern(".").call();
      commit(git, "base");
      Files.writeString(
          src,
          """
          public class Api {
            public void stays() {}
          }
          """);
      git.add().addFilepattern(".").call();
      commit(git, "head");
    }
  }

  void seedKotlinRepo() throws Exception {
    try (Git git = Git.init().setDirectory(tmp.toFile()).call()) {
      Path src = tmp.resolve("api.kt");
      Files.writeString(src, "fun n(): Int { return 1 }\n");
      git.add().addFilepattern(".").call();
      commit(git, "base");
      Files.writeString(src, "fun n(): Int { return 2 }\n");
      git.add().addFilepattern(".").call();
      commit(git, "head");
    }
  }

  void seedKotlinRemoval() throws Exception {
    try (Git git = Git.init().setDirectory(tmp.toFile()).call()) {
      Path src = tmp.resolve("api.kt");
      Files.writeString(src, "fun gone() {}\nfun stays() {}\n");
      git.add().addFilepattern(".").call();
      commit(git, "base");
      Files.writeString(src, "fun stays() {}\n");
      git.add().addFilepattern(".").call();
      commit(git, "head");
    }
  }

  void seedTypeScriptFormatOnly() throws Exception {
    try (Git git = Git.init().setDirectory(tmp.toFile()).call()) {
      Path src = tmp.resolve("src/api.ts");
      Files.createDirectories(src.getParent());
      Files.writeString(src, "export function n(): number { return 1; }\n");
      git.add().addFilepattern(".").call();
      commit(git, "base");
      Files.writeString(src, "export function n(): number {  return 1; }\n");
      git.add().addFilepattern(".").call();
      commit(git, "head");
    }
  }

  void seedTypeScriptRepo() throws Exception {
    try (Git git = Git.init().setDirectory(tmp.toFile()).call()) {
      Path src = tmp.resolve("src/api.ts");
      Files.createDirectories(src.getParent());
      Files.writeString(src, "export function n(): number { return 1; }\n");
      git.add().addFilepattern(".").call();
      commit(git, "base");
      Files.writeString(src, "export function n(): number { return 2; }\n");
      git.add().addFilepattern(".").call();
      commit(git, "head");
    }
  }

  void seedTypeScriptRemoval() throws Exception {
    try (Git git = Git.init().setDirectory(tmp.toFile()).call()) {
      Path src = tmp.resolve("src/api.ts");
      Files.createDirectories(src.getParent());
      Files.writeString(src, "export function gone() {}\nexport function stays() {}\n");
      git.add().addFilepattern(".").call();
      commit(git, "base");
      Files.writeString(src, "export function stays() {}\n");
      git.add().addFilepattern(".").call();
      commit(git, "head");
    }
  }

  void seedPythonRepo() throws Exception {
    try (Git git = Git.init().setDirectory(tmp.toFile()).call()) {
      Path src = tmp.resolve("api.py");
      Files.writeString(src, "def n():\n    return 1\n");
      git.add().addFilepattern(".").call();
      commit(git, "base");
      Files.writeString(src, "def n():\n    return 2\n");
      git.add().addFilepattern(".").call();
      commit(git, "head");
    }
  }

  void seedPythonRemoval() throws Exception {
    try (Git git = Git.init().setDirectory(tmp.toFile()).call()) {
      Path src = tmp.resolve("api.py");
      Files.writeString(src, "def gone():\n    pass\n\ndef stays():\n    pass\n");
      git.add().addFilepattern(".").call();
      commit(git, "base");
      Files.writeString(src, "def stays():\n    pass\n");
      git.add().addFilepattern(".").call();
      commit(git, "head");
    }
  }

  void seedPhpRepo() throws Exception {
    try (Git git = Git.init().setDirectory(tmp.toFile()).call()) {
      Path src = tmp.resolve("api.php");
      Files.writeString(src, "<?php\nfunction n() { return 1; }\n");
      git.add().addFilepattern(".").call();
      commit(git, "base");
      Files.writeString(src, "<?php\nfunction n() { return 2; }\n");
      git.add().addFilepattern(".").call();
      commit(git, "head");
    }
  }

  void seedPhpRemoval() throws Exception {
    try (Git git = Git.init().setDirectory(tmp.toFile()).call()) {
      Path src = tmp.resolve("api.php");
      Files.writeString(src, "<?php\nfunction gone() {}\nfunction stays() {}\n");
      git.add().addFilepattern(".").call();
      commit(git, "base");
      Files.writeString(src, "<?php\nfunction stays() {}\n");
      git.add().addFilepattern(".").call();
      commit(git, "head");
    }
  }

  void seedUnparseableJava() throws Exception {
    try (Git git = Git.init().setDirectory(tmp.toFile()).call()) {
      Path src = tmp.resolve("src/Broken.java");
      Files.createDirectories(src.getParent());
      Files.writeString(src, "public class Broken {}\n");
      git.add().addFilepattern(".").call();
      commit(git, "base");
      Files.writeString(src, "public class Broken { this is not java\n");
      git.add().addFilepattern(".").call();
      commit(git, "head");
    }
  }

  static void commit(Git git, String message) throws Exception {
    git.commit()
        .setAuthor("ChangeRadar Test", "test@changeradar.example")
        .setCommitter("ChangeRadar Test", "test@changeradar.example")
        .setSign(false)
        .setMessage(message)
        .call();
  }
}
