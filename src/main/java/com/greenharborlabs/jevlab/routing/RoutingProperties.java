package com.greenharborlabs.jevlab.routing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Routing thresholds. These are conservative starting points to measure against, not tuned values.
 */
@ConfigurationProperties("jev.routing")
public record RoutingProperties(
    @DefaultValue("0.8") double blockAbuseAt,
    @DefaultValue("0.3") double reviewAbuseAt,
    @DefaultValue("0.6") double allowReadAt,
    @DefaultValue("0.85") double allowWriteAt) {

  public RoutingProperties {
    requireUnit(blockAbuseAt, "block-abuse-at");
    requireUnit(reviewAbuseAt, "review-abuse-at");
    requireUnit(allowReadAt, "allow-read-at");
    requireUnit(allowWriteAt, "allow-write-at");
    if (reviewAbuseAt > blockAbuseAt) {
      throw new IllegalArgumentException("jev.routing.review-abuse-at must not exceed block-abuse-at");
    }
  }

  public static RoutingProperties defaults() {
    return new RoutingProperties(0.8, 0.3, 0.6, 0.85);
  }

  private static void requireUnit(double value, String name) {
    if (!(value >= 0.0 && value <= 1.0)) {
      throw new IllegalArgumentException("jev.routing." + name + " must be in [0, 1]");
    }
  }
}
