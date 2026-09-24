package io.matedata.identity;

import java.util.List;
import java.util.Optional;

public interface AccountRepository {
  Optional<Account> find(String username);

  /** Atomically creates an account; returns false if the username already exists. */
  boolean createIfAbsent(Account account);

  List<Account> all();
}
