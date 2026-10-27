package io.github.anikethts.changeradar.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewServerTest {

  @TempDir Path tmp;

  @Test
  void servesPageAndKeepsSourceInsideTheRepo() throws Exception {
    Path repo = tmp.resolve("repo");
    Files.createDirectories(repo.resolve("src"));
    Files.writeString(repo.resolve("src/Lib.java"), "alpha\nbeta-line\n");
    Files.writeString(tmp.resolve("secret.txt"), "outside-secret\n");
    Path html = repo.resolve("report.html");
    Files.writeString(html, "<!DOCTYPE html><title>ChangeRadar review</title>");
    HttpClient client = HttpClient.newHttpClient();
    try (ReviewServer server = ReviewServer.start(repo, html, 0)) {
      String root = "http://127.0.0.1:" + server.port();
      HttpResponse<String> page =
          client.send(
              HttpRequest.newBuilder(URI.create(root + "/")).build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, page.statusCode());
      assertTrue(page.body().contains("ChangeRadar review"));

      HttpResponse<String> source =
          client.send(
              HttpRequest.newBuilder(URI.create(root + "/source?path=src/Lib.java&line=2")).build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, source.statusCode());
      assertTrue(source.body().contains("beta-line"));

      HttpResponse<String> parent =
          client.send(request(root, "../secret.txt"), HttpResponse.BodyHandlers.ofString());
      assertEquals(400, parent.statusCode());
      assertFalse(parent.body().contains("outside-secret"));

      HttpResponse<String> absolute =
          client.send(
              request(root, tmp.resolve("secret.txt").toAbsolutePath().toString()),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(400, absolute.statusCode());
      assertFalse(absolute.body().contains("outside-secret"));
    }
  }

  private static HttpRequest request(String root, String path) {
    String encoded = URLEncoder.encode(path, StandardCharsets.UTF_8);
    return HttpRequest.newBuilder(URI.create(root + "/source?path=" + encoded + "&line=1")).build();
  }
}
