package io.github.anikethts.changeradar.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;

/**
 * Walks resolved call edges from changed symbols. Depth 1 is DIRECT; deeper hops are INDIRECT. Caps
 * depth and count so a dense graph cannot explode the report.
 */
public final class CallChains {

  public static final int MAX_DEPTH = 3;
  public static final int MAX_INDIRECT = 40;

  private CallChains() {}

  public record Edge(String calleeId, String callerId, SourceLocation location) {
    public Edge {
      Objects.requireNonNull(calleeId, "calleeId");
      Objects.requireNonNull(callerId, "callerId");
      Objects.requireNonNull(location, "location");
    }
  }

  public record Expansion(List<ImpactFinding> findings, boolean capped) {
    public Expansion {
      Objects.requireNonNull(findings, "findings");
      findings = List.copyOf(findings);
    }
  }

  public static Expansion fromResolved(Set<String> seeds, List<Edge> edges) {
    Objects.requireNonNull(seeds, "seeds");
    Objects.requireNonNull(edges, "edges");
    Map<String, List<Edge>> byCallee = new LinkedHashMap<>();
    for (Edge edge : edges) {
      byCallee.computeIfAbsent(edge.calleeId(), key -> new ArrayList<>()).add(edge);
    }

    record Step(String id, int depth, String path) {}

    Queue<Step> queue = new ArrayDeque<>();
    Set<String> queued = new LinkedHashSet<>();
    for (String seed : seeds) {
      if (queued.add(seed)) {
        queue.add(new Step(seed, 0, seed));
      }
    }

    List<ImpactFinding> findings = new ArrayList<>();
    Set<String> seenSites = new LinkedHashSet<>();
    int indirect = 0;
    boolean capped = false;

    while (!queue.isEmpty()) {
      Step current = queue.remove();
      if (current.depth() >= MAX_DEPTH) {
        continue;
      }
      List<Edge> outgoing = byCallee.getOrDefault(current.id(), List.of());
      for (Edge edge : outgoing) {
        if (edge.callerId().equals(current.id()) && current.depth() == 0) {
          continue;
        }
        String site =
            edge.location().path()
                + ":"
                + edge.location().startLine()
                + ":"
                + edge.callerId()
                + "->"
                + edge.calleeId();
        if (!seenSites.add(site)) {
          continue;
        }
        int nextDepth = current.depth() + 1;
        String nextPath = edge.callerId() + " → " + current.path();
        Confidence confidence = nextDepth == 1 ? Confidence.DIRECT : Confidence.INDIRECT;
        if (confidence == Confidence.INDIRECT) {
          if (indirect >= MAX_INDIRECT) {
            capped = true;
            continue;
          }
          indirect++;
        }
        String summary =
            confidence == Confidence.DIRECT
                ? edge.callerId()
                    + " calls "
                    + edge.calleeId()
                    + " ("
                    + confidence.name().toLowerCase()
                    + ")"
                : edge.callerId() + " calls " + edge.calleeId() + " (indirect: " + nextPath + ")";
        findings.add(
            new ImpactFinding("CALLER", confidence, summary, edge.calleeId(), edge.location()));
        if (nextDepth < MAX_DEPTH && queued.add(edge.callerId())) {
          queue.add(new Step(edge.callerId(), nextDepth, nextPath));
        }
      }
    }
    return new Expansion(findings, capped);
  }
}
