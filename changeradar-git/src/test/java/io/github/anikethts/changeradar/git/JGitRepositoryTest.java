package io.github.anikethts.changeradar.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.anikethts.changeradar.core.ChangeType;
import io.github.anikethts.changeradar.core.ChangedFile;
import io.github.anikethts.changeradar.core.GitAccessException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JGitRepositoryTest {

  @TempDir Path tmp;

  @Test
  void diffsAddedAndModifiedJavaFiles() throws Exception {
    try (Git git = Git.init().setDirectory(tmp.toFile()).call()) {
      Path src = tmp.resolve("src/Demo.java");
      Files.createDirectories(src.getParent());
      Files.writeString(src, "class Demo {}\n");
      git.add().addFilepattern(".").call();
      commit(git, "base");

      Files.writeString(src, "class Demo { void n() {} }\n");
      Files.writeString(tmp.resolve("src/Other.java"), "class Other {}\n");
      git.add().addFilepattern(".").call();
      commit(git, "head");
    }

    try (JGitRepository repo = JGitRepository.open(tmp)) {
      List<ChangedFile> changed = repo.diff("HEAD~1", "HEAD");
      assertEquals(2, changed.size());
      assertEquals("src/Demo.java", changed.get(0).path());
      assertEquals(ChangeType.MODIFY, changed.get(0).changeType());
      assertEquals("src/Other.java", changed.get(1).path());
      assertEquals(ChangeType.ADD, changed.get(1).changeType());
      assertTrue(
          changed.get(0).headLineRanges().stream().anyMatch(range -> range.startLine() >= 1));
      assertEquals(
          "class Demo { void n() {} }\n", repo.readFile("HEAD", "src/Demo.java").orElseThrow());
      assertTrue(repo.listPaths("HEAD", ".java").contains("src/Demo.java"));
      Map<String, String> sources = repo.readSources("HEAD", path -> path.endsWith(".java"));
      assertEquals(List.of("src/Demo.java", "src/Other.java"), List.copyOf(sources.keySet()));
      assertEquals("class Demo { void n() {} }\n", sources.get("src/Demo.java"));
      assertTrue(repo.resolve("HEAD").matches("[0-9a-f]{40,64}"));
    }
  }

  @Test
  void unknownRefFails() throws Exception {
    try (Git git = Git.init().setDirectory(tmp.toFile()).call()) {
      Files.writeString(tmp.resolve("README"), "x\n");
      git.add().addFilepattern(".").call();
      commit(git, "init");
    }
    try (JGitRepository repo = JGitRepository.open(tmp)) {
      GitAccessException ex =
          assertThrows(GitAccessException.class, () -> repo.resolve("does-not-exist"));
      assertTrue(ex.getMessage().contains("does-not-exist"));
    }
  }

  @Test
  void missingRepositoryFails() {
    assertThrows(GitAccessException.class, () -> JGitRepository.open(tmp.resolve("missing")));
  }

  private static void commit(Git git, String message) throws Exception {
    git.commit()
        .setAuthor("ChangeRadar Test", "test@changeradar.example")
        .setCommitter("ChangeRadar Test", "test@changeradar.example")
        .setSign(false)
        .setMessage(message)
        .call();
  }
}
