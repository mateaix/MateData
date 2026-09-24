package io.matedata.harness;

public record ModelConfiguration(
    String baseUrl, String model, String encryptedKey, int maxSteps, int timeoutSeconds) {}
