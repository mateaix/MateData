package io.matedata.conversation;

import java.util.*;

public record QueryRun(
    String id,
    String conversationId,
    String question,
    String datasetId,
    String mode,
    String status,
    String sql,
    List<String> columns,
    List<Map<String, Object>> rows,
    int rowCount,
    long durationMs,
    String createdAt,
    String answer,
    String error,
    List<Step> steps,
    String scopeFingerprint) {
  public QueryRun(
      String id,
      String conversationId,
      String question,
      String datasetId,
      String mode,
      String status,
      String sql,
      List<String> columns,
      List<Map<String, Object>> rows,
      int rowCount,
      long durationMs,
      String createdAt,
      String answer,
      String error,
      List<Step> steps) {
    this(
        id,
        conversationId,
        question,
        datasetId,
        mode,
        status,
        sql,
        columns,
        rows,
        rowCount,
        durationMs,
        createdAt,
        answer,
        error,
        steps,
        null);
  }

  public record Step(String name, String status, String detail, long durationMs) {}

  public QueryRun {
    columns = List.copyOf(columns);
    rows = List.copyOf(rows);
    steps = List.copyOf(steps);
  }

  public QueryRun withScope(String fingerprint) {
    return new QueryRun(
        id,
        conversationId,
        question,
        datasetId,
        mode,
        status,
        sql,
        columns,
        rows,
        rowCount,
        durationMs,
        createdAt,
        answer,
        error,
        steps,
        fingerprint);
  }

  public QueryRun summary() {
    return new QueryRun(
        id,
        conversationId,
        question,
        datasetId,
        mode,
        status,
        "",
        columns,
        List.of(),
        rowCount,
        durationMs,
        createdAt,
        answer,
        error,
        List.of(),
        scopeFingerprint);
  }
}
