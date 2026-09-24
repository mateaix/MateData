package io.matedata.identity.infrastructure;

import io.matedata.identity.PasswordHasher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class BCryptPasswordHasher implements PasswordHasher {
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

  public String encode(String password) {
    return encoder.encode(password);
  }

  public boolean matches(String password, String hash) {
    return encoder.matches(password, hash);
  }
}
