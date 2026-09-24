package io.matedata.harness;

import java.util.Optional;

public interface ModelConfigurationRepository {
  Optional<ModelConfiguration> current();

  void save(ModelConfiguration configuration);
}
