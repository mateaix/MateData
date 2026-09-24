package io.matedata.identity.infrastructure;

import io.matedata.identity.*;
import io.matedata.shared.infrastructure.DocumentStore;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAccountRepository implements AccountRepository {
  private final DocumentStore store;

  public JdbcAccountRepository(DocumentStore store) {
    this.store = store;
  }

  public Optional<Account> find(String username) {
    return store.get("identity", username, Account.class);
  }

  public boolean createIfAbsent(Account account) {
    return store.insertIfAbsent("identity", account.username(), account);
  }

  public List<Account> all() {
    return store.list("identity", Account.class);
  }
}
