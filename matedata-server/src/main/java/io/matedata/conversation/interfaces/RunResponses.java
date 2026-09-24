package io.matedata.conversation.interfaces;

import io.matedata.conversation.QueryRun;
import java.math.*;
import java.util.*;

/** Preserve decimal precision across JSON/JavaScript: exact decimal cells travel as strings. */
public final class RunResponses {
  private RunResponses() {}

  public static QueryRun safeNumbers(QueryRun run) {
    var rows =
        run.rows().stream()
            .map(
                row -> {
                  var copy = new LinkedHashMap<String, Object>();
                  row.forEach(
                      (key, value) ->
                          copy.put(
                              key,
                              value instanceof BigDecimal decimal
                                  ? decimal.toPlainString()
                                  : value instanceof BigInteger integer
                                      ? integer.toString()
                                      : value instanceof Long integer
                                              && (integer > 9007199254740991L
                                                  || integer < -9007199254740991L)
                                          ? integer.toString()
                                          : value));
                  return (Map<String, Object>) copy;
                })
            .toList();
    return new QueryRun(
        run.id(),
        run.conversationId(),
        run.question(),
        run.datasetId(),
        run.mode(),
        run.status(),
        run.sql(),
        run.columns(),
        rows,
        run.rowCount(),
        run.durationMs(),
        run.createdAt(),
        run.answer(),
        run.error(),
        run.steps(),
        run.scopeFingerprint());
  }
}
