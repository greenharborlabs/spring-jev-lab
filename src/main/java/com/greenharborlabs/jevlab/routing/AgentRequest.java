package com.greenharborlabs.jevlab.routing;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** An incoming API request from an agent, as the router sees it. */
public record AgentRequest(
    String method,
    String path,
    String agentId,
    String purpose,
    Map<String, String> headers,
    String body) {

  /** Only these headers reach Jev; credentials, macaroons, invoices and cookies never do. */
  static final Set<String> SHARED_HEADERS = Set.of("accept", "content-type", "user-agent");

  static final int BODY_EXCERPT_LIMIT = 2048;

  public AgentRequest {
    if (method == null || method.isBlank() || path == null || path.isBlank()) {
      throw new IllegalArgumentException("method and path are required");
    }
    headers = headers == null ? Map.of() : Map.copyOf(headers);
  }

  /** The state sent to Jev: request metadata and a bounded body excerpt, nothing else. */
  Map<String, Object> toJevState() {
    Map<String, String> shared = new LinkedHashMap<>();
    headers.forEach(
        (name, value) -> {
          if (SHARED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
            shared.put(name.toLowerCase(Locale.ROOT), value);
          }
        });
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("method", method.toUpperCase(Locale.ROOT));
    state.put("path", path);
    state.put("agent_id", agentId == null ? "" : agentId);
    state.put("declared_purpose", purpose == null ? "" : purpose);
    state.put("headers", shared);
    state.put("body_excerpt", excerpt(body));
    return state;
  }

  private static String excerpt(String body) {
    if (body == null) {
      return "";
    }
    return body.length() <= BODY_EXCERPT_LIMIT ? body : body.substring(0, BODY_EXCERPT_LIMIT);
  }
}
