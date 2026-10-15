package io.github.anikethts.changeradar.git;

import io.github.anikethts.changeradar.core.ChangeType;
import io.github.anikethts.changeradar.core.ChangedFile;
import io.github.anikethts.changeradar.core.GitAccessException;
import io.github.anikethts.changeradar.core.GitRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;

public final class JGitRepository implements GitRepository {

  private final Git git;

  private JGitRepository(Git git) {
    this.git = git;
  }

  public static JGitRepository open(Path root) {
    try {
      return new JGitRepository(Git.open(root.toFile()));
    } catch (IOException e) {
      throw new GitAccessException("Not a Git repository: " + root, e);
    }
  }

  @Override
  public Path root() {
    return git.getRepository().getWorkTree().toPath();
  }

  @Override
  public String resolve(String revision) {
    try {
      ObjectId id = git.getRepository().resolve(revision);
      if (id == null) {
        throw new GitAccessException("Cannot resolve Git revision: " + revision);
      }
      return id.name();
    } catch (IOException e) {
      throw new GitAccessException("Cannot resolve Git revision: " + revision, e);
    }
  }

  @Override
  public List<ChangedFile> diff(String base, String head) {
    Repository repo = git.getRepository();
    try (ObjectReader reader = repo.newObjectReader();
        RevWalk walk = new RevWalk(repo);
        DiffFormatter formatter = new DiffFormatter(new ByteArrayOutputStream())) {
      ObjectId baseId = requireResolved(base);
      ObjectId headId = requireResolved(head);
      CanonicalTreeParser baseTree = tree(reader, walk, baseId);
      CanonicalTreeParser headTree = tree(reader, walk, headId);
      formatter.setRepository(repo);
      List<ChangedFile> files = new ArrayList<>();
      for (DiffEntry entry : formatter.scan(baseTree, headTree)) {
        files.add(toChangedFile(entry));
      }
      files.sort(Comparator.comparing(ChangedFile::path));
      return List.copyOf(files);
    } catch (IOException e) {
      throw new GitAccessException("Cannot diff " + base + ".." + head, e);
    }
  }

  private ObjectId requireResolved(String revision) throws IOException {
    ObjectId id = git.getRepository().resolve(revision);
    if (id == null) {
      throw new GitAccessException("Cannot resolve Git revision: " + revision);
    }
    return id;
  }

  private static CanonicalTreeParser tree(ObjectReader reader, RevWalk walk, ObjectId id)
      throws IOException {
    CanonicalTreeParser parser = new CanonicalTreeParser();
    parser.reset(reader, walk.parseCommit(id).getTree());
    return parser;
  }

  private static ChangedFile toChangedFile(DiffEntry entry) {
    return switch (entry.getChangeType()) {
      case ADD, COPY -> new ChangedFile(entry.getNewPath(), ChangeType.ADD, null);
      case DELETE -> new ChangedFile(entry.getOldPath(), ChangeType.DELETE, null);
      case RENAME -> new ChangedFile(entry.getNewPath(), ChangeType.RENAME, entry.getOldPath());
      case MODIFY -> new ChangedFile(entry.getNewPath(), ChangeType.MODIFY, null);
    };
  }

  @Override
  public void close() {
    git.close();
  }
}
