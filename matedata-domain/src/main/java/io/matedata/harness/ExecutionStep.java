package io.matedata.harness;

/** Sanitized observable execution metadata, excluding prompts, secrets and model reasoning. */
public record ExecutionStep(String name, String status, String detail, long durationMs) {}
