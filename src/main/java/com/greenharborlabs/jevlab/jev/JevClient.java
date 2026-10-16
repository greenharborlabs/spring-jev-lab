package com.greenharborlabs.jevlab.jev;

import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls {@code POST /v1/systemone}. Retries only on 429, 529 and connection failures, with
 * exponential backoff. A read timeout is not retried because the call may have been processed.
 */
public class JevClient {

  /** Waits between retries; replaced in tests. */
  @FunctionalInterface
  public interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }

  private static final int DETAIL_LIMIT = 200;

  private final RestClient restClient;
  private final JevProperties properties;
  private final Sleeper sleeper;
  private final JsonMapper jsonMapper = JsonMapper.builder().build();

  public JevClient(RestClient.Builder builder, JevProperties properties, Sleeper sleeper) {
    this.restClient = builder.baseUrl(properties.baseUrl().toString()).build();
    this.properties = properties;
    this.sleeper = sleeper;
  }

  /** Asks every question against the same state in one request. */
  public JevResponse ask(Object state, Map<String, Question> questions) {
    if (!properties.hasApiKey()) {
      throw new JevException.NotConfigured();
    }
    if (questions.isEmpty()) {
      throw new IllegalArgumentException("at least one question is required");
    }
    String body = requestBody(state, questions);
    Duration backoff = properties.initialBackoff();
    for (int attempt = 1; ; attempt++) {
      try {
        return send(body);
      } catch (Retryable retryable) {
        if (attempt >= properties.maxAttempts()) {
          throw new JevException.Unavailable(
              retryable.getMessage() + " after " + attempt + " attempts", retryable.getCause());
        }
      }
      try {
        sleeper.sleep(backoff);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new JevException.Unavailable("interrupted while backing off", e);
      }
      backoff = backoff.multipliedBy(2);
    }
  }

  private String requestBody(Object state, Map<String, Question> questions) {
    Map<String, Object> wireQuestions = new LinkedHashMap<>();
    questions.forEach((id, question) -> wireQuestions.put(id, question.toWire()));
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("state", state);
    request.put("model", properties.model());
    request.put("questions", wireQuestions);
    return jsonMapper.writeValueAsString(request);
  }

  private JevResponse send(String body) {
    Exchange exchange;
    try {
      exchange =
          restClient
              .post()
              .uri("/v1/systemone")
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
              .contentType(MediaType.APPLICATION_JSON)
              .accept(MediaType.APPLICATION_JSON)
              .body(body)
              .exchange(
                  (request, response) ->
                      new Exchange(
                          response.getStatusCode().value(),
                          new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)),
                  true);
    } catch (ResourceAccessException e) {
      throw classify(e);
    }
    int status = exchange.status();
    if (status == 200) {
      return parse(exchange.body());
    }
    if (status == 401 || status == 403) {
      throw new JevException.Authentication(status);
    }
    if (status == 400 || status == 413 || status == 415 || status == 422) {
      throw new JevException.Rejected(status, detail(exchange.body()));
    }
    if (status == 429 || status == 529) {
      throw new Retryable("Jev returned HTTP " + status, null);
    }
    throw new JevException.Unavailable("Jev returned HTTP " + status, null);
  }

  private RuntimeException classify(ResourceAccessException e) {
    Throwable cause = e.getCause();
    if (cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException) {
      return new Retryable("could not connect to Jev", cause);
    }
    if (cause instanceof HttpTimeoutException) {
      return new JevException.Unavailable(
          "Jev did not answer within " + properties.readTimeout(), cause);
    }
    return new JevException.Unavailable("I/O failure calling Jev", cause);
  }

  private String detail(String body) {
    String redacted = body.replace(properties.apiKey(), "<redacted>").strip();
    return redacted.length() <= DETAIL_LIMIT ? redacted : redacted.substring(0, DETAIL_LIMIT) + "...";
  }

  private JevResponse parse(String body) {
    Map<?, ?> root;
    try {
      root = jsonMapper.readValue(body, Map.class);
    } catch (JacksonException e) {
      throw new JevException.Malformed("body is not a JSON object");
    }
    String model = text(root, "model");
    Map<?, ?> rawAnswers = object(root, "answers");
    Map<String, Answer> answers = new LinkedHashMap<>();
    rawAnswers.forEach((id, raw) -> answers.put(String.valueOf(id), answer(String.valueOf(id), raw)));
    Map<?, ?> usage = object(root, "usage");
    return new JevResponse(
        model,
        answers,
        new JevResponse.Usage(
            (long) number(usage, "input_tokens"), (long) number(usage, "output_tokens")));
  }

  private Answer answer(String id, Object raw) {
    if (!(raw instanceof Map<?, ?> fields)) {
      throw new JevException.Malformed("answer '" + id + "' is not an object");
    }
    String type = text(fields, "type");
    return switch (type) {
      case "choice" -> {
        Map<String, Double> probabilities = probabilities(fields);
        String choice = text(fields, "choice");
        if (!probabilities.containsKey(choice)) {
          throw new JevException.Malformed("choice '" + id + "' is not among its probabilities");
        }
        yield new Answer.Choice(choice, probabilities, unit(fields, "confidence"));
      }
      case "score" -> {
        Map<String, String> legend = new LinkedHashMap<>();
        object(fields, "legend").forEach((k, v) -> legend.put(String.valueOf(k), String.valueOf(v)));
        yield new Answer.Score(
            number(fields, "score"), legend, probabilities(fields), unit(fields, "confidence"));
      }
      case "noul" -> new Answer.Noul(unit(fields, "noul"));
      default -> throw new JevException.Malformed("answer '" + id + "' has unknown type " + type);
    };
  }

  private Map<String, Double> probabilities(Map<?, ?> fields) {
    Map<String, Double> probabilities = new LinkedHashMap<>();
    object(fields, "probabilities")
        .forEach(
            (k, v) -> {
              if (!(v instanceof Number n) || !inUnitRange(n.doubleValue())) {
                throw new JevException.Malformed("probability for '" + k + "' is not in [0, 1]");
              }
              probabilities.put(String.valueOf(k), n.doubleValue());
            });
    return probabilities;
  }

  private static String text(Map<?, ?> fields, String name) {
    if (fields.get(name) instanceof String s && !s.isBlank()) {
      return s;
    }
    throw new JevException.Malformed("'" + name + "' is missing or not a string");
  }

  private static Map<?, ?> object(Map<?, ?> fields, String name) {
    if (fields.get(name) instanceof Map<?, ?> m) {
      return m;
    }
    throw new JevException.Malformed("'" + name + "' is missing or not an object");
  }

  private static double number(Map<?, ?> fields, String name) {
    if (fields.get(name) instanceof Number n && Double.isFinite(n.doubleValue())) {
      return n.doubleValue();
    }
    throw new JevException.Malformed("'" + name + "' is missing or not a number");
  }

  private static double unit(Map<?, ?> fields, String name) {
    double value = number(fields, name);
    if (!inUnitRange(value)) {
      throw new JevException.Malformed("'" + name + "' is not in [0, 1]");
    }
    return value;
  }

  private static boolean inUnitRange(double value) {
    return value >= 0.0 && value <= 1.0;
  }

  private record Exchange(int status, String body) {}

  /** Internal signal for a retryable failure; never escapes {@link #ask}. */
  private static final class Retryable extends RuntimeException {
    Retryable(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
