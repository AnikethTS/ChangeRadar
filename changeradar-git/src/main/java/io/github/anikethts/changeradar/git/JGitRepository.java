package io.github.anikethts.changeradar.git;

import io.github.anikethts.changeradar.core.ChangeType;
import io.github.anikethts.changeradar.core.ChangedFile;
import io.github.anikethts.changeradar.core.GitAccessException;
import io.github.anikethts.changeradar.core.GitRepository;
import io.github.anikethts.changeradar.core.LineRange;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;

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
        files.add(toChangedFile(formatter, entry));
      }
      files.sort(Comparator.comparing(ChangedFile::path));
      return List.copyOf(files);
    } catch (IOException e) {
      throw new GitAccessException("Cannot diff " + base + ".." + head, e);
    }
  }

  @Override
  public List<String> listPaths(String revision, String suffix) {
    Repository repo = git.getRepository();
    try (RevWalk walk = new RevWalk(repo);
        TreeWalk treeWalk = new TreeWalk(repo)) {
      RevCommit commit = walk.parseCommit(requireResolved(revision));
      treeWalk.addTree(commit.getTree());
      treeWalk.setRecursive(true);
      List<String> paths = new ArrayList<>();
      while (treeWalk.next()) {
        String path = treeWalk.getPathString();
        if (path.endsWith(suffix)) {
          paths.add(path);
        }
      }
      paths.sort(String::compareTo);
      return List.copyOf(paths);
    } catch (IOException e) {
      throw new GitAccessException("Cannot list files at " + revision, e);
    }
  }

  @Override
  public Map<String, String> readSources(String revision, Predicate<String> include) {
    Repository repo = git.getRepository();
    try (RevWalk walk = new RevWalk(repo);
        TreeWalk treeWalk = new TreeWalk(repo);
        ObjectReader reader = repo.newObjectReader()) {
      RevCommit commit = walk.parseCommit(requireResolved(revision));
      treeWalk.addTree(commit.getTree());
      treeWalk.setRecursive(true);
      List<String> paths = new ArrayList<>();
      List<ObjectId> ids = new ArrayList<>();
      while (treeWalk.next()) {
        FileMode mode = treeWalk.getFileMode(0);
        if (mode == FileMode.TREE || mode == FileMode.GITLINK || mode == FileMode.MISSING) {
          continue;
        }
        String path = treeWalk.getPathString();
        if (!include.test(path)) {
          continue;
        }
        paths.add(path);
        ids.add(treeWalk.getObjectId(0));
      }
      Integer[] order = new Integer[paths.size()];
      for (int i = 0; i < order.length; i++) {
        order[i] = i;
      }
      Arrays.sort(order, Comparator.comparing(paths::get));
      Map<String, String> sources = new LinkedHashMap<>();
      for (int index : order) {
        ObjectLoader loader = reader.open(ids.get(index));
        sources.put(paths.get(index), new String(loader.getBytes(), StandardCharsets.UTF_8));
      }
      return sources;
    } catch (IOException e) {
      throw new GitAccessException("Cannot read sources at " + revision, e);
    }
  }

  @Override
  public Optional<String> readFile(String revision, String path) {
    Repository repo = git.getRepository();
    try (RevWalk walk = new RevWalk(repo);
        TreeWalk treeWalk =
            TreeWalk.forPath(repo, path, walk.parseCommit(requireResolved(revision)).getTree())) {
      if (treeWalk == null) {
        return Optional.empty();
      }
      ObjectLoader loader = repo.open(treeWalk.getObjectId(0));
      return Optional.of(new String(loader.getBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new GitAccessException("Cannot read " + path + " at " + revision, e);
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

  private static ChangedFile toChangedFile(DiffFormatter formatter, DiffEntry entry)
      throws IOException {
    List<LineRange> hunks = headHunks(formatter, entry);
    return switch (entry.getChangeType()) {
      case ADD, COPY -> new ChangedFile(entry.getNewPath(), ChangeType.ADD, null, hunks);
      case DELETE -> new ChangedFile(entry.getOldPath(), ChangeType.DELETE, null, List.of());
      case RENAME ->
          new ChangedFile(entry.getNewPath(), ChangeType.RENAME, entry.getOldPath(), hunks);
      case MODIFY -> new ChangedFile(entry.getNewPath(), ChangeType.MODIFY, null, hunks);
    };
  }

  private static List<LineRange> headHunks(DiffFormatter formatter, DiffEntry entry)
      throws IOException {
    if (entry.getChangeType() == DiffEntry.ChangeType.DELETE) {
      return List.of();
    }
    List<LineRange> ranges = new ArrayList<>();
    for (Edit edit : formatter.toFileHeader(entry).toEditList()) {
      if (edit.getType() == Edit.Type.DELETE) {
        continue;
      }
      int start = edit.getBeginB() + 1;
      int end = edit.getEndB();
      if (end >= start) {
        ranges.add(new LineRange(start, end));
      }
    }
    return List.copyOf(ranges);
  }

  @Override
  public void close() {
    git.close();
  }
}
