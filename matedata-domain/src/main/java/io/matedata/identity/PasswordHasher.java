package io.matedata.identity;

public interface PasswordHasher {
  String encode(String password);

  boolean matches(String password, String hash);
}
