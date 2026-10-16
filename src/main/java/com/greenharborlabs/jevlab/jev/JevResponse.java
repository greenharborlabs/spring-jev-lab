package com.greenharborlabs.jevlab.jev;

import java.util.Map;

/** A parsed Jev response. Typed accessors fail with {@link JevException.Malformed}. */
public record JevResponse(String model, Map<String, Answer> answers, Usage usage) {

  public JevResponse {
    answers = Map.copyOf(answers);
  }

  public record Usage(long inputTokens, long outputTokens) {}

  public Answer.Choice choice(String questionId) {
    return answer(questionId, Answer.Choice.class);
  }

  public Answer.Score score(String questionId) {
    return answer(questionId, Answer.Score.class);
  }

  public Answer.Noul noul(String questionId) {
    return answer(questionId, Answer.Noul.class);
  }

  private <T extends Answer> T answer(String questionId, Class<T> type) {
    Answer answer = answers.get(questionId);
    if (!type.isInstance(answer)) {
      throw new JevException.Malformed("answer '" + questionId + "' is missing or not a " + type.getSimpleName());
    }
    return type.cast(answer);
  }
}
