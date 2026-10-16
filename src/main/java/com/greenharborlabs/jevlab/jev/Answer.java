package com.greenharborlabs.jevlab.jev;

import java.util.Map;

/** A typed answer from Jev. */
public sealed interface Answer {

  record Choice(String choice, Map<String, Double> probabilities, double confidence)
      implements Answer {
    public Choice {
      probabilities = Map.copyOf(probabilities);
    }
  }

  record Score(
      double score, Map<String, String> legend, Map<String, Double> probabilities, double confidence)
      implements Answer {
    public Score {
      legend = Map.copyOf(legend);
      probabilities = Map.copyOf(probabilities);
    }
  }

  /** Probability that the answer is yes. */
  record Noul(double noul) implements Answer {}
}
