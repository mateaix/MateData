package io.matedata.semantic;

import java.math.BigDecimal;
import java.util.*;

public final class ResultExpectation {
  private ResultExpectation() {}

  public static boolean matches(
      String dimension,
      String metric,
      Map<String, BigDecimal> expected,
      List<Map<String, Object>> rows) {
    if (rows.size() != expected.size()) return false;
    var seen = new HashSet<String>();
    for (var row : rows) {
      String group = Objects.toString(row.get(dimension), "");
      var value = row.get(metric);
      if (!seen.add(group) || !expected.containsKey(group) || !(value instanceof Number))
        return false;
      try {
        if (new BigDecimal(value.toString()).compareTo(expected.get(group)) != 0) return false;
      } catch (NumberFormatException e) {
        return false;
      }
    }
    return true;
  }
}
