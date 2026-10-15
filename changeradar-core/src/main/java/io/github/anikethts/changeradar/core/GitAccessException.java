package io.github.anikethts.changeradar.core;

public final class GitAccessException extends RuntimeException {

  public GitAccessException(String message) {
    super(message);
  }

  public GitAccessException(String message, Throwable cause) {
    super(message, cause);
  }
}
