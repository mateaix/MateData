package io.matedata.harness;

public record EvaluationCase(
    String id,
    String name,
    String question,
    String datasetId,
    String expectedMetric,
    String expectedDimension) {}
