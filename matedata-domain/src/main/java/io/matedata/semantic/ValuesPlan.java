package io.matedata.semantic;

import java.util.Map;

/**
 * A governed lookup of one dimension's distinct values, used to ground equality filters in the
 * values that actually exist instead of guessed spellings.
 *
 * @param keyword optional substring for text dimensions; matched literally, never as a pattern
 * @param filters equality filters, in practice only the user's enforced row-level filters
 */
public record ValuesPlan(String dimension, String keyword, Map<String, String> filters, int limit) {
  public static final int MAX_LIMIT = 50;

  public ValuesPlan {
    filters = Map.copyOf(filters == null ? Map.of() : filters);
    keyword = keyword == null || keyword.isBlank() ? null : keyword.strip();
  }
}
