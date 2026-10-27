package io.github.anikethts.changeradar.cli;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Serves a review page on loopback and a short source slice from the repository. */
final class ReviewServer implements AutoCloseable {

  static final int WINDOW = 40;

  private final HttpServer server;
  private final ExecutorService executor;
  private final AtomicBoolean closed = new AtomicBoolean();

  private ReviewServer(HttpServer server, ExecutorService executor) {
    this.server = server;
    this.executor = executor;
  }

  static ReviewServer start(Path repo, Path reportHtml, int port) throws IOException {
    Path root = repo.toAbsolutePath().normalize();
    byte[] page = Files.readAllBytes(reportHtml);
    HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
    ExecutorService pool = Executors.newFixedThreadPool(4);
    http.setExecutor(pool);
    http.createContext("/", exchange -> handle(exchange, root, page));
    http.start();
    return new ReviewServer(http, pool);
  }

  int port() {
    return server.getAddress().getPort();
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    server.stop(0);
    executor.shutdownNow();
  }

  private static void handle(HttpExchange exchange, Path root, byte[] page) throws IOException {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        send(exchange, 405, "text/plain; charset=utf-8", "Method not allowed\n");
        return;
      }
      String path = exchange.getRequestURI().getPath();
      if ("/".equals(path) || "/index.html".equals(path)) {
        send(exchange, 200, "text/html; charset=utf-8", page);
        return;
      }
      if ("/source".equals(path)) {
        serveSource(exchange, root);
        return;
      }
      send(exchange, 404, "text/plain; charset=utf-8", "Not found\n");
    } catch (RuntimeException ex) {
      send(exchange, 500, "text/plain; charset=utf-8", "Review server error\n");
    }
  }

  private static void serveSource(HttpExchange exchange, Path root) throws IOException {
    String rawPath = query(exchange, "path");
    int line = parseLine(query(exchange, "line"));
    Path file;
    try {
      file = resolveInside(root, rawPath);
    } catch (IllegalArgumentException ex) {
      send(exchange, 400, "text/plain; charset=utf-8", "Path is outside the repository\n");
      return;
    }
    if (!Files.isRegularFile(file)) {
      send(exchange, 404, "text/plain; charset=utf-8", "File not found\n");
      return;
    }
    if (Files.size(file) > 1_000_000) {
      send(exchange, 413, "text/plain; charset=utf-8", "File is too large to preview\n");
      return;
    }
    List<String> lines = Files.readAllLines(file);
    send(exchange, 200, "text/plain; charset=utf-8", window(lines, line));
  }

  static Path resolveInside(Path root, String rawPath) throws IOException {
    if (rawPath == null || rawPath.isBlank()) {
      throw new IllegalArgumentException("missing path");
    }
    String unix = rawPath.replace('\\', '/');
    if (unix.startsWith("/") || unix.matches("^[A-Za-z]:.*")) {
      throw new IllegalArgumentException("absolute path");
    }
    for (String segment : unix.split("/", -1)) {
      if (segment.isEmpty() || ".".equals(segment)) {
        continue;
      }
      if ("..".equals(segment) || ".git".equals(segment)) {
        throw new IllegalArgumentException("rejected segment");
      }
    }
    Path candidate = root.resolve(unix).normalize();
    if (!candidate.startsWith(root)) {
      throw new IllegalArgumentException("outside repository");
    }
    if (!Files.exists(candidate)) {
      return candidate;
    }
    Path realRoot = root.toRealPath();
    Path real = candidate.toRealPath();
    if (!real.startsWith(realRoot) || real.startsWith(realRoot.resolve(".git"))) {
      throw new IllegalArgumentException("outside repository");
    }
    return real;
  }

  static String window(List<String> lines, int line) {
    if (lines.isEmpty()) {
      return "";
    }
    int center = Math.min(Math.max(line, 1), lines.size());
    int start = Math.max(1, center - (WINDOW / 2));
    int end = Math.min(lines.size(), start + WINDOW - 1);
    start = Math.max(1, end - WINDOW + 1);
    StringBuilder body = new StringBuilder();
    for (int number = start; number <= end; number++) {
      body.append(number).append("| ").append(lines.get(number - 1)).append('\n');
    }
    return body.toString();
  }

  private static int parseLine(String raw) {
    if (raw == null || raw.isBlank()) {
      return 1;
    }
    try {
      return Integer.parseInt(raw.trim());
    } catch (NumberFormatException ex) {
      return 1;
    }
  }

  private static String query(HttpExchange exchange, String name) {
    String query = exchange.getRequestURI().getRawQuery();
    if (query == null) {
      return "";
    }
    for (String part : query.split("&")) {
      int eq = part.indexOf('=');
      String key = eq < 0 ? part : part.substring(0, eq);
      String value = eq < 0 ? "" : part.substring(eq + 1);
      if (name.equals(URLDecoder.decode(key, StandardCharsets.UTF_8))) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
      }
    }
    return "";
  }

  private static void send(HttpExchange exchange, int status, String type, String body)
      throws IOException {
    send(exchange, status, type, body.getBytes(StandardCharsets.UTF_8));
  }

  private static void send(HttpExchange exchange, int status, String type, byte[] body)
      throws IOException {
    exchange.getResponseHeaders().set("Content-Type", type);
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(status, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }
}
