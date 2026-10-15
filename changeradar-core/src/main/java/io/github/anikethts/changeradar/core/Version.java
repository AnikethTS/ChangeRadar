package io.github.anikethts.changeradar.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class Version {

  private Version() {}

  public static String current() {
    try (InputStream in = Version.class.getClassLoader().getResourceAsStream("VERSION")) {
      if (in == null) {
        return "unknown";
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
    } catch (IOException e) {
      return "unknown";
    }
  }
}
