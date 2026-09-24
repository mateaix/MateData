package io.matedata.harness;

public record AuditEntry(
    String id, String username, String action, String resource, String createdAt) {}
