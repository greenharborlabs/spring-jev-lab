package com.greenharborlabs.jevlab.routing;

import com.greenharborlabs.jevlab.jev.JevResponse;
import java.util.List;

/**
 * The routing outcome. Jev fields are null when Jev did not answer; {@code reasons} always explains
 * the decision.
 */
public record RoutingDecision(
    Decision decision,
    List<String> reasons,
    Intent intent,
    Double intentConfidence,
    Double abuseProbability,
    String model,
    long latencyMillis,
    JevResponse.Usage usage) {

  public enum Decision {
    ALLOW,
    REVIEW,
    BLOCK
  }

  public RoutingDecision {
    reasons = List.copyOf(reasons);
  }

  static RoutingDecision withoutJev(String reason, long latencyMillis) {
    return new RoutingDecision(
        Decision.REVIEW, List.of(reason), null, null, null, null, latencyMillis, null);
  }
}
