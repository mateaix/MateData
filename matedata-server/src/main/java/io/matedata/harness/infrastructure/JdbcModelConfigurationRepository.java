package io.matedata.harness.infrastructure;

import io.matedata.harness.*;
import io.matedata.shared.infrastructure.DocumentStore;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcModelConfigurationRepository implements ModelConfigurationRepository {
  private final DocumentStore store;

  public JdbcModelConfigurationRepository(DocumentStore store) {
    this.store = store;
  }

  public Optional<ModelConfiguration> current() {
    return store.get("harness-settings", "model", ModelConfiguration.class);
  }

  public void save(ModelConfiguration configuration) {
    store.save("harness-settings", "model", configuration);
  }
}
