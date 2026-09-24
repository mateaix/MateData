package io.matedata.identity;

import java.util.List;
import java.util.Optional;

public interface AccountRepository {
  Optional<Account> find(String username);

  void save(Account account);

  List<Account> all();
}
