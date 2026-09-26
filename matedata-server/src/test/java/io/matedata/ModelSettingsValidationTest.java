package io.matedata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.matedata.harness.ModelConfiguration;
import io.matedata.harness.ModelConfiguration.Provider;
import io.matedata.harness.ModelConfigurationRepository;
import io.matedata.harness.application.ModelSettings;
import io.matedata.shared.CredentialCipher;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ModelSettingsValidationTest {
  record Fixture(ModelSettings settings, AtomicReference<ModelConfiguration> saved) {}

  Fixture fixture() {
    var saved = new AtomicReference<ModelConfiguration>();
    var repository = mock(ModelConfigurationRepository.class);
    when(repository.current()).thenAnswer(invocation -> Optional.ofNullable(saved.get()));
    doAnswer(
            invocation -> {
              saved.set(invocation.getArgument(0));
              return null;
            })
        .when(repository)
        .save(any());
    var cipher = mock(CredentialCipher.class);
    when(cipher.encrypt(anyString())).thenAnswer(invocation -> "enc:" + invocation.getArgument(0));
    return new Fixture(new ModelSettings(repository, cipher), saved);
  }

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

  @Test
  void ollamaNeedsNoKeyAndNeverKeepsAPreviousSecret() {
    var f = fixture();
    f.settings().save("OPENAI_COMPATIBLE", "https://api.example.test/v1", "gpt", "sk-1", 6, 60);
    assertThat(f.saved().get().encryptedKey()).isEqualTo("enc:sk-1");

    f.settings().save("OLLAMA", "http://127.0.0.1:11434", "gemma4", "", 6, 180);
    assertThat(f.saved().get().provider()).isEqualTo(Provider.OLLAMA);
    assertThat(f.saved().get().encryptedKey()).isEmpty();
    assertThat(f.settings().configured()).isTrue();
    assertThat(f.settings().view()).containsEntry("provider", "OLLAMA");

    // Switching back cannot silently revive the discarded key.
    assertThatThrownBy(
            () ->
                f.settings()
                    .save("OPENAI_COMPATIBLE", "https://api.example.test/v1", "gpt", "", 6, 60))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("API Key");
  }

  @Test
  void rejectsUnknownProvidersAndOutOfRangeBudgets() {
    var f = fixture();
    assertThatThrownBy(() -> f.settings().save("GEMINI", "http://127.0.0.1:1", "m", "", 6, 60))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> f.settings().save("OLLAMA", "http://127.0.0.1:1", "m", "", 13, 60))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> f.settings().save("OLLAMA", "http://127.0.0.1:1", "m", "", 6, 301))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(f.saved().get()).isNull();
  }

  @Test
  void configurationsSavedBeforeProvidersKeepTheirOpenAiMeaning() throws Exception {
    var legacy =
        "{\"baseUrl\":\"https://api.example.test/v1\",\"model\":\"gpt\",\"encryptedKey\":\"enc\","
            + "\"maxSteps\":6,\"timeoutSeconds\":60}";
    var configuration =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readValue(legacy, ModelConfiguration.class);
    assertThat(configuration.provider()).isEqualTo(Provider.OPENAI_COMPATIBLE);
    assertThat(configuration.requiresKey()).isTrue();
  }
}
