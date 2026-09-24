package io.matedata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.matedata.harness.ModelConfigurationRepository;
import io.matedata.harness.application.ModelSettings;
import io.matedata.shared.CredentialCipher;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelSettingsValidationTest {
  @Test
  void missingSchemeIsAnInputErrorRatherThanAServerFailure() {
    var repository = mock(ModelConfigurationRepository.class);
    var settings = new ModelSettings(repository, mock(CredentialCipher.class));
    for (String address : List.of("", "api.example.test/v1", "//api.example.test/v1", "/v1"))
      assertThatThrownBy(() -> settings.save(address, "model", "key", 3, 10))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("地址");
    verifyNoInteractions(repository);
  }
}
