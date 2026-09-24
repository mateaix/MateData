package io.matedata.harness;

import java.util.List;

public record EvaluationReport(
    String id, long passed, int total, long durationMs, List<Result> results) {
  public record Result(String name, boolean passed, String message, String runId) {}
}
