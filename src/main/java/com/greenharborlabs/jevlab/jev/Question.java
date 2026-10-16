package com.greenharborlabs.jevlab.jev;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedMap;

/** A typed question for Jev. Each record renders its own wire form. */
public sealed interface Question {

  Map<String, Object> toWire();

  /** Selects one option. Option order is preserved because jev-1.13 leans toward the first. */
  record Choice(String instructions, SequencedMap<String, String> options) implements Question {
    public Choice {
      requireText(instructions, "instructions");
      Objects.requireNonNull(options, "options");
      if (options.size() < 2 || options.size() > 255) {
        throw new IllegalArgumentException("choice needs 2 to 255 options");
      }
      options = new LinkedHashMap<>(options);
    }

    @Override
    public Map<String, Object> toWire() {
      Map<String, Object> wire = new LinkedHashMap<>();
      wire.put("type", "choice");
      wire.put("instructions", instructions);
      wire.put("criteria", options);
      return wire;
    }
  }

  /** Rates against ordered levels. */
  record Score(String instructions, List<String> levels) implements Question {
    public Score {
      requireText(instructions, "instructions");
      Objects.requireNonNull(levels, "levels");
      if (levels.size() < 2 || levels.size() > 10) {
        throw new IllegalArgumentException("score needs 2 to 10 levels");
      }
      levels = List.copyOf(levels);
    }

    @Override
    public Map<String, Object> toWire() {
      Map<String, Object> wire = new LinkedHashMap<>();
      wire.put("type", "score");
      wire.put("instructions", instructions);
      wire.put("criteria", levels);
      return wire;
    }
  }

  /** Yes/no probability. Criteria are optional; pass both or neither. */
  record Noul(String instructions, String whenTrue, String whenFalse) implements Question {
    public Noul {
      requireText(instructions, "instructions");
      if ((whenTrue == null) != (whenFalse == null)) {
        throw new IllegalArgumentException("noul criteria need both true and false or neither");
      }
    }

    public Noul(String instructions) {
      this(instructions, null, null);
    }

    @Override
    public Map<String, Object> toWire() {
      Map<String, Object> wire = new LinkedHashMap<>();
      wire.put("type", "noul");
      wire.put("instructions", instructions);
      if (whenTrue != null) {
        wire.put("criteria", Map.of("true", whenTrue, "false", whenFalse));
      }
      return wire;
    }
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
