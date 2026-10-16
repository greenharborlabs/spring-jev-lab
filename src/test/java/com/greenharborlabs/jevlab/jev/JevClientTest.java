package com.greenharborlabs.jevlab.jev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withRawStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class JevClientTest {

  static final String KEY = "test-key-not-real";
  static final String URL = "https://api.typesafe.ai/v1/systemone";

  static final String OK_BODY =
      """
      {"model":"jev-1.13.0",
       "answers":{
         "intent":{"type":"choice","choice":"read","probabilities":{"write":0.1,"read":0.9},"confidence":0.8},
         "quality":{"type":"score","score":1.7,"legend":{"0":"poor","1":"fair","2":"good"},
                    "probabilities":{"0":0.1,"1":0.1,"2":0.8},"confidence":0.6},
         "abuse":{"type":"noul","noul":0.04}},
       "usage":{"input_tokens":120,"output_tokens":0}}
      """;

  MockRestServiceServer server;
  RestClient.Builder builder;
  List<Duration> sleeps;

  @BeforeEach
  void setUp() {
    builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    sleeps = new ArrayList<>();
  }

  JevClient client(String apiKey) {
    JevProperties defaults = JevProperties.withApiKey(apiKey);
    return new JevClient(builder, defaults, sleeps::add);
  }

  static Map<String, Question> questions() {
    SequencedMap<String, String> options = new LinkedHashMap<>();
    options.put("write", "changes data");
    options.put("read", "only reads data");
    Map<String, Question> questions = new LinkedHashMap<>();
    questions.put("intent", new Question.Choice("What does the request do?", options));
    questions.put("quality", new Question.Score("How clear is it?", List.of("poor", "fair", "good")));
    questions.put("abuse", new Question.Noul("Is it abusive?", "it is abusive", "it is benign"));
    return questions;
  }

  @Test
  void sendsTypedQuestionsWithBearerKey() {
    server
        .expect(once(), requestTo(URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer " + KEY))
        .andExpect(
            content()
                .json(
                    """
                    {"state":{"path":"/a"},"model":"jev-latest","questions":{
                      "intent":{"type":"choice","instructions":"What does the request do?",
                                "criteria":{"write":"changes data","read":"only reads data"}},
                      "quality":{"type":"score","instructions":"How clear is it?",
                                 "criteria":["poor","fair","good"]},
                      "abuse":{"type":"noul","instructions":"Is it abusive?",
                               "criteria":{"true":"it is abusive","false":"it is benign"}}}}
                    """,
                    JsonCompareMode.STRICT))
        .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

    client(KEY).ask(Map.of("path", "/a"), questions());

    server.verify();
  }

  @Test
  void keepsChoiceOptionOrderOnTheWire() {
    server
        .expect(once(), requestTo(URL))
        .andExpect(content().string(containsString("{\"write\":\"changes data\",\"read\"")))
        .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

    client(KEY).ask("state", questions());

    server.verify();
  }

  @Test
  void parsesEveryAnswerType() {
    server.expect(once(), requestTo(URL)).andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

    JevResponse response = client(KEY).ask("state", questions());

    assertThat(response.model()).isEqualTo("jev-1.13.0");
    assertThat(response.choice("intent").choice()).isEqualTo("read");
    assertThat(response.choice("intent").confidence()).isEqualTo(0.8);
    assertThat(response.choice("intent").probabilities()).containsEntry("read", 0.9);
    assertThat(response.score("quality").score()).isEqualTo(1.7);
    assertThat(response.score("quality").legend()).containsEntry("2", "good");
    assertThat(response.noul("abuse").noul()).isEqualTo(0.04);
    assertThat(response.usage()).isEqualTo(new JevResponse.Usage(120, 0));
  }

  @Test
  void wrongAnswerTypeIsMalformed() {
    server.expect(once(), requestTo(URL)).andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

    JevResponse response = client(KEY).ask("state", questions());

    assertThatThrownBy(() -> response.noul("intent")).isInstanceOf(JevException.Malformed.class);
    assertThatThrownBy(() -> response.choice("missing")).isInstanceOf(JevException.Malformed.class);
  }

  @Test
  void missingKeySendsNothing() {
    assertThatThrownBy(() -> client(" ").ask("state", questions()))
        .isInstanceOf(JevException.NotConfigured.class);

    server.verify();
  }

  @Test
  void unauthorizedMapsToAuthenticationWithoutTheKey() {
    server.expect(once(), requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

    assertThatThrownBy(() -> client(KEY).ask("state", questions()))
        .isInstanceOf(JevException.Authentication.class)
        .hasMessageNotContaining(KEY);
    assertThat(sleeps).isEmpty();
  }

  @Test
  void validationFailureIsRejectedAndRedactsAnEchoedKey() {
    server
        .expect(once(), requestTo(URL))
        .andRespond(
            withStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .body("{\"error\":\"bad criteria\",\"auth\":\"Bearer " + KEY + "\"}"));

    assertThatThrownBy(() -> client(KEY).ask("state", questions()))
        .isInstanceOf(JevException.Rejected.class)
        .hasMessageContaining("422")
        .hasMessageContaining("bad criteria")
        .hasMessageNotContaining(KEY);
    assertThat(sleeps).isEmpty();
  }

  @Test
  void retriesRateLimitThenSucceeds() {
    server.expect(once(), requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
    server.expect(once(), requestTo(URL)).andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

    JevResponse response = client(KEY).ask("state", questions());

    assertThat(response.choice("intent").choice()).isEqualTo("read");
    assertThat(sleeps).containsExactly(Duration.ofMillis(200));
    server.verify();
  }

  @Test
  void stopsAfterMaxAttemptsWhenOverloaded() {
    server.expect(times(3), requestTo(URL)).andRespond(withRawStatus(529));

    assertThatThrownBy(() -> client(KEY).ask("state", questions()))
        .isInstanceOf(JevException.Unavailable.class)
        .hasMessageContaining("529")
        .hasMessageContaining("3 attempts");
    assertThat(sleeps).containsExactly(Duration.ofMillis(200), Duration.ofMillis(400));
    server.verify();
  }

  @Test
  void retriesConnectionFailure() {
    server.expect(once(), requestTo(URL)).andRespond(withException(new ConnectException("refused")));
    server.expect(once(), requestTo(URL)).andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

    client(KEY).ask("state", questions());

    assertThat(sleeps).hasSize(1);
    server.verify();
  }

  @Test
  void readTimeoutIsNotRetried() {
    server
        .expect(once(), requestTo(URL))
        .andRespond(withException(new HttpTimeoutException("request timed out")));

    assertThatThrownBy(() -> client(KEY).ask("state", questions()))
        .isInstanceOf(JevException.Unavailable.class)
        .hasMessageContaining("did not answer");
    assertThat(sleeps).isEmpty();
    server.verify();
  }

  @Test
  void serverErrorIsUnavailableWithoutRetry() {
    server.expect(once(), requestTo(URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

    assertThatThrownBy(() -> client(KEY).ask("state", questions()))
        .isInstanceOf(JevException.Unavailable.class);
    server.verify();
  }

  @Test
  void rejectsBodiesOutsideTheContract() {
    List<String> bodies =
        List.of(
            "not json",
            "{\"model\":\"m\",\"answers\":{},\"usage\":{\"input_tokens\":1}}",
            OK_BODY.replace("\"choice\":\"read\"", "\"choice\":\"delete\""),
            OK_BODY.replace("\"confidence\":0.8", "\"confidence\":1.5"),
            OK_BODY.replace("\"noul\":0.04", "\"noul\":\"low\""),
            OK_BODY.replace("\"type\":\"noul\"", "\"type\":\"essay\""));
    for (String body : bodies) {
      setUp();
      server.expect(once(), requestTo(URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

      assertThatThrownBy(() -> client(KEY).ask("state", questions()))
          .as(body)
          .isInstanceOf(JevException.Malformed.class);
    }
  }

  @Test
  void propertiesNeverPrintTheKey() {
    assertThat(JevProperties.withApiKey(KEY).toString()).doesNotContain(KEY).contains("<redacted>");
  }
}
