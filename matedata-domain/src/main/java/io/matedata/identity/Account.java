package io.matedata.identity;

public record Account(String username, String displayName, String role, String passwordHash) {}
