package com.greenharborlabs.jevlab.jev;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Jev client settings. The API key comes from {@code TYPESAFE_API_KEY} only. */
@ConfigurationProperties("jev")
public record JevProperties(
    String apiKey,
    @DefaultValue("https://api.typesafe.ai") URI baseUrl,
    @DefaultValue("jev-latest") String model,
    @DefaultValue("2s") Duration connectTimeout,
    @DefaultValue("5s") Duration readTimeout,
    @DefaultValue("3") int maxAttempts,
    @DefaultValue("200ms") Duration initialBackoff) {

  public JevProperties {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("jev.max-attempts must be at least 1");
    }
  }

  public static JevProperties withApiKey(String apiKey) {
    return new JevProperties(
        apiKey,
        URI.create("https://api.typesafe.ai"),
        "jev-latest",
        Duration.ofSeconds(2),
        Duration.ofSeconds(5),
        3,
        Duration.ofMillis(200));
  }

  public boolean hasApiKey() {
    return apiKey != null && !apiKey.isBlank();
  }

  @Override
  public String toString() {
    return "JevProperties[apiKey=" + (hasApiKey() ? "<redacted>" : "<unset>")
        + ", baseUrl=" + baseUrl + ", model=" + model
        + ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout
        + ", maxAttempts=" + maxAttempts + ", initialBackoff=" + initialBackoff + "]";
  }
}
