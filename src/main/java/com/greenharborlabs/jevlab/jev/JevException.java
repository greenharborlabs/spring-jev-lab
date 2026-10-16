package com.greenharborlabs.jevlab.jev;

/** Failures calling Jev. Messages never contain the API key. */
public sealed class JevException extends RuntimeException {

  private JevException(String message, Throwable cause) {
    super(message, cause);
  }

  /** No API key is configured; nothing was sent. */
  public static final class NotConfigured extends JevException {
    public NotConfigured() {
      super("TYPESAFE_API_KEY is not set", null);
    }
  }

  /** 401 or 403. */
  public static final class Authentication extends JevException {
    public Authentication(int status) {
      super("Jev rejected the API key (HTTP " + status + ")", null);
    }
  }

  /** 400, 413, 415 or 422: the request itself is wrong and retrying will not help. */
  public static final class Rejected extends JevException {
    public Rejected(int status, String detail) {
      super("Jev rejected the request (HTTP " + status + "): " + detail, null);
    }
  }

  /** Rate limited, overloaded, server error, timeout or connection failure. */
  public static final class Unavailable extends JevException {
    public Unavailable(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** The response did not match the documented contract. */
  public static final class Malformed extends JevException {
    public Malformed(String message) {
      super("Malformed Jev response: " + message, null);
    }
  }
}
