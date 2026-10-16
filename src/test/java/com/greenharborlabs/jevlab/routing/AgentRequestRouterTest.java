package com.greenharborlabs.jevlab.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.greenharborlabs.jevlab.jev.Answer;
import com.greenharborlabs.jevlab.jev.JevClient;
import com.greenharborlabs.jevlab.jev.JevException;
import com.greenharborlabs.jevlab.jev.JevResponse;
import com.greenharborlabs.jevlab.jev.Question;
import com.greenharborlabs.jevlab.routing.RoutingDecision.Decision;
import java.net.ConnectException;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

class AgentRequestRouterTest {

  JevClient jev;
  AgentRequestRouter router;

  static final AgentRequest REQUEST =
      new AgentRequest("GET", "/api/articles/1", "agent-7", "summarize an article", Map.of(), null);

  @BeforeEach
  void setUp() {
    jev = mock(JevClient.class);
    router = new AgentRequestRouter(jev, RoutingProperties.defaults());
  }

  void jevAnswers(String intent, double confidence, double abuse) {
    when(jev.ask(any(), anyMap()))
        .thenReturn(
            new JevResponse(
                "jev-1.13.0",
                Map.of(
                    "intent", new Answer.Choice(intent, Map.of(intent, confidence), confidence),
                    "abuse", new Answer.Noul(abuse)),
                new JevResponse.Usage(100, 0)));
  }

  static Stream<Arguments> decisionTable() {
    return Stream.of(
        Arguments.of("read", 0.99, 0.80, Decision.BLOCK, "abuse_high"),
        Arguments.of("read", 0.99, 0.79, Decision.REVIEW, "abuse_possible"),
        Arguments.of("read", 0.99, 0.30, Decision.REVIEW, "abuse_possible"),
        Arguments.of("read", 0.60, 0.29, Decision.ALLOW, "read_confident"),
        Arguments.of("read", 0.59, 0.00, Decision.REVIEW, "low_confidence"),
        Arguments.of("write", 0.85, 0.00, Decision.ALLOW, "write_confident"),
        Arguments.of("write", 0.84, 0.00, Decision.REVIEW, "low_confidence"),
        Arguments.of("admin_or_destructive", 1.0, 0.0, Decision.REVIEW, "intent_requires_review"),
        Arguments.of("payment_or_credential", 1.0, 0.0, Decision.REVIEW, "intent_requires_review"),
        Arguments.of("other", 1.0, 0.0, Decision.REVIEW, "intent_requires_review"),
        Arguments.of("admin_or_destructive", 1.0, 0.95, Decision.BLOCK, "abuse_high"));
  }

  @ParameterizedTest
  @MethodSource("decisionTable")
  void appliesThresholds(
      String intent, double confidence, double abuse, Decision expected, String reason) {
    jevAnswers(intent, confidence, abuse);

    RoutingDecision decision = router.route(REQUEST);

    assertThat(decision.decision()).isEqualTo(expected);
    assertThat(decision.reasons()).containsExactly(reason);
    assertThat(decision.intent().wireName()).isEqualTo(intent);
    assertThat(decision.abuseProbability()).isEqualTo(abuse);
    assertThat(decision.model()).isEqualTo("jev-1.13.0");
  }

  static Stream<Arguments> failures() {
    return Stream.of(
        Arguments.of(new JevException.NotConfigured(), "jev_not_configured"),
        Arguments.of(new JevException.Authentication(401), "jev_auth_failed"),
        Arguments.of(new JevException.Rejected(422, "bad"), "jev_rejected"),
        Arguments.of(new JevException.Malformed("bad"), "jev_malformed"),
        Arguments.of(
            new JevException.Unavailable("down", new ConnectException()), "jev_unavailable"));
  }

  @ParameterizedTest
  @MethodSource("failures")
  void failsClosedWhenJevFails(JevException failure, String reason) {
    when(jev.ask(any(), anyMap())).thenThrow(failure);

    RoutingDecision decision = router.route(REQUEST);

    assertThat(decision.decision()).isEqualTo(Decision.REVIEW);
    assertThat(decision.reasons()).containsExactly(reason);
    assertThat(decision.intent()).isNull();
  }

  @Test
  void unknownIntentFailsClosed() {
    jevAnswers("launch_missiles", 1.0, 0.0);

    RoutingDecision decision = router.route(REQUEST);

    assertThat(decision.decision()).isEqualTo(Decision.REVIEW);
    assertThat(decision.reasons()).containsExactly("jev_malformed");
  }

  @Test
  void missingAbuseAnswerFailsClosed() {
    when(jev.ask(any(), anyMap()))
        .thenReturn(
            new JevResponse(
                "jev-1.13.0",
                Map.of("intent", new Answer.Choice("read", Map.of("read", 1.0), 1.0)),
                new JevResponse.Usage(1, 0)));

    assertThat(router.route(REQUEST).decision()).isEqualTo(Decision.REVIEW);
  }

  @Test
  @SuppressWarnings("unchecked")
  void sendsOnlyAllowlistedHeadersAndABoundedBody() {
    jevAnswers("read", 1.0, 0.0);
    AgentRequest request =
        new AgentRequest(
            "post",
            "/api/notes",
            "agent-7",
            "save a note",
            Map.of(
                "Authorization", "L402 macaroon:preimage",
                "Cookie", "session=secret",
                "X-Macaroon", "AgEDbG5k",
                "Content-Type", "application/json"),
            "x".repeat(5000));

    router.route(request);

    ArgumentCaptor<Object> state = ArgumentCaptor.forClass(Object.class);
    verify(jev).ask(state.capture(), anyMap());
    Map<String, Object> sent = (Map<String, Object>) state.getValue();
    assertThat(sent.get("method")).isEqualTo("POST");
    assertThat((Map<String, String>) sent.get("headers"))
        .containsOnlyKeys("content-type")
        .containsEntry("content-type", "application/json");
    assertThat(sent.toString()).doesNotContain("macaroon", "preimage", "secret", "AgEDbG5k");
    assertThat((String) sent.get("body_excerpt")).hasSize(AgentRequest.BODY_EXCERPT_LIMIT);
  }

  @Test
  @SuppressWarnings("unchecked")
  void asksMostRestrictiveIntentFirst() {
    jevAnswers("read", 1.0, 0.0);

    router.route(REQUEST);

    ArgumentCaptor<Map<String, Question>> questions = ArgumentCaptor.forClass(Map.class);
    verify(jev).ask(any(), questions.capture());
    Question.Choice intent = (Question.Choice) questions.getValue().get("intent");
    assertThat(intent.options().firstEntry().getKey()).isEqualTo("admin_or_destructive");
    assertThat(questions.getValue().get("abuse")).isInstanceOf(Question.Noul.class);
  }

  @Test
  void rejectsInconsistentThresholds() {
    assertThatThrownBy(() -> new RoutingProperties(0.3, 0.8, 0.6, 0.85))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RoutingProperties(1.2, 0.3, 0.6, 0.85))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requiresMethodAndPath() {
    assertThatThrownBy(() -> new AgentRequest(" ", "/a", null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
